#!/usr/bin/env bash
#
# Guards the first-party Amplitude proxy (spec 055) in frontend/nginx.conf.
#
# The browser SDK is pointed at three paths on the page's own origin
# (PROXY_PATHS in frontend/src/analytics/amplitude.ts), and nginx forwards each to an
# Amplitude EU host. The two files are edited separately and nothing else ties them
# together: a renamed path in either one leaves every event and replay going to the SPA's
# index.html, which answers 200, so the SDK reports nothing wrong and analytics just stops.
#
# Also pinned, because each fails silently:
#   - every route goes to the EU data centre, not the US default;
#   - the site's cookies are stripped (Amplitude has no business seeing them);
#   - the upstream certificate is verified;
#   - the visitor's X-Forwarded-For is passed on, without which Amplitude geolocates
#     everyone to the Pi;
#   - an unknown /amp/ path is a 404, not the SPA.
#
# Verified by hand against the real hosts on 2026-10-19: each route returned Amplitude's
# own "invalid API key" error for a dummy key, so the paths reach the endpoints the SDK
# expects. That needs the network and Docker, so it is not repeated here.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
NGINX_CONF="$PROJECT_DIR/frontend/nginx.conf"
SDK="$PROJECT_DIR/frontend/src/analytics/amplitude.ts"

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

# Prints the body of the location block whose opening line matches $1 exactly.
block() {
  awk -v want="$1" '
    index($0, want) && !inside { inside = 1; depth = 0 }
    inside {
      print
      depth += gsub(/\{/, "{") - gsub(/\}/, "}")
      if (depth == 0 && /\}/) exit
    }
  ' "$NGINX_CONF"
}

check "frontend/nginx.conf exists" '[ -f "$NGINX_CONF" ]'
check "the SDK module exists" '[ -f "$SDK" ]'

# route | location line | Amplitude EU host | SDK path constant
while IFS='|' read -r name location host sdk_path; do
  echo "  $name"
  body="$(block "$location")"
  check "has a location block ($location)" '[ -n "$body" ]'
  check "forwards to $host" 'grep -qF "$host" <<<"$body"'
  check "strips the site's cookies" 'grep -qE "proxy_set_header[[:space:]]+Cookie[[:space:]]+\"\";" <<<"$body"'
  check "verifies the upstream certificate" 'grep -qE "proxy_ssl_verify[[:space:]]+on;" <<<"$body"'
  check "passes the visitor's address" 'grep -qE "proxy_set_header[[:space:]]+X-Forwarded-For[[:space:]]+\\\$http_x_forwarded_for;" <<<"$body"'
  check "the SDK sends to the same path ($sdk_path)" "grep -qF \"'$sdk_path'\" \"\$SDK\""
done <<'ROUTES'
events|location = /amp/e {|api.eu.amplitude.com|/amp/e
replay|location /amp/sr/ {|api-sr.eu.amplitude.com|/amp/sr
config|location /amp/cfg/ {|sr-client-cfg.eu.amplitude.com|/amp/cfg
ROUTES

echo "  fallthrough"
check "any other /amp/ path is a 404, not the SPA" \
  'block "location /amp/ {" | grep -qE "return[[:space:]]+404;"'
check "no route points at a US host" '! grep -qE "https://\\\$?[a-z_]*(api2|api-sr|sr-client-cfg)\.amplitude\.com|set \\\$amplitude_[a-z]+ (api2|api-sr|sr-client-cfg)\.amplitude\.com" "$NGINX_CONF"'

echo "  $checks checks, $failures failures"
[ "$failures" -eq 0 ]
