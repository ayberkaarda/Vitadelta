#!/usr/bin/env bash
# Runs every test suite that exists in the repository and prints a summary.
# Suites: contracts, scoring (services/scoring), data-tools (scripts/dsld, scripts/security),
# security (scripts/security-scan.sh), api (services/api), web (apps/web) and the opt-in steps of
# RUN_E2E=1: the nginx routing check (docker) and the e2e and adversarial suites (apps/web/e2e;
# a running, seeded stack).
# A suite whose directory is absent is reported as SKIPPED. Exit status is non-zero
# if any step fails. Requires bash (Git Bash or WSL on Windows) plus the toolchain
# of each present suite: node/npx, uv, java.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Exact tool pins for contract checks (recorded in docs/adr/0005-version-pins.md).
REDOCLY_CLI_VERSION="2.83.0"
AJV_CLI_VERSION="5.0.0"
AJV_FORMATS_VERSION="3.0.1"

RESULTS=()
suite_broken=false

record() {
  # record <suite> <step> <status> <seconds>
  RESULTS+=("$1|$2|$3|$4")
}

banner() {
  printf '\n==== %s: %s ====\n' "$1" "$2"
}

# run_step <suite> <step> <dir> <command...>
# Once a step of a suite fails, its remaining steps are reported as NOT RUN.
run_step() {
  local suite="$1" step="$2" dir="$3"
  shift 3
  if [ "$suite_broken" = true ]; then
    record "$suite" "$step" "NOT RUN" "-"
    return 0
  fi
  banner "$suite" "$step"
  local start end status
  start="$(date +%s)"
  if (cd "$dir" && "$@"); then
    status="PASS"
  else
    status="FAIL"
    suite_broken=true
  fi
  end="$(date +%s)"
  record "$suite" "$step" "$status" "$((end - start))"
}

# begin_suite <suite> <dir> <required tools...>
# Returns non-zero when the suite must not run (absent directory or missing tool).
begin_suite() {
  local suite="$1" dir="$2"
  shift 2
  suite_broken=false
  if [ ! -d "$dir" ]; then
    record "$suite" "($dir not present)" "SKIPPED" "-"
    return 1
  fi
  local tool
  for tool in "$@"; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      record "$suite" "tool '$tool' not found on PATH" "FAIL" "-"
      return 1
    fi
  done
  return 0
}

require_file() {
  if [ ! -f "$1" ]; then
    printf 'missing file: %s\n' "$1" >&2
    return 1
  fi
}

lint_openapi() {
  require_file "$1" && npx --yes "@redocly/cli@${REDOCLY_CLI_VERSION}" lint "$1"
}

compile_json_schema() {
  require_file "$1" && npx --yes -p "ajv-cli@${AJV_CLI_VERSION}" -p "ajv-formats@${AJV_FORMATS_VERSION}" \
    ajv compile --spec=draft2020 -c ajv-formats -s "$1"
}

# contracts
if begin_suite contracts contracts npx; then
  run_step contracts "redocly lint public-api" "$ROOT" lint_openapi contracts/public-api.openapi.yaml
  suite_broken=false
  run_step contracts "redocly lint scoring-api" "$ROOT" lint_openapi contracts/scoring-api.openapi.yaml
  suite_broken=false
  run_step contracts "ajv compile import-products" "$ROOT" compile_json_schema contracts/schemas/import-products.schema.json
fi

# scoring
if begin_suite scoring services/scoring uv; then
  run_step scoring "uv sync --locked" services/scoring uv sync --locked
  run_step scoring "ruff check" services/scoring uv run --locked ruff check .
  run_step scoring "ruff format --check" services/scoring uv run --locked ruff format --check .
  run_step scoring "mypy" services/scoring uv run --locked mypy
  run_step scoring "pytest" services/scoring uv run --locked pytest
fi

# data-tools
if begin_suite data-tools scripts/dsld/tests uv; then
  run_step data-tools "pytest dsld" "$ROOT" uv run --no-project --with pytest==9.2.0 pytest scripts/dsld/tests -q
  suite_broken=false
  run_step data-tools "pytest security tools" "$ROOT" \
    uv run --no-project --with pytest==9.2.0 pytest scripts/security/tests -q -p no:cacheprovider
  suite_broken=false
  run_step data-tools "security-scan self-tests" "$ROOT" bash scripts/security/selftest-security-scan.sh
fi

# security (scripts/security-scan.sh, section 15.3): secret scan, tracked .env files, SQL built
# from strings, dependency audits and the Dependabot configuration. RUN_SECURITY_SCAN=0 leaves it
# to a separate run (the CI workflow runs it in its own job).
if [ "${RUN_SECURITY_SCAN:-1}" = "0" ]; then
  record security "(RUN_SECURITY_SCAN=0: run separately)" "SKIPPED" "-"
elif begin_suite security scripts git uv npm; then
  run_step security "security-scan.sh" "$ROOT" bash scripts/security-scan.sh
fi

# api
if begin_suite api services/api java docker; then
  run_step api "gradlew check" services/api sh ./gradlew check
fi

# web
if begin_suite web apps/web node npm; then
  run_step web "npm ci" apps/web npm ci --no-audit --no-fund
  run_step web "npm run api:check" apps/web npm run api:check
  run_step web "npm run lint" apps/web npm run lint
  run_step web "npm test" apps/web npm test
  run_step web "npm run build" apps/web npm run build
fi

# nginx (opt-in with RUN_E2E=1): routing, caching and header check of infra/nginx against the
# web build of the step above, in a throwaway container (no stack needed).
if [ "${RUN_E2E:-0}" != "1" ]; then
  record nginx "(opt-in: set RUN_E2E=1)" "SKIPPED" "-"
elif begin_suite nginx infra/nginx docker curl; then
  run_step nginx "check-routing.sh" "$ROOT" bash infra/nginx/check-routing.sh
fi

# Restarts the api service so the in-memory per-client rate limits start empty, then waits
# until the API answers through nginx again.
restart_api() {
  docker compose --env-file .env -f infra/docker-compose.yml restart api &&
    for _ in $(seq 1 90); do
      if curl -fsS --max-time 5 "$E2E_BASE_URL/api/v1/health" >/dev/null 2>&1; then return 0; fi
      sleep 2
    done
  printf 'api did not answer after the restart\n' >&2
  return 1
}

# e2e (opt-in): RUN_E2E=1 runs the Playwright suites of apps/web (the regular suite, then the
# adversarial suite after an api restart) against an already running, seeded stack
# (scripts/dev-up.sh, scripts/seed.sh); they are skipped while the stack is down.
E2E_BASE_URL="${E2E_BASE_URL:-http://localhost:8080}"
if [ "${RUN_E2E:-0}" != "1" ]; then
  record e2e "(opt-in: set RUN_E2E=1)" "SKIPPED" "-"
elif ! curl -fsS --max-time 10 "$E2E_BASE_URL/api/v1/health" >/dev/null 2>&1; then
  record e2e "(stack not reachable)" "SKIPPED" "-"
elif begin_suite e2e apps/web node npm docker; then
  run_step e2e "install headless chromium" apps/web npm run e2e:install
  run_step e2e "npm run e2e" apps/web npm run e2e
  run_step e2e "restart api (rate limits)" "$ROOT" restart_api
  run_step e2e "npm run e2e:adversarial" apps/web npm run e2e:adversarial
fi

printf '\n==== summary ====\n'
printf '%-10s %-34s %-8s %s\n' "SUITE" "STEP" "RESULT" "SECONDS"
failed=0
for row in "${RESULTS[@]}"; do
  IFS='|' read -r r_suite r_step r_status r_secs <<< "$row"
  printf '%-10s %-34s %-8s %s\n' "$r_suite" "$r_step" "$r_status" "$r_secs"
  if [ "$r_status" = "FAIL" ] || [ "$r_status" = "NOT RUN" ]; then
    failed=$((failed + 1))
  fi
done

if [ "$failed" -ne 0 ]; then
  printf '\nRESULT: FAILED (%d step(s) failed or did not run)\n' "$failed"
  exit 1
fi
printf '\nRESULT: OK\n'
