#!/usr/bin/env bash
#
# Guards one nginx rule that fails silently and looks like an application bug.
#
# Since commit 62d26cc every proxy_pass in config/nginx/nginx-proxy.conf names its
# upstream through a VARIABLE, so nginx defers DNS to request time and boots even when
# an upstream is down. That change has a consequence nothing else in the repo records:
#
#   When the upstream is given by a variable AND proxy_pass also specifies a URI, nginx
#   passes that URI to the upstream LITERALLY. It does not replace the matched location
#   prefix with it, which is what the same directive does with a static upstream.
#
# So `location /api/ { proxy_pass http://$upstream:8080/api/; }` sends every request in
# that location to the upstream as a bare `/api/`. Live on term-time.simonrowe.dev: the
# page loaded perfectly, static assets were fine, and every single API call came back as
# a Spring 404 whose `path` field read "/api/" no matter what had been requested.
#
# frontend/nginx.conf deliberately keeps its `/api/` URI part: it names a static host,
# where the substitution behaves as everyone expects. Only the variable form is caught.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CONF="$PROJECT_DIR/config/nginx/nginx-proxy.conf"

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

echo "  proxy_pass with a variable upstream must not carry a URI"

# awk rather than grep, because the rule depends on the ENCLOSING location: an exact-match
# `location = /path` has no remainder to append, so passing a literal URI there is not only
# safe, it is the only way to rewrite that one path. `location = /` relies on exactly that
# to map the site root onto /school/ for term-time.simonrowe.dev.
offenders="$(awk '
  /^[[:space:]]*#/ { next }
  /location[[:space:]]/ {
    exact = ($0 ~ /location[[:space:]]+=[[:space:]]/)
  }
  /proxy_pass/ {
    line = $0
    sub(/#.*/, "", line)
    if (line ~ /proxy_pass[[:space:]]+https?:\/\/\$[A-Za-z_][A-Za-z0-9_]*(:[0-9]+)?\/[^;[:space:]]/ && !exact) {
      printf "%d:%s\n", NR, line
    }
  }
' "$CONF")"

check "no prefix-match location proxies a variable upstream with a URI" '[ -z "$offenders" ]'
if [ -n "$offenders" ]; then
  echo "      these send the literal URI instead of the request path:"
  echo "$offenders" | sed 's/^/        /'
fi

# The rule above only matters while upstreams really are variables; a static name stops
# nginx booting whenever that upstream is down, which is what 62d26cc set out to fix.
static="$(awk '
  /^[[:space:]]*#/ { next }
  /proxy_pass/ {
    line = $0
    sub(/#.*/, "", line)
    if (line ~ /proxy_pass[[:space:]]+https?:\/\/[a-z]/) { printf "%d:%s\n", NR, line }
  }
' "$CONF")"

check "every proxy_pass still resolves its upstream through a variable" '[ -z "$static" ]'
if [ -n "$static" ]; then
  echo "      static upstreams stop nginx booting when that upstream is down:"
  echo "$static" | sed 's/^/        /'
fi

echo "  $checks checks, $failures failures"
[ "$failures" -eq 0 ]
