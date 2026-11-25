#!/usr/bin/env bash
# Routing check of the web nginx configuration, without the compose stack.
#
# Starts a throwaway nginx container (same image as the web Dockerfile) with this
# directory's configuration and an already built web app, then checks that:
#   - application routes answer the SPA document (200, text/html);
#   - existing static files and translations are served with their own type;
#   - missing hashed chunks, styles, translations and media answer 404, not the SPA;
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

if [ ! -f "$dist/index.html" ]; then
  echo "No built web app at $dist (run npm run build in apps/web)." >&2
  exit 2
fi

to_docker_path() {
  # Docker Desktop on Windows (Git Bash) needs a Windows-style path for bind mounts.
  if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

cleanup() { docker rm -f "$name" > /dev/null 2>&1 || true; }
trap cleanup EXIT

MSYS_NO_PATHCONV=1 docker run -d --name "$name" \
  --add-host api:127.0.0.1 \
  -e VITADELTA_TLS=false -e NGINX_ENVSUBST_FILTER='^VITADELTA_' \
  -p "127.0.0.1:$port:8080" \
  -v "$(to_docker_path "$here/nginx.conf"):/etc/nginx/nginx.conf:ro" \
  -v "$(to_docker_path "$here/snippets"):/etc/nginx/snippets:ro" \
  -v "$(to_docker_path "$here/templates"):/etc/nginx/templates:ro" \
  -v "$(to_docker_path "$dist"):/usr/share/nginx/html:ro" \
  "$image" > /dev/null

base="http://127.0.0.1:$port"
for _ in $(seq 1 50); do
  if curl -fsS -o /dev/null "$base/" 2> /dev/null; then break; fi
  sleep 0.2
done

failures=0
check() {
  # check <path> <expected status> <expected content-type prefix or "-">
  local path="$1" want_status="$2" want_type="$3" headers status type
  headers="$(curl -sS -o /dev/null -D - "$base$path")"
  status="$(printf '%s' "$headers" | awk 'NR==1 {print $2}')"
  type="$(printf '%s' "$headers" | tr -d '\r' | awk -F': ' 'tolower($1)=="content-type" {print $2}')"
  local ok=true
  [ "$status" = "$want_status" ] || ok=false
  if [ "$want_type" != "-" ] && [[ "$type" != "$want_type"* ]]; then ok=false; fi
  for header in content-security-policy x-content-type-options x-frame-options referrer-policy; do
    printf '%s' "$headers" | tr -d '\r' | grep -qi "^$header:" || ok=false
  done
  if $ok; then
    echo "ok    $path -> $status ${type:-}"
  else
    echo "FAIL  $path -> $status ${type:-} (expected $want_status $want_type, security headers)"
    failures=$((failures + 1))
  fi
}

chunk="$(cd "$dist" && ls main-*.js 2> /dev/null | head -n 1)"
style="$(cd "$dist" && ls styles-*.css 2> /dev/null | head -n 1)"

check "/" 200 "text/html"
check "/results?country=TR" 200 "text/html"
check "/account/saved" 200 "text/html"
check "/products/3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01" 200 "text/html"
check "/auth/verify" 200 "text/html"
[ -n "$chunk" ] && check "/$chunk" 200 "application/javascript"
[ -n "$style" ] && check "/$style" 200 "text/css"
check "/i18n/en.json" 200 "application/json"
check "/i18n/admin/tr.json" 200 "application/json"
check "/chunk-DOESNOTEXIST.js" 404 "-"
check "/main-DOESNOTEXIST.js" 404 "-"
check "/styles-DOESNOTEXIST.css" 404 "-"
check "/i18n/xx.json" 404 "-"
check "/i18n/admin/xx.json" 404 "-"
check "/media/missing.woff2" 404 "-"
check "/actuator/health" 404 "-"

if [ "$failures" -gt 0 ]; then
  echo "$failures routing check(s) failed." >&2
  exit 1
fi
echo "All routing checks passed."
