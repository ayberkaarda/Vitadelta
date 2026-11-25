#!/usr/bin/env bash
# Registers every secret value of an env file with the GitHub Actions log masker, so that no later
# step of the job can print it: passwords, signing and cookie secrets (VITADELTA_*PASSWORD*,
# VITADELTA_*SECRET*). The values themselves are never printed; the workflow runner hides the
# ::add-mask:: commands from the log.
#
# Usage: scripts/ci/mask-env-secrets.sh <env file>
# Exit status: 0 done (also when there is nothing to mask), 1 file missing, 2 usage error.
set -euo pipefail

[ "$#" -eq 1 ] || { printf 'Usage: %s <env file>\n' "$(basename "$0")" >&2; exit 2; }
[ -f "$1" ] || { printf 'mask-env-secrets: %s not found\n' "$1" >&2; exit 1; }

count=0
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  case "$line" in
    VITADELTA_*=*) ;;
    *) continue ;;
  esac
  name="${line%%=*}"
  value="${line#*=}"
  case "$name" in
    *PASSWORD* | *SECRET*) ;;
    *) continue ;;
  esac
  [ -n "$value" ] || continue
  printf '::add-mask::%s\n' "$value"
  count=$((count + 1))
done < "$1"
printf 'masked %d secret value(s) of %s\n' "$count" "$(basename "$1")"
