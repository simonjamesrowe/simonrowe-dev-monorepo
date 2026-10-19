#!/usr/bin/env bash
#
# Guards one property: a client cannot choose the address the backend rate-limits on.
#
# pinggy sends no visitor address (verified 2026-10-19 on the production tunnel, its `x:xff`
# option included) but passes a client's OWN X-Forwarded-For straight through. While the outer
# nginx trusted X-Forwarded-For from the Docker ranges, that client-written header became the
# logged address and the backend's rate-limit key, so a script could take a fresh bucket per
# request (verified in production: `X-Forwarded-For: 6.6.6.6` was logged as client 6.6.6.6),
# including on chat and summaries, which cost money.
#
# The links that keep the key out of the client's hands, each of which fails silently if
# dropped:
#   1. the outer nginx does NOT recover a client address from X-Forwarded-For;
#   2. every proxy_pass in both nginx configs overwrites X-Real-IP with $remote_addr, so a
#      client-sent X-Real-IP never survives to the backend;
#   3. the frontend nginx adopts X-Real-IP only from the private Docker ranges, so the
#      requests it passes on (www's /api/, Amplitude's /amp/) carry the outer proxy's value;
#   4. the backend keys rate limits on X-Real-IP (RateLimitInterceptorTest pins that side).
#
# The accepted cost is that every visitor shares one bucket, since pinggy's address is the
# only one nginx sees. That is what honest visitors were already getting.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUTER="$PROJECT_DIR/config/nginx/nginx-proxy.conf"
FRONT="$PROJECT_DIR/frontend/nginx.conf"
LIMITER="$PROJECT_DIR/backend/src/main/java/com/simonrowe/ratelimit/RateLimitInterceptor.java"

failures=0
checks=0

check() {
  local description="$1" condition="$2"
  checks=$((checks + 1))
  if eval "$condition"; then
    echo "    ok: $description"
  else
    failures=$((failures + 1))
    echo "    FAIL: $description"
  fi
}

for f in "$OUTER" "$FRONT" "$LIMITER"; do
  check "${f#"$PROJECT_DIR"/} exists" '[ -f "$f" ]'
done

echo "  1. outer nginx"
check "does not take the client address from X-Forwarded-For" \
  '! grep -qE "^[[:space:]]*real_ip_header[[:space:]]+X-Forwarded-For;" "$OUTER"'
check "trusts no forwarding proxy at all (no set_real_ip_from)" \
  '! grep -qE "^[[:space:]]*set_real_ip_from[[:space:]]" "$OUTER"'

echo "  2. every proxy_pass overwrites X-Real-IP"
# Prints "file:line location" for each location that proxies without setting
# X-Real-IP to $remote_addr. A location that does not set it passes a client's own value on.
unset_real_ip="$(awk '
  /^[[:space:]]*#/ { next }
  /^[[:space:]]*location[[:space:]]/ { loc = $0; start = FNR; proxied = 0; real = 0 }
  /proxy_pass/ { proxied = 1 }
  /proxy_set_header[[:space:]]+X-Real-IP[[:space:]]+\$remote_addr;/ { real = 1 }
  /^    \}/ { if (proxied && !real) printf "%s:%d %s\n", FILENAME, start, loc; proxied = 0 }
' "$OUTER" "$FRONT")"
check "no proxied location leaves X-Real-IP to the client" '[ -z "$unset_real_ip" ]'
[ -n "$unset_real_ip" ] && echo "$unset_real_ip" | sed "s#$PROJECT_DIR/#      #"

echo "  3. frontend nginx"
check "adopts X-Real-IP as the client address" 'grep -qE "^[[:space:]]*real_ip_header[[:space:]]+X-Real-IP;" "$FRONT"'
check "trusts it only from the private Docker ranges" \
  'grep -qE "^[[:space:]]*set_real_ip_from[[:space:]]+172\.16\.0\.0/12;" "$FRONT" && ! grep -qE "^[[:space:]]*set_real_ip_from[[:space:]]+0\.0\.0\.0/0;" "$FRONT"'

echo "  4. backend"
client_ip_method="$(awk '/private String getClientIp/,/^  }/' "$LIMITER")"
check "the rate limiter reads X-Real-IP" 'grep -qF "\"X-Real-IP\"" <<<"$client_ip_method"'
check "the rate limiter does not read X-Forwarded-For" '! grep -qF "X-Forwarded-For\")" <<<"$client_ip_method"'

echo "  $checks checks, $failures failures"
[ "$failures" -eq 0 ]
