#!/usr/bin/env bash
# Brings up the local stack (postgres, mailpit, api, scoring, web) and verifies it.
# On first run it creates the root .env from .env.example with development values
# and freshly generated secrets. Requires bash (Git Bash or WSL on Windows), docker and curl.
# Usage: scripts/dev-up.sh [--bootstrap-admin]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENV_FILE=".env"
ENV_EXAMPLE=".env.example"
COMPOSE_FILE="infra/docker-compose.yml"
WEB_URL="http://localhost:8080"
MAILPIT_URL="http://127.0.0.1:8025"

log() { printf '[dev-up] %s\n' "$*"; }
fail() { printf '[dev-up] ERROR: %s\n' "$*" >&2; exit 1; }

require() {
  command -v "$1" >/dev/null 2>&1 || fail "'$1' is required but was not found on PATH"
}

# Prints <bytes> random bytes as lowercase hex.
random_hex() {
  local bytes="$1"
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$bytes"
  elif [ -r /dev/urandom ]; then
    od -An -N "$bytes" -tx1 /dev/urandom | tr -d ' \n'
    printf '\n'
  else
    fail "cannot generate secrets: neither openssl nor /dev/urandom is available"
  fi
}

# Development value for a variable of .env.example; empty output leaves it unset.
dev_value() {
  case "$1" in
    VITADELTA_DB_URL) printf '%s' "jdbc:postgresql://postgres:5432/vitadelta" ;;
    VITADELTA_DB_USERNAME) printf '%s' "vitadelta" ;;
    VITADELTA_DB_PASSWORD) random_hex 24 ;;
    VITADELTA_JWT_SECRET) random_hex 48 ;;
    VITADELTA_COOKIE_SECRET) random_hex 48 ;;
    VITADELTA_WEB_ORIGINS) printf '%s' "$WEB_URL" ;;
    VITADELTA_WEB_BASE_URL) printf '%s' "$WEB_URL" ;;
    VITADELTA_TRUST_PROXY) printf '%s' "true" ;;
    VITADELTA_TRUSTED_PROXIES) printf '%s' "172.30.80.0/24" ;;
    VITADELTA_UNVERIFIED_ACCOUNT_TTL_DAYS) printf '%s' "30" ;;
    VITADELTA_JOBS_ENABLED) printf '%s' "true" ;;
    VITADELTA_PRIVACY_CONTACT) printf '%s' "privacy@vitadelta.localhost" ;;
    VITADELTA_SCORING_BASE_URL) printf '%s' "http://scoring:8000" ;;
    VITADELTA_SMTP_HOST) printf '%s' "mailpit" ;;
    VITADELTA_SMTP_PORT) printf '%s' "1025" ;;
    VITADELTA_SMTP_STARTTLS) printf '%s' "false" ;;
    VITADELTA_MAIL_FROM) printf '%s' "no-reply@vitadelta.localhost" ;;
    VITADELTA_FX_BASE_URL) printf '%s' "https://api.frankfurter.dev/v2" ;;
    VITADELTA_ANON_QUERY_LIMIT) printf '%s' "3" ;;
    VITADELTA_ANON_VISIBLE_RESULTS) printf '%s' "3" ;;
    VITADELTA_TLS) printf '%s' "false" ;;
    *) printf '%s' "" ;;
  esac
}

create_env() {
  [ -f "$ENV_EXAMPLE" ] || fail "$ENV_EXAMPLE not found"
  log "creating $ENV_FILE from $ENV_EXAMPLE with development values and new secrets"
  local tmp="$ENV_FILE.tmp.$$"
  local line name value
  (
    umask 077
    : > "$tmp"
    while IFS= read -r line || [ -n "$line" ]; do
      line="${line%$'\r'}"
      if [[ "$line" =~ ^(VITADELTA_[A-Z0-9_]+)=$ ]]; then
        name="${BASH_REMATCH[1]}"
        value="$(dev_value "$name" | tr -d '\r\n')"
        printf '%s=%s\n' "$name" "$value" >> "$tmp"
      else
        printf '%s\n' "$line" >> "$tmp"
      fi
    done < "$ENV_EXAMPLE"
  )
  mv "$tmp" "$ENV_FILE"
}

# Gives the local bootstrap administrator an address and a fresh random password in .env when
# either is empty; values that are already set are kept. The API creates the account at its next
# start if it does not exist yet (the end-to-end suites sign in with it).
ensure_bootstrap_admin() {
  local email password tmp line
  email="$(sed -n 's/^VITADELTA_BOOTSTRAP_ADMIN_EMAIL=//p' "$ENV_FILE" | tr -d '\r' | tail -n 1)"
  password="$(sed -n 's/^VITADELTA_BOOTSTRAP_ADMIN_PASSWORD=//p' "$ENV_FILE" | tr -d '\r' | tail -n 1)"
  if [ -n "$email" ] && [ -n "$password" ]; then
    log "bootstrap administrator already configured in $ENV_FILE"
    return 0
  fi
  [ -n "$email" ] || email="admin@vitadelta.localhost"
  [ -n "$password" ] || password="$(random_hex 24 | tr -d '\r\n')"
  tmp="$ENV_FILE.tmp.$$"
  (
    umask 077
    : > "$tmp"
    while IFS= read -r line || [ -n "$line" ]; do
      line="${line%$'\r'}"
      case "$line" in
        VITADELTA_BOOTSTRAP_ADMIN_EMAIL=*) printf 'VITADELTA_BOOTSTRAP_ADMIN_EMAIL=%s\n' "$email" ;;
        VITADELTA_BOOTSTRAP_ADMIN_PASSWORD=*) printf 'VITADELTA_BOOTSTRAP_ADMIN_PASSWORD=%s\n' "$password" ;;
        *) printf '%s\n' "$line" ;;
      esac
    done < "$ENV_FILE" > "$tmp"
    grep -q '^VITADELTA_BOOTSTRAP_ADMIN_EMAIL=' "$tmp" ||
      printf 'VITADELTA_BOOTSTRAP_ADMIN_EMAIL=%s\n' "$email" >> "$tmp"
    grep -q '^VITADELTA_BOOTSTRAP_ADMIN_PASSWORD=' "$tmp" ||
      printf 'VITADELTA_BOOTSTRAP_ADMIN_PASSWORD=%s\n' "$password" >> "$tmp"
  )
  mv "$tmp" "$ENV_FILE"
  log "bootstrap administrator $email configured in $ENV_FILE (the password is stored there only)"
}

usage() {
  printf 'Usage: %s [--bootstrap-admin]\n' "$(basename "$0")"
  printf '  --bootstrap-admin  fill an empty bootstrap administrator in .env with a local address\n'
  printf '                     and a generated password (needed by the end-to-end suites)\n'
}

bootstrap_admin=false
for arg in "$@"; do
  case "$arg" in
    --bootstrap-admin) bootstrap_admin=true ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

require docker
require curl
docker info >/dev/null 2>&1 || fail "docker daemon is not reachable; start Docker and retry"

if [ ! -f "$ENV_FILE" ]; then
  create_env
else
  log "using existing $ENV_FILE"
fi
if [ "$bootstrap_admin" = true ]; then
  ensure_bootstrap_admin
fi

compose() { docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"; }

log "building and starting the stack (waits until every service is healthy)"
if ! compose up -d --build --wait --wait-timeout 600; then
  compose ps -a || true
  fail "stack did not become healthy; inspect with: docker compose --env-file .env -f $COMPOSE_FILE logs"
fi

failures=0
report() {
  # report <name> <ok|fail> <detail>
  if [ "$2" = "ok" ]; then
    printf '  %-28s OK    %s\n' "$1" "$3"
  else
    printf '  %-28s FAIL  %s\n' "$1" "$3"
    failures=$((failures + 1))
  fi
}

log "verifying"
api_body="$(curl -fsS --max-time 10 "$WEB_URL/api/v1/health" 2>&1)" || api_body="request failed: $api_body"
if printf '%s' "$api_body" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"(UP|DEGRADED)"'; then
  report "api via nginx" ok "$WEB_URL/api/v1/health -> $api_body"
else
  report "api via nginx" fail "$WEB_URL/api/v1/health -> $api_body"
fi

scoring_body="$(compose exec -T scoring python -c \
  "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8000/internal/v1/health', timeout=5).read().decode())" \
  2>&1)" || scoring_body="request failed: $scoring_body"
scoring_body="$(printf '%s' "$scoring_body" | tr -d '\r')"
if printf '%s' "$scoring_body" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"(UP|DEGRADED)"'; then
  report "scoring (inside container)" ok "/internal/v1/health -> $scoring_body"
else
  report "scoring (inside container)" fail "/internal/v1/health -> $scoring_body"
fi

web_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$WEB_URL/" 2>/dev/null)" || web_code="000"
web_page="$(curl -fsS --max-time 10 "$WEB_URL/" 2>/dev/null)" || web_page=""
if [ "$web_code" = "200" ] && printf '%s' "$web_page" | grep -qi '<html'; then
  report "web root" ok "$WEB_URL/ -> HTTP $web_code"
else
  report "web root" fail "$WEB_URL/ -> HTTP $web_code"
fi

mail_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$MAILPIT_URL/" 2>/dev/null)" || mail_code="000"
if [ "$mail_code" = "200" ]; then
  report "mailpit ui" ok "$MAILPIT_URL/ -> HTTP $mail_code"
else
  report "mailpit ui" fail "$MAILPIT_URL/ -> HTTP $mail_code"
fi

if [ "$failures" -ne 0 ]; then
  fail "$failures check(s) failed"
fi
log "stack is up: web $WEB_URL, mailpit $MAILPIT_URL"
