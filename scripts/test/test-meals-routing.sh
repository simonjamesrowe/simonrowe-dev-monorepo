#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
PROXY="$PROJECT_DIR/config/nginx/nginx-proxy.conf"
FRONTEND="$PROJECT_DIR/frontend/nginx.conf"
MONITOR="$PROJECT_DIR/scripts/monitor-prod.sh"
HEARTBEAT="$PROJECT_DIR/scripts/prod-heartbeat.sh"

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

echo "  Meals host routing"
check "the edge proxy declares the canonical hostname" \
  "grep -qF 'server_name meals.simonrowe.dev;' '$PROXY'"
check "same-origin meals API calls route to the backend" \
  "grep -qF 'location /api/meals/' '$PROXY'"
check "the frontend maps the hostname to the meals entry" \
  "grep -qF 'meals.simonrowe.dev /meals/index.html;' '$FRONTEND'"
check "the watchdog probes the meals hostname" \
  "grep -qF 'frontend|https://meals.simonrowe.dev/|200' '$MONITOR'"
check "the GitHub heartbeat probes the meals hostname" \
  "grep -qF 'meals|https://meals.simonrowe.dev/|200' '$HEARTBEAT'"
check "bundle uploads fit through the proxy" \
  "awk '/server_name meals.simonrowe.dev;/,/^}/' '$PROXY' | grep -qF 'client_max_body_size 12m;'"
check "a recipe read (bounded at 90s by the backend) outlasts nginx's read timeout" \
  "awk '/server_name meals.simonrowe.dev;/,/^}/' '$PROXY' | grep -qF 'proxy_read_timeout 120s;'"
check "the meals hostname reaches no other backend path" \
  "! awk '/server_name meals.simonrowe.dev;/,/^}/' '$PROXY' | grep -E 'location /api/[^m]'"

if docker version >/dev/null 2>&1; then
  check "the edge proxy configuration is valid nginx syntax" \
    "docker run --rm -v '$PROXY:/etc/nginx/conf.d/default.conf:ro' nginx:alpine nginx -t >/dev/null 2>&1"
else
  echo "    SKIP: docker is not available for nginx -t"
fi

echo "  $checks checks, $failures failures"
[[ "$failures" -eq 0 ]]
