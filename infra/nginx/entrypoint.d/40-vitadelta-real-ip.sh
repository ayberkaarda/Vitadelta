#!/bin/sh
# Generates /etc/nginx/generated/real-ip.conf from VITADELTA_REAL_IP_FROM.
#
# VITADELTA_REAL_IP_FROM is a comma- or space-separated list of addresses or CIDR blocks of the
# proxies in front of this nginx (for example a TLS-terminating load balancer). Requests whose
# peer is in that list have their client address restored from the X-Forwarded-For header (the
# right-most address that is not itself a trusted proxy); any other peer keeps its own address, so
# the header cannot be forged by arbitrary clients. The API-facing X-Forwarded-For is still
# overwritten with the restored address in the server configuration.
#
# Empty or unset (the default): no directive is written and the peer address is used as before.
set -eu

dir=/etc/nginx/generated
out="$dir/real-ip.conf"
mkdir -p "$dir"

list="$(printf '%s' "${VITADELTA_REAL_IP_FROM:-}" | tr ',' ' ')"

{
  echo "# Generated at container start from VITADELTA_REAL_IP_FROM; do not edit."
  count=0
  for entry in $list; do
    case "$entry" in
      *[!0-9A-Fa-f:./]* | "")
        echo "VITADELTA_REAL_IP_FROM: invalid address or CIDR '$entry'" >&2
        exit 1
        ;;
    esac
    echo "set_real_ip_from $entry;"
    count=$((count + 1))
  done
  if [ "$count" -gt 0 ]; then
    echo "real_ip_header X-Forwarded-For;"
    echo "real_ip_recursive on;"
  fi
} > "$out.tmp"
mv "$out.tmp" "$out"
