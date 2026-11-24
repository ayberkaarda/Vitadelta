#!/usr/bin/env bash
# Stops the local stack started by scripts/dev-up.sh.
# Usage: scripts/dev-down.sh [--volumes]
#   --volumes  also removes the PostgreSQL data volume (all local data is lost).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() { printf 'Usage: %s [--volumes]\n' "$(basename "$0")"; }

remove_volumes=false
for arg in "$@"; do
  case "$arg" in
    --volumes | -v) remove_volumes=true ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

command -v docker >/dev/null 2>&1 || { printf '[dev-down] ERROR: docker not found on PATH\n' >&2; exit 1; }

args=(down --remove-orphans)
if [ "$remove_volumes" = true ]; then
  args+=(--volumes)
fi

if [ -f .env ]; then
  docker compose --env-file .env -f infra/docker-compose.yml "${args[@]}"
else
  # Without .env the compose file cannot be interpolated; address the project by name.
  docker compose --project-name vitadelta "${args[@]}"
fi

if [ "$remove_volumes" = true ]; then
  printf '[dev-down] stack stopped and data volume removed\n'
else
  printf '[dev-down] stack stopped (data volume kept; use --volumes to remove it)\n'
fi
