#!/usr/bin/env bash
#
# Guards how a visitor's address travels from the pinggy tunnel to the backend.
#
# Measured from Loki on 2026-10-19: every request reached the outer nginx from pinggy's own
# Docker address with no X-Forwarded-For, so the backend's per-IP rate limiter held every
# visitor in one bucket and Amplitude could not geolocate anyone. And because pinggy passes a
# client's own X-Forwarded-For straight through (checked on a test tunnel), the rate limiter,
# which read the FIRST entry of that header, could be given a fresh bucket per request by
# anyone who invented a new address each time.
#
# The chain that fixes both, each link of which fails silently if dropped:
#   1. pinggy starts with `x:xff`, so it sends the visitor's address at all;
#   2. the outer nginx's real_ip takes the RIGHT-most untrusted address (real_ip_recursive),
#      so a forged entry to the left of pinggy's is ignored;
#   3. every proxy_pass in both nginx configs overwrites X-Real-IP with that address, so a
#      client-sent X-Real-IP never survives to the backend;
#   4. the frontend nginx adopts X-Real-IP as its own $remote_addr, or every request that
#      comes through it (www's /api/, and Amplitude's /amp/) names the outer proxy instead;
#   5. the backend keys rate limits on X-Real-IP (RateLimitInterceptorTest pins that side).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE="$PROJECT_DIR/docker-compose.prod.yml"
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

for f in "$COMPOSE" "$OUTER" "$FRONT" "$LIMITER"; do
  check "${f#"$PROJECT_DIR"/} exists" '[ -f "$f" ]'
done

echo "  1. pinggy"
# The pinggy service's command list, comments stripped, up to the next key at its indent.
pinggy_command="$(awk '
  /^  pinggy:/ { inside = 1; next }
  inside && /^  [a-z]/ { exit }
  inside && /^    command:/ { cmd = 1; next }
  inside && cmd && /^    [a-z]/ { cmd = 0 }
  inside && cmd { sub(/#.*/, ""); print }
' "$COMPOSE")"
check "pinggy has a command" '[ -n "$pinggy_command" ]'
check "pinggy is started with x:xff" 'grep -qE "^[[:space:]]*-[[:space:]]*x:xff[[:space:]]*$" <<<"$pinggy_command"'
check "pinggy still forwards to nginx:80" 'grep -qE "^[[:space:]]*-[[:space:]]*nginx:80[[:space:]]*$" <<<"$pinggy_command"'

echo "  2. outer nginx"
check "takes the client address from X-Forwarded-For" 'grep -qE "^[[:space:]]*real_ip_header[[:space:]]+X-Forwarded-For;" "$OUTER"'
check "takes the right-most untrusted address (real_ip_recursive on)" \
  'grep -qE "^[[:space:]]*real_ip_recursive[[:space:]]+on;" "$OUTER"'

echo "  3. every proxy_pass overwrites X-Real-IP"
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

echo "  4. frontend nginx"
check "adopts X-Real-IP as the client address" 'grep -qE "^[[:space:]]*real_ip_header[[:space:]]+X-Real-IP;" "$FRONT"'
check "trusts it only from the private Docker ranges" \
  'grep -qE "^[[:space:]]*set_real_ip_from[[:space:]]+172\.16\.0\.0/12;" "$FRONT" && ! grep -qE "^[[:space:]]*set_real_ip_from[[:space:]]+0\.0\.0\.0/0;" "$FRONT"'

echo "  5. backend"
client_ip_method="$(awk '/private String getClientIp/,/^  }/' "$LIMITER")"
check "the rate limiter reads X-Real-IP" 'grep -qF "\"X-Real-IP\"" <<<"$client_ip_method"'
check "the rate limiter does not read X-Forwarded-For" '! grep -qF "X-Forwarded-For\")" <<<"$client_ip_method"'

echo "  $checks checks, $failures failures"
[ "$failures" -eq 0 ]
