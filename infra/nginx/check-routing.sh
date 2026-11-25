#!/usr/bin/env bash
# Routing and caching check of the web nginx configuration, without the compose stack.
#
# Starts a throwaway nginx container (same image as the web Dockerfile) with this
# directory's configuration and a copy of an already built web app, then checks that:
#   - application routes answer the SPA document (200, text/html);
#   - existing static files and translations are served with their own type;
#   - missing hashed chunks, styles, translations and media answer 404, not the SPA;
#   - fingerprinted files (hashed bundles, chunks, styles, media) are cached for a year
#     as immutable, while index.html, application routes and translations are revalidated
#     and nothing else (404s included) is ever marked immutable;
#   - the security headers are present on the SPA document, on files and on 404s.
#
# Usage: infra/nginx/check-routing.sh [path-to-built-browser-dir]
# Default build path: apps/web/dist/web/browser (run `npm run build` in apps/web first).
# Needs docker and curl. The container listens on 127.0.0.1:${CHECK_PORT:-18089}.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
dist="${1:-$repo/apps/web/dist/web/browser}"
port="${CHECK_PORT:-18089}"
image="nginx:1.30.8-alpine"
name="vitadelta-nginx-check-$$"
immutable="public, max-age=31536000, immutable"

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
cp -R "$dist/." "$site/"
mkdir -p "$site/media"
printf 'wOF2' > "$site/media/check-Ab3dE_9z.woff2"
printf 'wOF2' > "$site/media/unhashed.woff2"

# A stub upstream that echoes the forwarded client address. It is a second server block of
# the same nginx on 127.0.0.2:8080 (the "api" host of every container below), so no extra
# container or network is needed.
stub="$(mktemp -d)"
cat > "$stub/stub.conf" << 'EOF'
server {
    listen 127.0.0.2:8080;
    location / {
        default_type text/plain;
        return 200 "xff=$http_x_forwarded_for|forwarded=$http_forwarded|real_ip=$http_x_real_ip\n";
    }
}
EOF
trap 'docker rm -f "$name" "$name-trusted" "$name-untrusted" > /dev/null 2>&1 || true; rm -rf "$site" "$stub"' EXIT

# start_nginx <container name> <VITADELTA_REAL_IP_FROM value> [docker run options...]
# The image entrypoint scripts are not mounted executable, so the real-ip generator is run
# explicitly before the stock entrypoint (which renders the template and starts nginx).
start_nginx() {
  local cname="$1" real_ip="$2"
  shift 2
  MSYS_NO_PATHCONV=1 docker run -d --name "$cname" \
    --add-host api:127.0.0.2 \
    -e VITADELTA_TLS=false -e NGINX_ENVSUBST_FILTER='^VITADELTA_' \
    -e "VITADELTA_REAL_IP_FROM=$real_ip" \
    "$@" \
    -v "$(to_docker_path "$here/nginx.conf"):/etc/nginx/nginx.conf:ro" \
    -v "$(to_docker_path "$here/snippets"):/etc/nginx/snippets:ro" \
    -v "$(to_docker_path "$here/templates"):/etc/nginx/templates:ro" \
    -v "$(to_docker_path "$here/entrypoint.d"):/vitadelta-entrypoint:ro" \
    -v "$(to_docker_path "$stub/stub.conf"):/etc/nginx/conf.d/zz-stub.conf:ro" \
    -v "$(to_docker_path "$site"):/usr/share/nginx/html:ro" \
    --entrypoint sh \
    "$image" -c 'sh /vitadelta-entrypoint/40-vitadelta-real-ip.sh && exec /docker-entrypoint.sh nginx -g "daemon off;"' > /dev/null
}

start_nginx "$name" "" -p "127.0.0.1:$port:8080"
start_nginx "$name-trusted" "127.0.0.1/32"
start_nginx "$name-untrusted" "192.0.2.0/24, 198.51.100.7"

base="http://127.0.0.1:$port"
for _ in $(seq 1 50); do
  if curl -fsS -o /dev/null "$base/" 2> /dev/null; then break; fi
  sleep 0.2
done

failures=0
header_value() {
  printf '%s' "$1" | tr -d '\r' | awk -v name="$2" -F': ' 'tolower($1)==name {print substr($0, length($1) + 3)}'
}

check() {
  # check <path> <expected status> <expected content-type prefix or "-"> [cache]
  # cache: "immutable" (exactly the one-year immutable policy), "revalidate" (no-cache or
  # no-store) or "-" (no expectation). Any response not expected immutable must not be.
  local path="$1" want_status="$2" want_type="$3" want_cache="${4:--}" headers status type cache
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
  for header in content-security-policy x-content-type-options x-frame-options referrer-policy permissions-policy; do
    printf '%s' "$headers" | tr -d '\r' | grep -qi "^$header:" || ok=false
  done
  if $ok; then
    echo "ok    $path -> $status ${type:-} [${cache:-no cache-control}]"
  else
    echo "FAIL  $path -> $status ${type:-} [${cache:-no cache-control}] (expected $want_status $want_type cache=$want_cache, security headers)"
    failures=$((failures + 1))
  fi
}

main="$(cd "$site" && ls main-*.js 2> /dev/null | head -n 1)"
chunk="$(cd "$site" && ls chunk-*.js 2> /dev/null | head -n 1)"
style="$(cd "$site" && ls styles-*.css 2> /dev/null | head -n 1)"

check "/" 200 "text/html" revalidate
check "/index.html" 200 "text/html" revalidate
check "/results?country=TR" 200 "text/html" revalidate
check "/account/saved" 200 "text/html" revalidate
check "/products/3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01" 200 "text/html" revalidate
check "/auth/verify" 200 "text/html" revalidate
check "/auth/reset" 200 "text/html" revalidate
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

# Client-address forwarding to the API. The requests are sent from inside each container, so
# the peer address nginx sees is 127.0.0.1. "xff" is what the API receives.
upstream_view() {
  # upstream_view <container> <forged X-Forwarded-For value>
  docker exec "$1" wget -q -O - --header "X-Forwarded-For: $2" \
    --header "Forwarded: for=198.51.100.99" --header "X-Real-IP: 198.51.100.98" \
    http://127.0.0.1:8080/api/v1/echo | tr -d '\r'
}

check_forwarding() {
  # check_forwarding <label> <container> <forged X-Forwarded-For> <expected upstream view>
  local got
  got="$(upstream_view "$2" "$3")"
  if [ "$got" = "$4" ]; then
    echo "ok    forwarding: $1 -> $got"
  else
    echo "FAIL  forwarding: $1 -> $got (expected $4)"
    failures=$((failures + 1))
  fi
}

empty_forwarded="forwarded=|real_ip="
# Default (VITADELTA_REAL_IP_FROM empty): a forged header is overwritten with the peer address.
check_forwarding "default ignores a forged header" "$name" "203.0.113.7" "xff=127.0.0.1|$empty_forwarded"
# The peer is a trusted proxy: the client address behind it is forwarded, recursively
# skipping trusted hops, and other forwarding headers are still dropped.
check_forwarding "trusted proxy restores the client" "$name-trusted" "203.0.113.7" "xff=203.0.113.7|$empty_forwarded"
check_forwarding "trusted proxy chain keeps the right-most untrusted hop" "$name-trusted" "198.51.100.1, 203.0.113.7, 127.0.0.1" "xff=203.0.113.7|$empty_forwarded"
# The peer is not in the trusted list: its own forged header is ignored.
check_forwarding "untrusted peer cannot forge the client" "$name-untrusted" "203.0.113.7" "xff=127.0.0.1|$empty_forwarded"

if [ "$failures" -gt 0 ]; then
  echo "$failures routing check(s) failed." >&2
  exit 1
fi
echo "All routing checks passed."
