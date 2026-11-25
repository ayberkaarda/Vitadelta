#!/usr/bin/env bash
# Self-tests of scripts/security-scan.sh, run in throwaway Git repositories (never in this one).
#
# Each case builds a small repository with a copy of the scan script, its SQL checker, the
# .gitleaks.toml allowlist and the Dependabot file, plants one problem and checks the row of the
# summary table that must report it:
#   tree-secret        a token in an untracked, not ignored file        -> secrets-tree FAIL
#   history-secret     a token committed and removed again              -> secrets-history FAIL
#   hyphen-password    a hyphen-joined real-looking password assignment -> secrets-tree FAIL
#   allowlist-exact    an allowlisted synthetic value, matched exactly  -> not reported
#   allowlist-substr   an allowlisted value inside a longer value       -> secrets-tree FAIL
#   unreadable-file    a secret in a file the scan cannot read          -> secrets-tree FAIL
#   missing-object     a history object that cannot be read             -> secrets-history FAIL
#   sql-concat         SQL built from strings in services/api/src/main  -> sql-concat FAIL, and the
#                      checks after it still run and are reported
# Secret-scan cases run twice: with gitleaks (skipped when it is not installed) and with the git
# grep fallback (gitleaks removed from PATH). The planted values are generated at run time, so this
# file contains none of them.
#
# Usage: scripts/security/selftest-security-scan.sh [scan script to test]
# Exit status: 0 every case passed, 1 a case failed, 2 usage error.
# Requires bash (Git Bash or WSL on Windows), git and uv.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCAN="${1:-$ROOT/scripts/security-scan.sh}"
[ "$#" -le 1 ] || { printf 'Usage: %s [scan script]\n' "$(basename "$0")" >&2; exit 2; }
[ -f "$SCAN" ] || { printf 'selftest: %s not found\n' "$SCAN" >&2; exit 2; }
command -v git > /dev/null 2>&1 || { printf 'selftest: git is required\n' >&2; exit 1; }
command -v uv > /dev/null 2>&1 || { printf 'selftest: uv is required\n' >&2; exit 1; }

WORK="$(mktemp -d)"
LOCKED=()
cleanup() {
  local file
  for file in "${LOCKED[@]+"${LOCKED[@]}"}"; do unlock_file "$file"; done
  rm -rf "$WORK"
}
trap cleanup EXIT

# PATH without any directory that holds a gitleaks executable (fallback mode).
NO_GITLEAKS_PATH=""
IFS=':' read -r -a path_entries <<< "$PATH"
for entry in "${path_entries[@]}"; do
  if [ -x "$entry/gitleaks" ] || [ -x "$entry/gitleaks.exe" ]; then continue; fi
  NO_GITLEAKS_PATH="${NO_GITLEAKS_PATH:+$NO_GITLEAKS_PATH:}$entry"
done
if command -v gitleaks > /dev/null 2>&1; then HAVE_GITLEAKS=true; else HAVE_GITLEAKS=false; fi

hex() { od -An -N "$1" -tx1 /dev/urandom | tr -d ' \n'; }
# A value shaped like a GitHub personal access token; assembled so this file never contains one.
token() { printf '%s%s' "gh" "p_$(hex 18)"; }
# Assignment of a password-like key, assembled the same way.
assign() { printf '%s%s = "%s"\n' "pass" "word" "$1"; }

lock_file() {
  # Makes a file unreadable for the current user (POSIX mode bits, or a Windows deny entry).
  if command -v icacls > /dev/null 2>&1 && command -v cygpath > /dev/null 2>&1; then
    MSYS_NO_PATHCONV=1 icacls "$(cygpath -w "$1")" /deny "${USERNAME:-$(id -un)}:(R)" > /dev/null
  else
    chmod 000 "$1"
  fi
  LOCKED+=("$1")
}
unlock_file() {
  if command -v icacls > /dev/null 2>&1 && command -v cygpath > /dev/null 2>&1; then
    MSYS_NO_PATHCONV=1 icacls "$(cygpath -w "$1")" /reset > /dev/null 2>&1 || true
  else
    chmod 600 "$1" 2> /dev/null || true
  fi
}

new_repo() {
  # new_repo <name>: a repository with the scan files committed; prints its path.
  local repo="$WORK/$1"
  mkdir -p "$repo/scripts/security" "$repo/.github"
  cp "$SCAN" "$repo/scripts/security-scan.sh"
  cp "$ROOT/scripts/security/check_sql_concat.py" "$repo/scripts/security/"
  cp "$ROOT/.gitleaks.toml" "$repo/.gitleaks.toml"
  cp "$ROOT/.github/dependabot.yml" "$repo/.github/dependabot.yml"
  printf '.env\nbackups/\n' > "$repo/.gitignore"
  printf 'readme of a throwaway repository\n' > "$repo/README.txt"
  git -C "$repo" init -q
  git -C "$repo" config user.email selftest@example.invalid
  git -C "$repo" config user.name selftest
  git -C "$repo" config core.autocrlf false
  git -C "$repo" add -A
  git -C "$repo" commit -q -m initial
  printf '%s' "$repo"
}
commit_all() { git -C "$1" add -A && git -C "$1" commit -q -m "$2"; }

scan() {
  # scan <repo> <gitleaks|fallback>: runs the scan, stores its output in <repo>.<mode>.out.
  local repo="$1" mode="$2" path="$PATH" status=0
  [ "$mode" = fallback ] && path="$NO_GITLEAKS_PATH"
  (cd "$repo" && PATH="$path" NVD_API_KEY="" bash scripts/security-scan.sh) > "$repo.$mode.out" 2>&1 || status=$?
  printf '%s\n' "$status" > "$repo.$mode.status"
}
row() {
  # row <repo> <mode> <check>: the RESULT column of that check in the summary, or "missing".
  local value
  value="$(awk -v c="$3" 'f && $1 == c { print $2; exit } /^==== security summary ====/ { f = 1 }' "$1.$2.out")"
  printf '%s' "${value:-missing}"
}

passed=0
failed=0
skipped=0
expect() {
  # expect <case> <repo> <mode> <check> <regex of the expected RESULT>
  local got
  got="$(row "$2" "$3" "$4")"
  if [[ "$got" =~ ^($5)$ ]]; then
    printf 'ok    %-17s %-8s %-16s %s\n' "$1" "$3" "$4" "$got"
    passed=$((passed + 1))
  else
    printf 'FAIL  %-17s %-8s %-16s got %s, expected %s (exit %s)\n' "$1" "$3" "$4" "$got" "$5" "$(cat "$2.$3.status")"
    sed 's/^/        | /' "$2.$3.out" | tail -n 25
    failed=$((failed + 1))
  fi
}
modes() {
  if [ "$HAVE_GITLEAKS" = true ]; then printf 'gitleaks fallback'; else printf 'fallback'; fi
}
[ "$HAVE_GITLEAKS" = true ] || { printf 'skip  gitleaks mode (gitleaks not on PATH)\n'; skipped=$((skipped + 1)); }

# tree-secret
repo="$(new_repo tree-secret)"
printf 'github_token=%s\n' "$(token)" > "$repo/local-notes.txt"
for mode in $(modes); do
  scan "$repo" "$mode"
  expect tree-secret "$repo" "$mode" secrets-tree FAIL
  expect tree-secret "$repo" "$mode" secrets-history "PASS|DEGRADED"
done

# history-secret
repo="$(new_repo history-secret)"
mkdir -p "$repo/config"
printf 'github_token=%s\n' "$(token)" > "$repo/config/app.properties"
commit_all "$repo" "add configuration"
git -C "$repo" rm -q config/app.properties
commit_all "$repo" "remove configuration"
for mode in $(modes); do
  scan "$repo" "$mode"
  expect history-secret "$repo" "$mode" secrets-history FAIL
  expect history-secret "$repo" "$mode" secrets-tree "PASS|DEGRADED"
done

# hyphen-password (the fallback must not drop hyphen-joined values)
repo="$(new_repo hyphen-password)"
assign "orchard-river-$(hex 4 | tr "0-9" "g-p")-92" > "$repo/settings.py"
scan "$repo" fallback
expect hyphen-password "$repo" fallback secrets-tree FAIL

# allowlist-exact: a reviewed synthetic value of .gitleaks.toml is not reported
allowed="$(sed -n "s/^[[:space:]]*'''^\([A-Za-z-]*[0-9]*\)\$''',[[:space:]]*\$/\1/p" "$ROOT/.gitleaks.toml" | head -n 1)"
if [ -z "$allowed" ]; then
  printf 'FAIL  allowlist-exact: no plain allowlisted value found in .gitleaks.toml\n'
  failed=$((failed + 1))
else
  repo="$(new_repo allowlist-exact)"
  assign "$allowed" > "$repo/fixture.ts"
  commit_all "$repo" "add fixture"
  scan "$repo" fallback
  expect allowlist-exact "$repo" fallback secrets-tree DEGRADED
  expect allowlist-exact "$repo" fallback secrets-history DEGRADED

  # allowlist-substr: the same value inside a longer one is a different value and is reported
  repo="$(new_repo allowlist-substr)"
  assign "${allowed}-$(hex 4)" > "$repo/fixture.ts"
  scan "$repo" fallback
  expect allowlist-substr "$repo" fallback secrets-tree FAIL
fi

# unreadable-file
repo="$(new_repo unreadable-file)"
printf 'github_token=%s\n' "$(token)" > "$repo/private-notes.txt"
lock_file "$repo/private-notes.txt"
if cat "$repo/private-notes.txt" > /dev/null 2>&1; then
  printf 'skip  unreadable-file (the file stays readable for this user, for example as root)\n'
  skipped=$((skipped + 1))
else
  for mode in $(modes); do
    scan "$repo" "$mode"
    expect unreadable-file "$repo" "$mode" secrets-tree FAIL
  done
fi

# missing-object: an unreadable history object is an execution error, never "no match"
repo="$(new_repo missing-object)"
printf 'first version %s\n' "$(hex 8)" > "$repo/notes.txt"
commit_all "$repo" "add notes"
blob="$(git -C "$repo" rev-parse HEAD:notes.txt)"
printf 'second version %s\n' "$(hex 8)" > "$repo/notes.txt"
commit_all "$repo" "change notes"
object="$repo/.git/objects/${blob:0:2}/${blob:2}"
chmod u+w "$object" 2> /dev/null || true
rm -f "$object"
for mode in $(modes); do
  scan "$repo" "$mode"
  expect missing-object "$repo" "$mode" secrets-history FAIL
done

# sql-concat: a failing SQL check is recorded and the remaining checks still run
repo="$(new_repo sql-concat)"
mkdir -p "$repo/services/api/src/main/java/com/example"
cat > "$repo/services/api/src/main/java/com/example/Lookup.java" << 'EOF'
package com.example;

class Lookup {
  int remove(org.springframework.jdbc.core.simple.JdbcClient jdbc, String id) {
    return jdbc.sql("DELETE FROM item WHERE id = '" + id + "'").update();
  }
}
EOF
commit_all "$repo" "add lookup"
scan "$repo" fallback
expect sql-concat "$repo" fallback sql-concat FAIL
expect sql-concat "$repo" fallback dependabot "PASS|FAIL"

printf '\n%d passed, %d failed, %d skipped\n' "$passed" "$failed" "$skipped"
[ "$failed" -eq 0 ]
