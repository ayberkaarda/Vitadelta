#!/usr/bin/env bash
# Secret and dependency scanning (project specification section 15.3). Run locally, by
# scripts/test-all.sh and by the CI workflow; exits non-zero on any finding.
#
# Checks:
#   secrets-history   gitleaks over the full history of every ref (.gitleaks.toml); every object of
#                     the history must be readable first
#   secrets-tree      gitleaks over the working tree: tracked files and untracked files that are
#                     not ignored (ignored files such as .env never leave the machine); every listed
#                     file must be copied into the scanned tree, so an unreadable file is a failure
#   env-files         no .env* file other than .env.example is tracked; .env and backups/ are ignored
#   sql-concat        no string-built SQL in services/api/src/main, no f-string SQL in services/scoring
#   npm-audit         npm audit --omit=dev --audit-level=high in apps/web
#   pip-audit         pip-audit over the locked set of services/scoring (uv export --locked)
#   dependency-check  OWASP Dependency-Check Gradle plugin in services/api, failing at CVSS >= 7
#   dependabot        .github/dependabot.yml covers npm, uv, gradle, github-actions, docker and
#                     docker-compose weekly
#   actions-pinned    every action of .github/workflows is pinned by a full commit SHA with its
#                     version in a comment
#
# Each check runs in its own subshell: a check that fails, exits or stops on an error is recorded as
# FAIL and the remaining checks still run.
#
# Degradation modes (always visible in the summary, never reported as PASS):
#   - gitleaks not on PATH: both secret checks fall back to a pattern set over the same files and
#     history (grep over the copied tree, git grep over every revision) and are reported DEGRADED;
#     --no-fallback makes a missing gitleaks a FAIL. The fallback suppresses a match only when the
#     credential value equals one of the reviewed synthetic values of .gitleaks.toml exactly.
#   - NVD_API_KEY not set: dependency-check is reported SKIPPED(no NVD_API_KEY), because the NVD
#     feed cannot be downloaded in reasonable time without a key. NVD_DATA_DIR keeps the feed
#     between runs.
#
# Self-tests in throwaway repositories: scripts/security/selftest-security-scan.sh
#
# Usage: scripts/security-scan.sh [--no-fallback] [--strict]
#   --no-fallback  fail instead of falling back when gitleaks is missing
#   --strict       exit 3 when any check was SKIPPED or DEGRADED (and nothing failed)
# Exit status: 0 no finding, 1 finding or a check that could not run, 2 usage error, 3 see --strict.
# Requires bash (Git Bash or WSL on Windows), git, tar, uv, node/npm and, for the full set, gitleaks
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
# or 10 (skipped) / 11 (degraded) with the reason in $WORK/note. It runs in a subshell, so neither
# an exit nor an errexit inside it can end the scan; its status alone decides the result.
run_check() {
  local check="$1" fn="$2" start end status note
  banner "$check"
  : > "$WORK/note"
  start="$(date +%s)"
  set +e
  ("$fn")
  status=$?
  set -e
  end="$(date +%s)"
  note="$(tr -d '\r\n' < "$WORK/note")"
  case "$status" in
    0) record "$check" "PASS" "$((end - start))" "$note" ;;
    10) record "$check" "SKIPPED(${note})" "$((end - start))" "" ;;
    11) record "$check" "DEGRADED" "$((end - start))" "$note" ;;
    *) record "$check" "FAIL" "$((end - start))" "${note:-exit status $status}" ;;
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

# Every object reachable from any ref must be readable; a missing or corrupt object would make a
# history scan silently incomplete. Returns 0 when the history is complete.
history_readable() {
  if ! git rev-list --all --objects > "$WORK/objects" 2> "$WORK/objects.err"; then
    cat "$WORK/objects.err"
    note "the history could not be listed (git rev-list --all --objects failed)"
    return 1
  fi
  if ! git cat-file --batch-check='%(objectname) %(objecttype) %(rest)' < "$WORK/objects" > "$WORK/object-types" 2> "$WORK/objects.err"; then
    cat "$WORK/objects.err"
    note "the history objects could not be read (git cat-file failed)"
    return 1
  fi
  local missing
  missing="$(grep -c ' missing$' "$WORK/object-types" || true)"
  if [ "$missing" != "0" ]; then
    grep ' missing$' "$WORK/object-types" | sed 's/^/unreadable history object: /' | head -n 20
    note "$missing history object(s) missing or unreadable; the history cannot be scanned completely"
    return 1
  fi
  return 0
}

# Copies the files Git would see (tracked plus untracked, not ignored) into $WORK/tree. Any failure
# of the listing, the archive or the unpacking, and any listed file that did not arrive in the
# copy, is an error: the scan never runs on an incomplete tree.
prepare_tree() {
  local tree="$WORK/tree" file listed copied
  mkdir -p "$tree" || { note "could not create the temporary tree"; return 1; }
  if ! git ls-files -z --cached --others --exclude-standard > "$WORK/files.all"; then
    note "git ls-files failed; the working tree could not be listed"
    return 1
  fi
  # Tracked files deleted in the working tree have nothing to scan; everything else is copied.
  : > "$WORK/files.present"
  while IFS= read -r -d '' file; do
    if [ -e "$file" ] || [ -L "$file" ]; then printf '%s\0' "$file" >> "$WORK/files.present"; fi
  done < "$WORK/files.all"
  listed="$(tr -cd '\000' < "$WORK/files.present" | wc -c | tr -d ' ')"
  if ! tar --null --no-recursion -T "$WORK/files.present" -cf "$WORK/tree.tar" 2> "$WORK/tar.err"; then
    cat "$WORK/tar.err"
    note "could not read every file of the working tree (see above); the scan would be incomplete"
    return 1
  fi
  if ! tar -xf "$WORK/tree.tar" -C "$tree" 2> "$WORK/tar.err"; then
    cat "$WORK/tar.err"
    note "could not unpack the copy of the working tree"
    return 1
  fi
  copied="$(cd "$tree" && find . \( -type f -o -type l \) | wc -l | tr -d ' ')"
  if [ "$copied" != "$listed" ]; then
    note "only $copied of $listed files of the working tree were copied; the scan would be incomplete"
    return 1
  fi
  printf '%s' "$listed" > "$WORK/tree.count"
  return 0
}

# Fallback secret patterns (extended regular expressions, case-insensitive), used only without
# gitleaks.
FALLBACK_PATTERNS=(
  '-----BEGIN ([A-Z]+ )?PRIVATE KEY-----'
  'AKIA[0-9A-Z]{16}'
  'gh[pousr]_[A-Za-z0-9]{36,}'
  'xox[abprs]-[A-Za-z0-9-]{10,}'
  'eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*'
  '(api[_-]?key|secret|token|passwd|password)[A-Za-z0-9_]*["'"'"']?[[:space:]]*[:=][[:space:]]*["'"'"'][A-Za-z0-9_+/=.-]{16,}["'"'"']'
)

# Exact synthetic values reviewed in .gitleaks.toml, one per line.
fallback_allowlist() {
  sed -n '/BEGIN synthetic values/,/END synthetic values/p' .gitleaks.toml |
    sed -n "s/^[[:space:]]*'''^\(.*\)\$''',[[:space:]]*\$/\1/p" |
    sed 's/\\\././g'
}

# Reads "<prefix fields>:<match>" lines on stdin and prints those whose credential value is not an
# allowlisted value. <fields> is the number of ":"-separated fields before the match (2 for
# path:line, 3 for revision:path:line). The value is the quoted part of an assignment, otherwise
# the whole match; it must equal an allowlisted value exactly to be suppressed.
drop_allowlisted() {
  awk -v fields="$1" -v allow_file="$WORK/allow" '
    BEGIN { while ((getline line < allow_file) > 0) { sub(/\r$/, "", line); if (line != "") allowed[line] = 1 } }
    {
      rest = $0
      for (i = 0; i < fields; i++) { p = index(rest, ":"); if (p == 0) { rest = ""; break }; rest = substr(rest, p + 1) }
      value = rest
      if (match(value, /["\047][^"\047]*["\047]$/)) value = substr(value, RSTART + 1, RLENGTH - 2)
      if (!(value in allowed)) print
    }'
}

pattern_file() {
  local pattern
  : > "$WORK/patterns"
  for pattern in "${FALLBACK_PATTERNS[@]}"; do printf '%s\n' "$pattern" >> "$WORK/patterns"; done
}

# fallback_tree: matches in the copied working tree, minus allowlisted values, into $WORK/hits.
# Returns 0 when the search ran (with or without matches), 1 on an execution error.
fallback_tree() {
  local status
  pattern_file
  fallback_allowlist > "$WORK/allow"
  (cd "$WORK/tree" && grep -r -I -n -o -i -E -f "$WORK/patterns" --exclude=.gitleaks.toml . > "$WORK/raw" 2> "$WORK/grep.err")
  status=$?
  if [ "$status" -gt 1 ] || [ -s "$WORK/grep.err" ]; then
    cat "$WORK/grep.err"
    note "the fallback search over the working tree failed (grep exit status $status)"
    return 1
  fi
  sed 's|^\./||' "$WORK/raw" | drop_allowlisted 2 | sort -u > "$WORK/hits"
}

# fallback_history: matches in every revision of every ref, minus allowlisted values, into
# $WORK/hits. Revisions are passed before "--" in chunks; "no match" (exit 1) and an execution
# error (any other exit status, or anything on stderr) are told apart.
fallback_history() {
  local revs=() rev status pattern args=()
  pattern_file
  fallback_allowlist > "$WORK/allow"
  while IFS= read -r pattern; do args+=(-e "$pattern"); done < "$WORK/patterns"
  : > "$WORK/raw"
  git rev-list --all > "$WORK/revs" || { note "git rev-list --all failed"; return 1; }
  flush() {
    [ "${#revs[@]}" -gt 0 ] || return 0
    git grep -I -n -o -i -E "${args[@]}" "${revs[@]}" -- . ':!.gitleaks.toml' >> "$WORK/raw" 2> "$WORK/grep.err"
    status=$?
    revs=()
    if [ "$status" -gt 1 ] || [ -s "$WORK/grep.err" ]; then
      cat "$WORK/grep.err"
      note "the fallback search over the history failed (git grep exit status $status)"
      return 1
    fi
  }
  while IFS= read -r rev; do
    revs+=("$rev")
    if [ "${#revs[@]}" -ge 64 ]; then flush || return 1; fi
  done < "$WORK/revs"
  flush || return 1
  drop_allowlisted 3 < "$WORK/raw" | sort -u > "$WORK/hits"
}

report_hits() {
  # report_hits <scope> <fields>: prints the locations (the first <fields> ":"-separated fields;
  # the match itself is redacted) and returns 1 when there are hits.
  if [ -s "$WORK/hits" ]; then
    sed -E "s/^(([^:]*:){$2}).*/\\1 <match redacted>/" "$WORK/hits"
    note "gitleaks not found; pattern fallback over $1 found $(wc -l < "$WORK/hits" | tr -d ' ') match(es)"
    return 1
  fi
  return 0
}

check_secrets_history() {
  history_readable || return 1
  if have_gitleaks; then
    note "gitleaks $GITLEAKS_VERSION, all refs, $(wc -l < "$WORK/objects" | tr -d ' ') objects readable"
    gitleaks git --config .gitleaks.toml --log-opts="--all" --redact --no-banner --no-color . 2>&1
    return $?
  fi
  if [ "$allow_fallback" = false ]; then
    note "gitleaks not found on PATH (--no-fallback)"
    return 1
  fi
  fallback_history || return 1
  report_hits "the history" 3 || return 1
  note "gitleaks not found; git grep pattern fallback over $(wc -l < "$WORK/revs" | tr -d ' ') revisions found nothing"
  return 11
}

check_secrets_tree() {
  prepare_tree || return 1
  if have_gitleaks; then
    note "gitleaks $GITLEAKS_VERSION, $(cat "$WORK/tree.count") files"
    gitleaks dir --config .gitleaks.toml --redact --no-banner --no-color "$WORK/tree" 2>&1
    return $?
  fi
  if [ "$allow_fallback" = false ]; then
    note "gitleaks not found on PATH (--no-fallback)"
    return 1
  fi
  fallback_tree || return 1
  report_hits "the working tree" 2 || return 1
  note "gitleaks not found; grep pattern fallback over $(cat "$WORK/tree.count") files found nothing"
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
  output="$(uv run --no-project python -I scripts/security/check_sql_concat.py "$ROOT" 2>&1)"
  status=$?
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
  for ecosystem in npm gradle github-actions docker docker-compose; do
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
    printf 'npm, uv, gradle, github-actions, docker and docker-compose are updated weekly\n'
    note "npm, uv, gradle, github-actions, docker, docker-compose weekly"
  else
    note "$file misses an ecosystem or a weekly schedule (see above)"
  fi
  return "$failed"
}

check_actions_pinned() {
  local files=() file bad total
  for file in .github/workflows/*.yml .github/workflows/*.yaml; do
    [ -f "$file" ] && files+=("$file")
  done
  if [ "${#files[@]}" -eq 0 ]; then
    note "no workflow files"
    return 0
  fi
  total="$(grep -hEc '^[[:space:]]*(-[[:space:]]+)?uses:' "${files[@]}" | awk '{ n += $1 } END { print n + 0 }')"
  # A local action (./path) needs no pin; anything else needs <owner>/<repo>@<40 hex> # v<x.y.z>.
  bad="$(grep -nE '^[[:space:]]*(-[[:space:]]+)?uses:' "${files[@]}" |
    grep -vE 'uses:[[:space:]]*\./' |
    grep -vE 'uses:[[:space:]]*[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}[[:space:]]+#[[:space:]]*v[0-9]+\.[0-9]+\.[0-9]+[[:space:]]*$' || true)"
  if [ -n "$bad" ]; then
    printf '%s\n' "$bad" | sed 's/^/not pinned by commit SHA with a version comment: /'
    note "$(printf '%s\n' "$bad" | wc -l | tr -d ' ') of $total action reference(s) not pinned by commit SHA"
    return 1
  fi
  printf '%s action reference(s), each pinned by a full commit SHA\n' "$total"
  note "$total action reference(s) pinned by commit SHA"
}

run_check secrets-history check_secrets_history
run_check secrets-tree check_secrets_tree
run_check env-files check_env_files
run_check sql-concat check_sql_concat
run_check npm-audit check_npm_audit
run_check pip-audit check_pip_audit
run_check dependency-check check_dependency_check
run_check dependabot check_dependabot
run_check actions-pinned check_actions_pinned

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
