#!/usr/bin/env bash
# Routing, caching, header and proxy check of the web nginx configuration, without the compose
# stack.
#
# Starts a throwaway nginx container (same image as the web Dockerfile) with this directory's
# configuration and a copy of an already built web app, next to a small stand-in for the API (an
# nginx that answers like the API does and echoes the forwarding headers it received), then checks:
#   - application routes answer the SPA document (200, text/html);
#   - existing static files and translations are served with their own type;
#   - missing hashed chunks, styles, translations and media answer 404, not the SPA;
#   - fingerprinted files (hashed bundles, chunks, styles, media) are cached for a year
#     as immutable, while index.html, application routes and translations are revalidated
#     and nothing else (404s included) is ever marked immutable;
#   - the security headers of section 15.2 with their exact values on every response class:
#     SPA document, files, 404s, the blocked actuator path, proxied API answers and a 502 when the
#     API is down; Referrer-Policy no-referrer on /auth/verify and /auth/reset; the API's own
#     stricter policy kept on API answers, each header sent once; no version in Server;
#   - HSTS is absent with VITADELTA_TLS=false and present with its exact value on every response
#     class with VITADELTA_TLS=true (a second container);
#   - X-Forwarded-For is overwritten with the peer address, never appended; client-supplied
#     Forwarded and X-Real-IP never reach the API; X-Request-Id is forwarded only when it matches
#     the API pattern;
#   - the access log holds the path without the query string.
#
# Usage: infra/nginx/check-routing.sh [path-to-built-browser-dir]
# Default build path: apps/web/dist/web/browser (run `npm run build` in apps/web first).
# Needs docker and curl. The containers listen on 127.0.0.1:${CHECK_PORT:-18089} and the next port.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
dist="${1:-$repo/apps/web/dist/web/browser}"
port="${CHECK_PORT:-18089}"
tls_port=$((port + 1))
image="nginx:1.30.8-alpine"
name="vitadelta-nginx-check-$$"
tls_name="vitadelta-nginx-check-tls-$$"
api_name="vitadelta-nginx-check-api-$$"
network="vitadelta-nginx-check-$$"
immutable="public, max-age=31536000, immutable"
web_csp="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
api_csp="default-src 'none'; frame-ancestors 'none'"
hsts="max-age=63072000; includeSubDomains"

if [ ! -f "$dist/index.html" ]; then
  echo "No built web app at $dist (run npm run build in apps/web)." >&2
  exit 2
fi

to_docker_path() {
  # Docker Desktop on Windows (Git Bash) needs a Windows-style path for bind mounts.
  if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# A copy of the build plus one fingerprinted media file (the build references none today),
# so the media caching rule is checked as well.
site="$(mktemp -d)"
cleanup() {
  docker rm -f "$name" "$tls_name" "$api_name" > /dev/null 2>&1 || true
  docker network rm "$network" > /dev/null 2>&1 || true
  rm -rf "$site"
}
trap cleanup EXIT
mkdir -p "$site/html" "$site/api"
cp -R "$dist/." "$site/html/"
mkdir -p "$site/html/media"
printf 'wOF2' > "$site/html/media/check-Ab3dE_9z.woff2"
printf 'wOF2' > "$site/html/media/unhashed.woff2"

# Stand-in for the API: answers JSON with the API's own headers and echoes what it received.
cat > "$site/api/default.conf" << 'EOF'
server {
    listen 8080;
    location / {
        default_type application/json;
        add_header Content-Security-Policy "default-src 'none'; frame-ancestors 'none'" always;
        add_header Referrer-Policy "no-referrer" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header X-Echo-Forwarded-For "$http_x_forwarded_for" always;
        add_header X-Echo-Request-Id "$http_x_request_id" always;
        add_header X-Echo-Real-Ip "$http_x_real_ip" always;
        add_header X-Echo-Forwarded "$http_forwarded" always;
        return 200 '{"status":"UP"}';
    }
}
EOF

docker network create "$network" > /dev/null
MSYS_NO_PATHCONV=1 docker run -d --name "$api_name" --network "$network" --network-alias api \
  -v "$(to_docker_path "$site/api/default.conf"):/etc/nginx/conf.d/default.conf:ro" \
  "$image" > /dev/null

start_web() {
  # start_web <container name> <host port> <VITADELTA_TLS>
  MSYS_NO_PATHCONV=1 docker run -d --name "$1" --network "$network" \
    -e VITADELTA_TLS="$3" -e NGINX_ENVSUBST_FILTER='^VITADELTA_' \
    -p "127.0.0.1:$2:8080" \
    -v "$(to_docker_path "$here/nginx.conf"):/etc/nginx/nginx.conf:ro" \
    -v "$(to_docker_path "$here/snippets"):/etc/nginx/snippets:ro" \
    -v "$(to_docker_path "$here/templates"):/etc/nginx/templates:ro" \
    -v "$(to_docker_path "$site/html"):/usr/share/nginx/html:ro" \
    "$image" > /dev/null
  for _ in $(seq 1 50); do
    if curl -fsS -o /dev/null "http://127.0.0.1:$2/" 2> /dev/null; then return 0; fi
    sleep 0.2
  done
  echo "nginx container $1 did not answer" >&2
  docker logs "$1" >&2 || true
  return 1
}
start_web "$name" "$port" false

base="http://127.0.0.1:$port"
failures=0
header_value() {
  printf '%s' "$1" | tr -d '\r' | awk -v name="$2" -F': ' 'tolower($1)==name {print substr($0, length($1) + 3)}'
}
header_count() {
  printf '%s' "$1" | tr -d '\r' | awk -v name="$2" -F': ' 'tolower($1)==name {n++} END {print n + 0}'
}
pass() { echo "ok    $1"; }
fail_check() {
  echo "FAIL  $1"
  failures=$((failures + 1))
}

# security_headers <headers> <expected referrer policy> <expected csp> <expect hsts: yes|no>
# Prints the problems; empty output means every header is present once with its exact value.
security_headers() {
  local headers="$1" referrer="$2" csp="$3" want_hsts="$4" name value
  for name in content-security-policy x-content-type-options x-frame-options referrer-policy permissions-policy; do
    [ "$(header_count "$headers" "$name")" = "1" ] || printf ' %s-sent-%s-times' "$name" "$(header_count "$headers" "$name")"
  done
  [ "$(header_value "$headers" content-security-policy)" = "$csp" ] || printf ' csp'
  [ "$(header_value "$headers" x-content-type-options)" = "nosniff" ] || printf ' nosniff'
  [ "$(header_value "$headers" x-frame-options)" = "DENY" ] || printf ' x-frame-options'
  [ "$(header_value "$headers" referrer-policy)" = "$referrer" ] || printf ' referrer-policy'
  [ "$(header_value "$headers" permissions-policy)" = "camera=(), microphone=(), geolocation=()" ] || printf ' permissions-policy'
  value="$(header_value "$headers" strict-transport-security)"
  if [ "$want_hsts" = yes ]; then
    [ "$value" = "$hsts" ] && [ "$(header_count "$headers" strict-transport-security)" = "1" ] || printf ' hsts-missing'
  else
    [ -z "$value" ] || printf ' hsts-present'
  fi
  value="$(header_value "$headers" server)"
  [ -z "$value" ] || [ "$value" = "nginx" ] || printf ' server-version'
}

check() {
  # check <path> <expected status> <expected content-type prefix or "-"> [cache] [referrer policy]
  # cache: "immutable" (exactly the one-year immutable policy), "revalidate" (no-cache or
  # no-store) or "-" (no expectation). Any response not expected immutable must not be.
  local path="$1" want_status="$2" want_type="$3" want_cache="${4:--}"
  local referrer="${5:-strict-origin-when-cross-origin}" headers status type cache problems
  headers="$(curl -sS -o /dev/null -D - "$base$path")"
  status="$(printf '%s' "$headers" | awk 'NR==1 {print $2}')"
  type="$(header_value "$headers" content-type)"
  cache="$(header_value "$headers" cache-control)"
  local ok=true
  [ "$status" = "$want_status" ] || ok=false
  if [ "$want_type" != "-" ] && [[ "$type" != "$want_type"* ]]; then ok=false; fi
  case "$want_cache" in
    immutable) [ "$cache" = "$immutable" ] || ok=false ;;
    revalidate) [[ "$cache" =~ no-cache|no-store ]] && [[ "$cache" != *immutable* ]] || ok=false ;;
    *) [[ "$cache" != *immutable* ]] || ok=false ;;
  esac
  problems="$(security_headers "$headers" "$referrer" "$web_csp" no)"
  [ -z "$problems" ] || ok=false
  if $ok; then
    pass "$path -> $status ${type:-} [${cache:-no cache-control}]"
  else
    fail_check "$path -> $status ${type:-} [${cache:-no cache-control}] (expected $want_status $want_type cache=$want_cache; headers:${problems:- ok})"
  fi
}

main="$(cd "$site/html" && ls main-*.js 2> /dev/null | head -n 1)"
chunk="$(cd "$site/html" && ls chunk-*.js 2> /dev/null | head -n 1)"
style="$(cd "$site/html" && ls styles-*.css 2> /dev/null | head -n 1)"

check "/" 200 "text/html" revalidate
check "/index.html" 200 "text/html" revalidate
check "/results?country=TR" 200 "text/html" revalidate
check "/account/saved" 200 "text/html" revalidate
check "/products/3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01" 200 "text/html" revalidate
check "/auth/verify" 200 "text/html" revalidate no-referrer
check "/auth/reset" 200 "text/html" revalidate no-referrer
[ -n "$main" ] && check "/$main" 200 "application/javascript" immutable
[ -n "$chunk" ] && check "/$chunk" 200 "application/javascript" immutable
[ -n "$style" ] && check "/$style" 200 "text/css" immutable
check "/media/check-Ab3dE_9z.woff2" 200 "font/woff2" immutable
check "/media/unhashed.woff2" 200 "font/woff2"
check "/favicon.ico" 200 "image/"
check "/i18n/en.json" 200 "application/json" revalidate
check "/i18n/admin/tr.json" 200 "application/json" revalidate
check "/chunk-DOESNOTEXIST.js" 404 "-"
check "/chunk-Zz9_Yy8-.js" 404 "-"
check "/main-DOESNOTEXIST.js" 404 "-"
check "/styles-DOESNOTEXIST.css" 404 "-"
check "/i18n/xx.json" 404 "-"
check "/i18n/admin/xx.json" 404 "-"
check "/media/missing.woff2" 404 "-"
check "/media/missing-Ab3dE_9z.woff2" 404 "-"
check "/actuator/health" 404 "-"
check "/actuator/env" 404 "-"

# Proxied API answers keep the API's own policy and referrer rule, each header exactly once.
api_check() {
  # api_check <label> <expected status> <curl args...>
  local label="$1" want_status="$2" headers status problems
  shift 2
  headers="$(curl -sS -o /dev/null -D - "$@")"
  status="$(printf '%s' "$headers" | awk 'NR==1 {print $2}')"
  problems="$(security_headers "$headers" no-referrer "$api_csp" no)"
  if [ "$status" = "$want_status" ] && [ -z "$problems" ]; then
    pass "$label -> $status, API policy kept, each header once"
  else
    fail_check "$label -> $status (expected $want_status; headers:${problems:- ok})"
  fi
  LAST_HEADERS="$headers"
}

LAST_HEADERS=""
api_check "/api/v1/health" 200 "$base/api/v1/health"

# Forwarding headers: a spoofed X-Forwarded-For is replaced by the peer address, Forwarded and
# X-Real-IP are dropped, and only a well-formed X-Request-Id is passed on.
api_check "/api/v1/health with spoofed forwarding headers" 200 "$base/api/v1/health" \
  -H "X-Forwarded-For: 203.0.113.7, 198.51.100.9" -H "Forwarded: for=203.0.113.7" \
  -H "X-Real-IP: 203.0.113.7" -H "X-Request-Id: check-request-01"
forwarded_for="$(header_value "$LAST_HEADERS" x-echo-forwarded-for)"
if [[ "$forwarded_for" =~ ^[0-9.]+$ ]] && [[ "$forwarded_for" != *203.0.113.7* ]]; then
  pass "X-Forwarded-For overwritten with the peer address ($forwarded_for)"
else
  fail_check "X-Forwarded-For reached the API as '$forwarded_for' (expected the peer address only)"
fi
if [ -z "$(header_value "$LAST_HEADERS" x-echo-real-ip)" ] && [ -z "$(header_value "$LAST_HEADERS" x-echo-forwarded)" ]; then
  pass "client-supplied X-Real-IP and Forwarded dropped"
else
  fail_check "client-supplied X-Real-IP or Forwarded reached the API"
fi
if [ "$(header_value "$LAST_HEADERS" x-echo-request-id)" = "check-request-01" ]; then
  pass "well-formed X-Request-Id forwarded"
else
  fail_check "well-formed X-Request-Id was not forwarded"
fi
long_id="$(printf 'a%.0s' $(seq 1 65))"
for bad_id in "has space" "$long_id" "semi;colon"; do
  api_check "/api/v1/health with a malformed X-Request-Id" 200 "$base/api/v1/health" -H "X-Request-Id: $bad_id"
  if [ -z "$(header_value "$LAST_HEADERS" x-echo-request-id)" ]; then
    pass "malformed X-Request-Id (${#bad_id} chars) not forwarded"
  else
    fail_check "malformed X-Request-Id (${#bad_id} chars) reached the API"
  fi
done

# The access log never holds a query string.
curl -sS -o /dev/null "$base/i18n/en.json?country=TR&probe=query-secret-4711"
sleep 0.3
log_lines="$(docker logs "$name" 2>&1 | grep "GET /i18n/en.json" || true)"
if [ -n "$log_lines" ] && ! printf '%s' "$log_lines" | grep -q 'query-secret-4711'; then
  pass "access log records /i18n/en.json without its query string"
else
  fail_check "access log missing the request or containing its query string: ${log_lines:-no line}"
fi

# HSTS only when VITADELTA_TLS=true: a second container with the flag set.
start_web "$tls_name" "$tls_port" true
tls_base="http://127.0.0.1:$tls_port"
tls_check() {
  # tls_check <path> <referrer policy> <csp>
  local headers status problems
  headers="$(curl -sS -o /dev/null -D - "$tls_base$1")"
  status="$(printf '%s' "$headers" | awk 'NR==1 {print $2}')"
  problems="$(security_headers "$headers" "$2" "$3" yes)"
  if [ -z "$problems" ]; then
    pass "VITADELTA_TLS=true $1 -> $status with HSTS"
  else
    fail_check "VITADELTA_TLS=true $1 -> $status (headers:$problems)"
  fi
}
tls_check "/" strict-origin-when-cross-origin "$web_csp"
tls_check "/auth/reset" no-referrer "$web_csp"
tls_check "/chunk-DOESNOTEXIST.js" strict-origin-when-cross-origin "$web_csp"
tls_check "/actuator/health" strict-origin-when-cross-origin "$web_csp"
tls_check "/api/v1/health" no-referrer "$api_csp"

# API down: the 502/504 from nginx carries the web policy and every header as well.
docker stop "$api_name" > /dev/null
headers="$(curl -sS -o /dev/null -D - "$base/api/v1/health")"
status="$(printf '%s' "$headers" | awk 'NR==1 {print $2}')"
problems="$(security_headers "$headers" strict-origin-when-cross-origin "$web_csp" no)"
if { [ "$status" = "502" ] || [ "$status" = "504" ]; } && [ -z "$problems" ]; then
  pass "/api/v1/health with the API down -> $status with every security header"
else
  fail_check "/api/v1/health with the API down -> $status (headers:${problems:- ok})"
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures routing check(s) failed." >&2
  exit 1
fi
echo "All routing checks passed."
