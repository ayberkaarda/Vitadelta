#!/usr/bin/env bash
# Encrypted database backup (project specification section 15.6).
#
# Dumps the "vitadelta" database of the compose PostgreSQL container with pg_dump -Fc, encrypts
# the stream with age to the public key in VITADELTA_BACKUP_RECIPIENT and writes
# backups/vitadelta-<UTC timestamp>.dump.age with file mode 0600. The dump never touches the disk
# unencrypted, and the private key is never needed on this host. Without age the script refuses to
# run: an unencrypted backup is never written.
#
# Retention: after a successful backup, dumps beyond the newest 14 and dumps older than 14 days
# are deleted (the newest dump is always kept). The 14-day window is also the period during which
# a deleted account can still exist in a backup (docs/runbook.md, privacy notice).
#
# What a backup contains: the whole application database (catalog, reference data, accounts with
# password hashes, sessions, saved products, alerts, notifications, outbox, FX rates, promo codes).
# What it does not contain: .env and every other secret, the Mailpit store, the in-memory rate
# limits and caches, and the repository's data/ files (they are in Git).
#
# VITADELTA_BACKUP_RECIPIENT is read from the environment, else from the env file. Scheduling is
# done by the host (see docs/runbook.md for the daily cron line).
#
# Usage: scripts/backup.sh [--env-file <path>] [--dir <directory>]
#   --env-file  env file of the stack (default .env)
#   --dir       backup directory (default backups/ in the repository root)
# Requires bash (Git Bash or WSL on Windows), docker and age.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

COMPOSE_FILE="infra/docker-compose.yml"
KEEP_COUNT=14
KEEP_DAYS=14

log() { printf '[backup] %s\n' "$*"; }
fail() { printf '[backup] ERROR: %s\n' "$*" >&2; exit 1; }
usage() { printf 'Usage: %s [--env-file <path>] [--dir <directory>]\n' "$(basename "$0")"; }

env_file=".env"
backup_dir="backups"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; env_file="$2"; shift 2 ;;
    --dir) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; backup_dir="$2"; shift 2 ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

env_value() {
  # env_value <name>: value from the environment, else from the env file.
  local from_env="${!1:-}"
  if [ -n "$from_env" ]; then
    printf '%s' "$from_env"
  elif [ -f "$env_file" ]; then
    sed -n "s/^$1=//p" "$env_file" | tr -d '\r' | tail -n 1
  fi
}

command -v docker > /dev/null 2>&1 || fail "'docker' is required but was not found on PATH"
command -v age > /dev/null 2>&1 ||
  fail "'age' is required but was not found on PATH; no backup was written (an unencrypted backup is never written)"
[ -f "$env_file" ] || fail "$env_file not found"

recipient="$(env_value VITADELTA_BACKUP_RECIPIENT)"
[ -n "$recipient" ] || fail "VITADELTA_BACKUP_RECIPIENT is empty; set it to the age public key of the backup owner"
if ! [[ "$recipient" =~ ^age1[0-9a-z]{58}$ || "$recipient" =~ ^ssh-(ed25519|rsa)\ [A-Za-z0-9+/=]+ ]]; then
  fail "VITADELTA_BACKUP_RECIPIENT is not an age public key (age1...) or an ssh-ed25519/ssh-rsa key"
fi
db_user="$(env_value VITADELTA_DB_USERNAME)"
[ -n "$db_user" ] || fail "VITADELTA_DB_USERNAME is empty in $env_file"

compose() { docker compose --env-file "$env_file" -f "$COMPOSE_FILE" "$@"; }

if [ -z "$(compose ps --status running --quiet postgres 2> /dev/null)" ]; then
  fail "the postgres service of the stack is not running (start it with scripts/dev-up.sh)"
fi

mkdir -p "$backup_dir"
chmod 700 "$backup_dir" 2> /dev/null || true
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$backup_dir/vitadelta-$stamp.dump.age"
partial="$target.partial"
[ ! -e "$target" ] || fail "$target already exists"

cleanup() { rm -f "$partial"; }
trap cleanup EXIT

log "dumping database vitadelta and encrypting to the configured recipient"
umask 077
if ! compose exec -T postgres pg_dump -Fc --no-password -U "$db_user" -d vitadelta |
  age --encrypt --recipient "$recipient" --output "$partial"; then
  fail "pg_dump or age failed; no backup was written"
fi

header="$(head -c 21 "$partial" 2> /dev/null || true)"
[ "$header" = "age-encryption.org/v1" ] || fail "the output is not an age file; no backup was written"
size="$(wc -c < "$partial" | tr -d ' ')"
# An encrypted empty dump is a few hundred bytes; a real dump of this schema is far larger.
[ "$size" -gt 4096 ] || fail "the encrypted dump is only $size bytes; no backup was written"

mv "$partial" "$target"
chmod 600 "$target"
trap - EXIT
log "wrote $target ($size bytes, mode 0600)"

# Retention: newest KEEP_COUNT dumps, none older than KEEP_DAYS days; the newest always stays.
now="$(date -u +%s)"
index=0
while IFS= read -r file; do
  index=$((index + 1))
  [ "$index" -eq 1 ] && continue
  name="$(basename "$file")"
  stamp_part="${name#vitadelta-}"
  stamp_part="${stamp_part%.dump.age}"
  if [[ "$stamp_part" =~ ^([0-9]{4})([0-9]{2})([0-9]{2})T([0-9]{2})([0-9]{2})([0-9]{2})Z$ ]]; then
    taken="$(date -u -d "${BASH_REMATCH[1]}-${BASH_REMATCH[2]}-${BASH_REMATCH[3]} ${BASH_REMATCH[4]}:${BASH_REMATCH[5]}:${BASH_REMATCH[6]}" +%s)"
  else
    continue
  fi
  if [ "$index" -gt "$KEEP_COUNT" ] || [ $((now - taken)) -gt $((KEEP_DAYS * 86400)) ]; then
    rm -f "$file"
    log "pruned $name"
  fi
done < <(find "$backup_dir" -maxdepth 1 -type f -name 'vitadelta-*.dump.age' | sort -r)

kept="$(find "$backup_dir" -maxdepth 1 -type f -name 'vitadelta-*.dump.age' | wc -l | tr -d ' ')"
log "done; $kept dump(s) kept in $backup_dir (newest $KEEP_COUNT, at most $KEEP_DAYS days old)"
