#!/usr/bin/env bash
# Loads the reference data (data/reference, including the form registry forms.csv and the citation
# notes of upper_limits.csv), the synthetic fixtures (data/fixtures) and the real label snapshot
# (data/real) into the database of the local stack.
#
# The api image runs once with the Spring profile "seed" on the compose network; the repository's
# data directory is mounted read-only at /data (see infra/docker-compose.yml). The seed validates
# every file first and writes nothing when any row is invalid (exit code 2, row-level messages in
# the output). It is idempotent: rows are upserted by their business keys, so running it again
# keeps every id. A run bumps catalog_version once when it changed a catalog or reference row and
# not at all otherwise; changed offers emit OFFERS_CHANGED outbox events like an admin import, an
# unchanged run emits none.
#
# Requires bash (Git Bash or WSL on Windows), docker and the root .env created by scripts/dev-up.sh.
# Usage: scripts/seed.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENV_FILE=".env"
COMPOSE_FILE="infra/docker-compose.yml"

log() { printf '[seed] %s\n' "$*"; }
fail() { printf '[seed] ERROR: %s\n' "$*" >&2; exit 1; }

usage() { printf 'Usage: %s\n' "$(basename "$0")"; }
for arg in "$@"; do
  case "$arg" in
    --help | -h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

command -v docker >/dev/null 2>&1 || fail "'docker' is required but was not found on PATH"
docker info >/dev/null 2>&1 || fail "docker daemon is not reachable; start Docker and retry"
[ -f "$ENV_FILE" ] || fail "$ENV_FILE not found; run scripts/dev-up.sh first"
[ -d data/reference ] || fail "data/reference not found"

compose() { docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"; }

log "waiting for postgres"
compose up -d --wait --wait-timeout 120 postgres >/dev/null

log "building the api image and running the seed profile"
set +e
compose run --rm --no-deps --build -T \
  -e SPRING_PROFILES_ACTIVE=seed \
  api
status=$?
set -e

case "$status" in
  0) log "done" ;;
  2) printf '[seed] ERROR: seed data is invalid; nothing was written (see the messages above)\n' >&2 ;;
  *) printf '[seed] ERROR: seed run failed with exit code %s\n' "$status" >&2 ;;
esac
exit "$status"
