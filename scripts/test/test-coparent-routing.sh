#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
PROXY="$PROJECT_DIR/config/nginx/nginx-proxy.conf"
FRONTEND="$PROJECT_DIR/frontend/nginx.conf"
MONITOR="$PROJECT_DIR/scripts/monitor-prod.sh"

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

echo "  CoParent host routing"
check "the edge proxy declares the canonical hostname" \
  "grep -qF 'server_name coparents.simonrowe.dev;' '$PROXY'"
check "same-origin CoParent API calls route to the backend" \
  "grep -qF 'location /api/coparent/' '$PROXY'"
check "the frontend maps the hostname to the CoParent entry" \
  "grep -qF 'coparents.simonrowe.dev /coparent/index.html;' '$FRONTEND'"
check "the watchdog probes the CoParent hostname" \
  "grep -qF 'frontend|https://coparents.simonrowe.dev/|200' '$MONITOR'"

if docker version >/dev/null 2>&1; then
  check "the edge proxy configuration is valid nginx syntax" \
    "docker run --rm -v '$PROXY:/etc/nginx/conf.d/default.conf:ro' nginx:alpine nginx -t >/dev/null 2>&1"
else
  echo "    SKIP: docker is not available for nginx -t"
fi

echo "  $checks checks, $failures failures"
[[ "$failures" -eq 0 ]]
