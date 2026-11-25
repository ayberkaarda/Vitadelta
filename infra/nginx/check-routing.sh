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
cleanup() {
  docker rm -f "$name" > /dev/null 2>&1 || true
  rm -rf "$site"
}
trap cleanup EXIT
cp -R "$dist/." "$site/"
mkdir -p "$site/media"
printf 'wOF2' > "$site/media/check-Ab3dE_9z.woff2"
printf 'wOF2' > "$site/media/unhashed.woff2"

MSYS_NO_PATHCONV=1 docker run -d --name "$name" \
  --add-host api:127.0.0.1 \
  -e VITADELTA_TLS=false -e NGINX_ENVSUBST_FILTER='^VITADELTA_' \
  -p "127.0.0.1:$port:8080" \
  -v "$(to_docker_path "$here/nginx.conf"):/etc/nginx/nginx.conf:ro" \
  -v "$(to_docker_path "$here/snippets"):/etc/nginx/snippets:ro" \
  -v "$(to_docker_path "$here/templates"):/etc/nginx/templates:ro" \
  -v "$(to_docker_path "$site"):/usr/share/nginx/html:ro" \
  "$image" > /dev/null

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

if [ "$failures" -gt 0 ]; then
  echo "$failures routing check(s) failed." >&2
  exit 1
fi
echo "All routing checks passed."
