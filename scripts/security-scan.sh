#!/usr/bin/env bash
# Secret and dependency scanning (project specification section 15.3). Run locally, by
# scripts/test-all.sh and by the CI workflow; exits non-zero on any finding.
#
# Checks:
#   secrets-history   gitleaks over the full history of every ref (.gitleaks.toml)
#   secrets-tree      gitleaks over the working tree: tracked files and untracked files that are
#                     not ignored (ignored files such as .env never leave the machine)
#   env-files         no .env* file other than .env.example is tracked; .env and backups/ are ignored
#   sql-concat        no string-built SQL in services/api/src/main, no f-string SQL in services/scoring
#   npm-audit         npm audit --omit=dev --audit-level=high in apps/web
#   pip-audit         pip-audit over the locked set of services/scoring (uv export --locked)
#   dependency-check  OWASP Dependency-Check Gradle plugin in services/api, failing at CVSS >= 7
#   dependabot        .github/dependabot.yml covers npm, uv, gradle and github-actions weekly
#
# Degradation modes (always visible in the summary, never reported as PASS):
#   - gitleaks not on PATH: both secret checks fall back to a git grep pattern set over the same
#     files and history and are reported DEGRADED; --no-fallback makes a missing gitleaks a FAIL.
#   - NVD_API_KEY not set: dependency-check is reported SKIPPED(no NVD_API_KEY), because the NVD
#     feed cannot be downloaded in reasonable time without a key. NVD_DATA_DIR keeps the feed
#     between runs.
#
# Usage: scripts/security-scan.sh [--no-fallback] [--strict]
#   --no-fallback  fail instead of falling back when gitleaks is missing
#   --strict       exit 3 when any check was SKIPPED or DEGRADED (and nothing failed)
# Exit status: 0 no finding, 1 finding or a check that could not run, 2 usage error, 3 see --strict.
# Requires bash (Git Bash or WSL on Windows), git, uv, node/npm and, for the full set, gitleaks
# and a JDK 25 in JAVA_HOME.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Exact tool pins (recorded in docs/adr/0005-version-pins.md).
GITLEAKS_VERSION="8.30.1"
PIP_AUDIT_VERSION="2.10.1"

usage() { printf 'Usage: %s [--no-fallback] [--strict]\n' "$(basename "$0")"; }

allow_fallback=true
strict=false
for arg in "$@"; do
  case "$arg" in
    --no-fallback) allow_fallback=false ;;
    --strict) strict=true ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

command -v git > /dev/null 2>&1 || { printf 'security-scan: git is required\n' >&2; exit 1; }
if [ "$(git rev-parse --is-inside-work-tree 2> /dev/null)" != "true" ]; then
  printf 'security-scan: %s is not a Git checkout; the scan needs the history and the ignore rules\n' "$ROOT" >&2
  exit 1
fi

RESULTS=()
record() {
  # record <check> <status> <seconds> <note>
  RESULTS+=("$1|$2|$3|$4")
}
banner() { printf '\n==== security: %s ====\n' "$1"; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# run_check <check> <function>: the function prints its findings, returns 0 (pass), 1 (finding)
# or 10 (skipped) / 11 (degraded) with the reason in $WORK/note.
run_check() {
  local check="$1" fn="$2" start end status note
  banner "$check"
  : > "$WORK/note"
  start="$(date +%s)"
  set +e
  "$fn"
  status=$?
  set -e
  end="$(date +%s)"
  note="$(tr -d '\r\n' < "$WORK/note")"
  case "$status" in
    0) record "$check" "PASS" "$((end - start))" "$note" ;;
    10) record "$check" "SKIPPED(${note})" "$((end - start))" "" ;;
    11) record "$check" "DEGRADED" "$((end - start))" "$note" ;;
    *) record "$check" "FAIL" "$((end - start))" "$note" ;;
  esac
}

note() { printf '%s' "$*" > "$WORK/note"; }

have_gitleaks() {
  command -v gitleaks > /dev/null 2>&1 || return 1
  local version
  version="$(gitleaks version 2> /dev/null | tr -d '\r' | sed 's/^v//')"
  if [ "$version" != "$GITLEAKS_VERSION" ]; then
    printf 'note: gitleaks %s found, %s is the pinned version\n' "$version" "$GITLEAKS_VERSION"
  fi
  return 0
}

# Fallback secret patterns (git grep -E -i), used only without gitleaks.
FALLBACK_PATTERNS=(
  '-----BEGIN ([A-Z]+ )?PRIVATE KEY-----'
  'AKIA[0-9A-Z]{16}'
  'gh[pousr]_[A-Za-z0-9]{36,}'
  'xox[abprs]-[A-Za-z0-9-]{10,}'
  'eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*'
  '(api[_-]?key|secret|token|passwd|password)[A-Za-z0-9_]*["'"'"']?[[:space:]]*[:=][[:space:]]*["'"'"'][A-Za-z0-9_+/=.-]{16,}["'"'"']'
)

# Exact synthetic values reviewed in .gitleaks.toml; the fallback ignores matches containing them.
fallback_allowlist() {
  sed -n '/BEGIN synthetic values/,/END synthetic values/p' .gitleaks.toml |
    sed -n "s/^[[:space:]]*'''^\(.*\)\$''',[[:space:]]*\$/\1/p" |
    sed 's/\\\././g'
}

fallback_grep() {
  # fallback_grep <tree|history>: prints matches not covered by the allowlist.
  local args=() pattern
  for pattern in "${FALLBACK_PATTERNS[@]}"; do args+=(-e "$pattern"); done
  fallback_allowlist > "$WORK/allow"
  if [ "$1" = tree ]; then
    git grep -I -n -o -i -E --untracked "${args[@]}" -- . ':!.gitleaks.toml' || true
  else
    git rev-list --all | xargs -r -n 64 git grep -I -n -o -i -E "${args[@]}" -- . ':!.gitleaks.toml' || true
  fi | grep -v -F -f "$WORK/allow" | drop_passphrases | sort -u
}

# Drops key/token/password assignments whose quoted value is a hyphen-joined word passphrase
# (the fixed test passwords of the suites); random-looking values are kept.
drop_passphrases() {
  awk '
    {
      value = ""
      if (match($0, /[:=][[:space:]]*["\047][^"\047]+["\047]$/)) {
        value = substr($0, RSTART, RLENGTH)
        sub(/^[:=][[:space:]]*["\047]/, "", value)
        sub(/["\047]$/, "", value)
      }
      if (value != "") {
        if (value ~ /^([A-Za-z]+-)+[A-Za-z0-9]+$/) next
        if (value !~ /[0-9]/ && value ~ /-/) next
      }
      print
    }'
}

check_secrets_history() {
  if have_gitleaks; then
    note "gitleaks $GITLEAKS_VERSION, all refs"
    gitleaks git --config .gitleaks.toml --log-opts="--all" --redact --no-banner --no-color . 2>&1
    return $?
  fi
  if [ "$allow_fallback" = false ]; then
    note "gitleaks not found on PATH (--no-fallback)"
    return 1
  fi
  local hits
  hits="$(fallback_grep history)"
  if [ -n "$hits" ]; then
    printf '%s\n' "$hits" | sed -E 's/(:[0-9]+:).*/\1 <match redacted>/'
    note "gitleaks not found; git grep fallback found $(printf '%s\n' "$hits" | wc -l | tr -d ' ') match(es)"
    return 1
  fi
  note "gitleaks not found; git grep pattern fallback over all refs found nothing"
  return 11
}

check_secrets_tree() {
  if have_gitleaks; then
    # Copy the files Git would see (tracked plus untracked, not ignored) and scan that copy.
    local tree="$WORK/tree"
    mkdir -p "$tree"
    git ls-files -z --cached --others --exclude-standard |
      while IFS= read -r -d '' file; do
        [ -f "$file" ] && printf '%s\0' "$file"
      done |
      tar --null -T - -cf - | tar -xf - -C "$tree"
    note "gitleaks $GITLEAKS_VERSION, $(find "$tree" -type f | wc -l | tr -d ' ') files"
    gitleaks dir --config .gitleaks.toml --redact --no-banner --no-color "$tree" 2>&1
    return $?
  fi
  if [ "$allow_fallback" = false ]; then
    note "gitleaks not found on PATH (--no-fallback)"
    return 1
  fi
  local hits
  hits="$(fallback_grep tree)"
  if [ -n "$hits" ]; then
    printf '%s\n' "$hits" | sed -E 's/(:[0-9]+:).*/\1 <match redacted>/'
    note "gitleaks not found; git grep fallback found $(printf '%s\n' "$hits" | wc -l | tr -d ' ') match(es)"
    return 1
  fi
  note "gitleaks not found; git grep pattern fallback over the working tree found nothing"
  return 11
}

check_env_files() {
  local tracked failed=0
  tracked="$(git ls-files -- '.env*' ':(glob)**/.env*' | grep -v -x -F '.env.example' || true)"
  if [ -n "$tracked" ]; then
    printf '%s\n' "$tracked" | sed 's/^/tracked environment file: /'
    failed=1
  fi
  if ! git check-ignore -q --no-index .env; then
    printf '.env is not ignored by .gitignore\n'
    failed=1
  fi
  if ! git check-ignore -q --no-index backups/vitadelta-check.dump.age; then
    printf 'backups/ is not ignored by .gitignore\n'
    failed=1
  fi
  if git check-ignore -q --no-index .env.example; then
    printf '.env.example is ignored but must stay tracked\n'
    failed=1
  fi
  if [ "$failed" -eq 0 ]; then
    printf 'only .env.example is tracked; .env and backups/ are ignored\n'
    note "only .env.example tracked"
  fi
  return "$failed"
}

check_sql_concat() {
  command -v uv > /dev/null 2>&1 || { note "uv not found on PATH"; return 1; }
  local output status
  set +e
  output="$(uv run --no-project python -I scripts/security/check_sql_concat.py "$ROOT" 2>&1)"
  status=$?
  set -e
  printf '%s\n' "$output"
  note "$(printf '%s\n' "$output" | tail -n 1)"
  return "$status"
}

check_npm_audit() {
  command -v npm > /dev/null 2>&1 || { note "npm not found on PATH"; return 1; }
  [ -f apps/web/package-lock.json ] || { note "apps/web/package-lock.json not found"; return 1; }
  (cd apps/web && npm audit --omit=dev --audit-level=high) || {
    note "high or critical advisories, or the registry could not be reached (see above)"
    return 1
  }
  note "apps/web, runtime dependencies"
}

check_pip_audit() {
  command -v uv > /dev/null 2>&1 || { note "uv not found on PATH"; return 1; }
  local requirements="$WORK/scoring-requirements.txt"
  (cd services/scoring && uv export --locked --all-groups --no-emit-project --format requirements-txt \
    --quiet --output-file "$requirements") || { note "uv export --locked failed"; return 1; }
  uvx --quiet "pip-audit==${PIP_AUDIT_VERSION}" --requirement "$requirements" --require-hashes \
    --disable-pip --progress-spinner off || {
    note "known vulnerabilities, or the advisory service could not be reached (see above)"
    return 1
  }
  note "services/scoring, $(grep -c '==' "$requirements" | tr -d ' ') locked packages (all groups)"
}

check_dependency_check() {
  if [ -z "${NVD_API_KEY:-}" ]; then
    note "no NVD_API_KEY"
    return 10
  fi
  if ! grep -q 'org.owasp.dependencycheck' services/api/build.gradle.kts; then
    note "NVD_API_KEY is set but the org.owasp.dependencycheck plugin is not applied in services/api"
    return 1
  fi
  (cd services/api && sh ./gradlew --no-daemon --console=plain dependencyCheckAnalyze) || {
    note "dependencies with CVSS >= 7, or the analysis failed (see above)"
    return 1
  }
  note "services/api, CVSS threshold 7"
}

check_dependabot() {
  local file=.github/dependabot.yml failed=0 ecosystem
  [ -f "$file" ] || { note "$file not found"; return 1; }
  for ecosystem in npm gradle github-actions; do
    if ! grep -Eq "package-ecosystem:[[:space:]]*\"?${ecosystem}\"?[[:space:]]*\$" "$file"; then
      printf 'no %s entry\n' "$ecosystem"
      failed=1
    fi
  done
  if ! grep -Eq 'package-ecosystem:[[:space:]]*"?(uv|pip)"?[[:space:]]*$' "$file"; then
    printf 'no uv (or pip) entry\n'
    failed=1
  fi
  if grep -Eq 'interval:[[:space:]]*"?(daily|monthly|quarterly|semiannually|yearly)' "$file"; then
    printf 'every update schedule must be weekly\n'
    failed=1
  fi
  if [ "$failed" -eq 0 ]; then
    printf 'npm, uv, gradle and github-actions are updated weekly\n'
    note "npm, uv, gradle, github-actions weekly"
  fi
  return "$failed"
}

run_check secrets-history check_secrets_history
run_check secrets-tree check_secrets_tree
run_check env-files check_env_files
run_check sql-concat check_sql_concat
run_check npm-audit check_npm_audit
run_check pip-audit check_pip_audit
run_check dependency-check check_dependency_check
run_check dependabot check_dependabot

printf '\n==== security summary ====\n'
printf '%-17s %-26s %-7s %s\n' "CHECK" "RESULT" "SECONDS" "NOTE"
failed=0
incomplete=0
for row in "${RESULTS[@]}"; do
  IFS='|' read -r r_check r_status r_secs r_note <<< "$row"
  printf '%-17s %-26s %-7s %s\n' "$r_check" "$r_status" "$r_secs" "$r_note"
  case "$r_status" in
    FAIL) failed=$((failed + 1)) ;;
    PASS) ;;
    *) incomplete=$((incomplete + 1)) ;;
  esac
done

if [ "$failed" -ne 0 ]; then
  printf '\nRESULT: FAILED (%d check(s) failed)\n' "$failed"
  exit 1
fi
if [ "$incomplete" -ne 0 ]; then
  printf '\nRESULT: OK WITH %d CHECK(S) SKIPPED OR DEGRADED (not a full scan; see the summary)\n' "$incomplete"
  [ "$strict" = true ] && exit 3
  exit 0
fi
printf '\nRESULT: OK\n'
