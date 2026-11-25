#!/usr/bin/env bash
# Restore of an encrypted backup into a fresh, isolated stack (project specification section 15.6).
#
# The dump is decrypted with the age identity (private key) given on the command line and streamed
# straight into pg_restore; the plaintext dump is never written to disk. It is restored into the
# compose project "vitadelta-restore" (infra/docker-compose.restore.yml): its own database volume
# and an internal network, never the live database. Any previous restore stack is removed first.
# The API then starts against the restored database with outbound e-mail going to a sink Mailpit,
# every scheduler disabled (VITADELTA_JOBS_ENABLED=false) and the FX provider unreachable, using
# fresh random secrets that exist only in the environment of this script's compose commands.
#
# Read-only smoke subset (writes nothing): public health, row counts per table, and one
# POST /recommendations/compare of GOLD-A vs GOLD-B in TR. Nothing registers users, imports data or
# triggers alerts. The restore stack keeps running for inspection afterwards.
#
# Usage: scripts/restore.sh --identity <age identity file> <backups/vitadelta-<timestamp>.dump.age>
#        scripts/restore.sh --down        removes the restore stack and its volume
# Exit status: 0 restored and smoke green, 1 failure, 2 usage error.
# Requires bash (Git Bash or WSL on Windows), docker and age.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PROJECT="vitadelta-restore"
RESTORE_COMPOSE_FILE="infra/docker-compose.restore.yml"
LIVE_COMPOSE_FILE="infra/docker-compose.yml"

# Compose settings inherited from the caller's environment never apply here: every compose command
# below names the restore project and file explicitly, and these variables are cleared so that
# neither a project name, a file list, profiles nor env files of the live stack can leak in.
unset COMPOSE_PROJECT_NAME COMPOSE_FILE COMPOSE_PROFILES COMPOSE_ENV_FILES COMPOSE_PATH_SEPARATOR

log() { printf '[restore] %s\n' "$*"; }
fail() { printf '[restore] ERROR: %s\n' "$*" >&2; exit 1; }
usage() {
  printf 'Usage: %s --identity <age identity file> <dump.age>\n' "$(basename "$0")"
  printf '       %s --down\n' "$(basename "$0")"
}

identity=""
dump=""
down=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --identity) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; identity="$2"; shift 2 ;;
    --down) down=true; shift ;;
    --help | -h) usage; exit 0 ;;
    -*) usage >&2; exit 2 ;;
    *) [ -z "$dump" ] || { usage >&2; exit 2; }; dump="$1"; shift ;;
  esac
done

command -v docker > /dev/null 2>&1 || fail "'docker' is required but was not found on PATH"

compose() { docker compose --project-name "$PROJECT" --file "$RESTORE_COMPOSE_FILE" "$@"; }

random_hex() { od -An -N "$1" -tx1 /dev/urandom | tr -d ' \n'; }

# Fresh per-run values, exported to the compose commands only (the compose file requires them,
# also for "down").
export RESTORE_DB_USERNAME="vitadelta"
RESTORE_DB_PASSWORD="$(random_hex 24)"
RESTORE_JWT_SECRET="$(random_hex 48)"
RESTORE_COOKIE_SECRET="$(random_hex 48)"
export RESTORE_DB_PASSWORD RESTORE_JWT_SECRET RESTORE_COOKIE_SECRET

# Refuses to continue unless compose resolves exactly the restore project, that project is not the
# live one (the name in infra/docker-compose.yml), and no container of that project was created
# from the live compose file.
guard_isolation() {
  local resolved live
  resolved="$(compose config --no-interpolate 2> /dev/null | sed -n 's/^name: //p' | tr -d '\r"')"
  live="$(sed -n 's/^name: //p' "$LIVE_COMPOSE_FILE" | tr -d '\r"')"
  [ -n "$resolved" ] || fail "could not resolve the compose project of $RESTORE_COMPOSE_FILE"
  [ "$resolved" = "$PROJECT" ] || fail "compose resolved project '$resolved' instead of '$PROJECT'; refusing to run"
  [ "$resolved" != "$live" ] || fail "the restore project equals the live project '$live'; refusing to run"
  if docker ps -a --filter "label=com.docker.compose.project=$resolved" \
    --format '{{.Label "com.docker.compose.project.config_files"}}' | grep -Eq 'docker-compose\.yml$'; then
    fail "project '$resolved' has containers created from $LIVE_COMPOSE_FILE; refusing to touch it"
  fi
  log "isolation verified: compose project '$resolved', live project '$live' is not touched"
}
guard_isolation

if [ "$down" = true ]; then
  compose down --volumes --remove-orphans
  log "restore stack and its volume removed"
  exit 0
fi

[ -n "$dump" ] && [ -n "$identity" ] || { usage >&2; exit 2; }
command -v age > /dev/null 2>&1 || fail "'age' is required but was not found on PATH"
[ -f "$dump" ] || fail "dump not found: $dump"
[ -f "$identity" ] || fail "identity file not found: $identity"
[ "$(head -c 21 "$dump")" = "age-encryption.org/v1" ] || fail "$dump is not an age-encrypted file"

psql_restore() {
  compose exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U "$RESTORE_DB_USERNAME" -d vitadelta "$@"
}

log "removing any previous restore stack"
compose down --volumes --remove-orphans > /dev/null 2>&1 || true

log "starting a fresh, isolated database"
compose up -d --wait --wait-timeout 120 postgres

log "decrypting $(basename "$dump") and restoring it (plaintext is streamed, never stored)"
if ! age --decrypt --identity "$identity" "$dump" |
  compose exec -T postgres pg_restore --no-owner --no-acl --exit-on-error --single-transaction \
    -U "$RESTORE_DB_USERNAME" -d vitadelta; then
  fail "decryption or pg_restore failed; the restore stack is left running for inspection ($0 --down removes it)"
fi

log "starting scoring and the API against the restored database (jobs off, e-mail to the sink, no FX)"
if ! compose up -d --wait --wait-timeout 600 api; then
  compose logs --no-color --tail 80 api >&2 || true
  fail "the API did not become healthy on the restored database"
fi

failures=0
report() {
  if [ "$2" = ok ]; then
    printf '  %-34s OK    %s\n' "$1" "$3"
  else
    printf '  %-34s FAIL  %s\n' "$1" "$3"
    failures=$((failures + 1))
  fi
}
api_get() { compose exec -T api curl -sS --max-time 20 "http://127.0.0.1:8080/api/v1$1"; }

log "read-only smoke"
health="$(api_get /health 2>&1 | tr -d '\r')" || true
if printf '%s' "$health" | grep -Eq '^\{"status":"(UP|DEGRADED)"\}$'; then
  report "GET /api/v1/health" ok "$health"
else
  report "GET /api/v1/health" fail "$health"
fi

printf '\n  row counts per table (restored database):\n'
counts="$(psql_restore -A -t -F ' ' -c "
  SELECT table_name,
         (xpath('/row/c/text()',
                query_to_xml(format('SELECT count(*) AS c FROM %I.%I', table_schema, table_name),
                             false, true, '')))[1]::text::bigint
  FROM information_schema.tables
  WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
  ORDER BY table_name" 2>&1 | tr -d '\r')" || counts=""
if [ -z "$counts" ]; then
  report "row counts" fail "the count query failed"
else
  printf '%s\n' "$counts" | awk '{ printf "    %-28s %s\n", $1, $2 }'
  printf '\n'
  for table in flyway_schema_history country nutrient product offer catalog_version; do
    value="$(printf '%s\n' "$counts" | awk -v t="$table" '$1 == t { print $2 }')"
    if [ -n "$value" ] && [ "$value" -gt 0 ]; then
      report "rows in $table" ok "$value"
    else
      report "rows in $table" fail "${value:-table missing}"
    fi
  done
fi

ids="$(psql_restore -A -t -c "SELECT string_agg(id::text, ' ' ORDER BY code) FROM product WHERE code IN ('GOLD-A', 'GOLD-B')" 2>&1 | tr -d '\r')" || ids=""
read -r gold_a gold_b _ <<< "$ids"
if [ -n "${gold_a:-}" ] && [ -n "${gold_b:-}" ]; then
  body="{\"country\":\"TR\",\"baselineProductId\":\"$gold_a\",\"candidateProductId\":\"$gold_b\"}"
  response="$(compose exec -T api curl -sS --max-time 30 -w '\n%{http_code}' \
    -X POST -H 'Content-Type: application/json' -d "$body" \
    http://127.0.0.1:8080/api/v1/recommendations/compare 2>&1 | tr -d '\r')" || true
  compare="$(printf '%s\n' "$response" | tail -n 1)"
  verdict="$(printf '%s\n' "$response" | grep -o '"verdict":"[A-Z_]*"' | head -n 1)" || true
  if [ "$compare" = "200" ] && [ -n "$verdict" ]; then
    report "POST /recommendations/compare" ok "GOLD-A vs GOLD-B in TR -> 200 $verdict"
  else
    report "POST /recommendations/compare" fail "GOLD-A vs GOLD-B in TR -> HTTP ${compare:-none}"
  fi
else
  report "POST /recommendations/compare" fail "GOLD-A and GOLD-B not found in the restored catalog"
fi

if [ "$failures" -ne 0 ]; then
  fail "$failures smoke check(s) failed; the restore stack is left running ($0 --down removes it)"
fi
log "restore verified; the isolated stack keeps running for inspection"
log "inspect: docker compose --project-name $PROJECT --file $RESTORE_COMPOSE_FILE exec api curl -s http://127.0.0.1:8080/api/v1/health"
log "remove:  $0 --down"
