#!/usr/bin/env bash
# Collects the part of a Playwright output directory that may leave the CI runner: the JUnit
# reports and the failure screenshots. Nothing else is copied: no traces (request and response
# bodies, cookies, typed input), no page snapshots (error-context.md), no videos, no HTML report
# (it embeds the attachments) and no network data.
#
# Every JUnit report is scrubbed before it is copied: each secret value of the env file
# (VITADELTA_*PASSWORD*, VITADELTA_*SECRET*) is replaced literally, and token-shaped strings
# (JWTs, bearer credentials, vd_refresh / vd_anon cookie values, "accessToken", "refreshToken",
# "password" and "token" JSON fields, #token= fragments) are masked. A report that still contains
# a secret value after scrubbing is dropped.
#
# Usage: scripts/ci/collect-e2e-artifacts.sh <playwright output dir> <destination dir> [env file]
# Exit status: 0 collected (also when there is nothing to collect), 1 error, 2 usage error.
set -euo pipefail

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  printf 'Usage: %s <playwright output dir> <destination dir> [env file]\n' "$(basename "$0")" >&2
  exit 2
fi
src="$1"
dest="$2"
env_file="${3:-}"

mkdir -p "$dest"
umask 077
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Secret values, one per line (never printed).
: > "$work/secrets"
if [ -n "$env_file" ] && [ -f "$env_file" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in VITADELTA_*=*) ;; *) continue ;; esac
    name="${line%%=*}"
    value="${line#*=}"
    case "$name" in *PASSWORD* | *SECRET*) ;; *) continue ;; esac
    [ -n "$value" ] && printf '%s\n' "$value" >> "$work/secrets"
  done < "$env_file"
fi

scrub() {
  # scrub <in> <out>: literal replacement of every secret value, then the token patterns.
  awk -v secrets="$work/secrets" '
    BEGIN { n = 0; while ((getline s < secrets) > 0) if (s != "") list[++n] = s }
    {
      line = $0
      for (i = 1; i <= n; i++) {
        out = ""
        while ((p = index(line, list[i])) > 0) {
          out = out substr(line, 1, p - 1) "[REDACTED]"
          line = substr(line, p + length(list[i]))
        }
        line = out line
      }
      print line
    }' "$1" |
    sed -E \
      -e 's/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*/[REDACTED-JWT]/g' \
      -e 's/([Bb]earer)[[:space:]]+[A-Za-z0-9._~+\/=-]+/\1 [REDACTED]/g' \
      -e 's/(vd_refresh|vd_anon)=[^;"&[:space:]]+/\1=[REDACTED]/g' \
      -e 's/((&quot;|")(accessToken|refreshToken|password|currentPassword|newPassword|token)(&quot;|")[[:space:]]*:[[:space:]]*(&quot;|"))[^"&]*/\1[REDACTED]/g' \
      -e 's/(#token=)[A-Za-z0-9_-]+/\1[REDACTED]/g' > "$2"
}

contains_secret() {
  [ -s "$work/secrets" ] && grep -q -F -f "$work/secrets" "$1"
}

copied=0
dropped=0
if [ -d "$src" ]; then
  # JUnit reports written by the CI run (PLAYWRIGHT_JUNIT_OUTPUT_FILE).
  for report in "$src"/junit-*.xml; do
    [ -f "$report" ] || continue
    scrub "$report" "$work/report.xml"
    if contains_secret "$work/report.xml"; then
      printf 'dropped %s: a secret value survived scrubbing\n' "$(basename "$report")" >&2
      dropped=$((dropped + 1))
      continue
    fi
    cp "$work/report.xml" "$dest/$(basename "$report")"
    copied=$((copied + 1))
  done
  # Failure screenshots (test-failed-<n>.png) under the per-test result directories.
  while IFS= read -r -d '' shot; do
    relative="${shot#"$src"/}"
    mkdir -p "$dest/screenshots/$(dirname "$relative")"
    cp "$shot" "$dest/screenshots/$relative"
    copied=$((copied + 1))
  done < <(find "$src" -type f -name 'test-failed-*.png' -print0)
fi
printf 'collected %d file(s) into %s (%d report(s) dropped)\n' "$copied" "$dest" "$dropped"
