#!/usr/bin/env bash
#
# Behavioural coverage of the maintenance / unavailable pages in
# config/nginx/nginx-proxy.conf.
#
# Runs a real nginx:alpine with the real proxy conf, the real page mounts and a
# throwaway state directory standing in for the `deploy-state` volume, then curls
# it with Host headers. No upstream containers are started, so every proxied
# hostname is genuinely unreachable - which is exactly the state the unavailable
# page exists for, and lets the flag-on/flag-off distinction be tested without a
# stack.
#
# The assertions that matter most are the negative ones. Putting the flag check at
# server level instead of inside `location /` would 503 the GitHub webhook that
# triggered the deploy, and failing /healthz would mark nginx unhealthy - which
# takes pinggy, and therefore every public hostname including Portainer, offline.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

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

if ! docker version >/dev/null 2>&1; then
  echo "    SKIP: docker is not available"
  exit 0
fi

# ---------------------------------------------------------------------------
echo "  nginx -t"
# ---------------------------------------------------------------------------
if docker run --rm \
  -v "$PROJECT_DIR/config/nginx/nginx-proxy.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginx:alpine nginx -t >/dev/null 2>&1; then
  check "the proxy configuration is syntactically valid" true
else
  check "the proxy configuration is syntactically valid" false
fi

# ---------------------------------------------------------------------------
NAME="test-nginx-maintenance-$$"
NETWORK="${NAME}-net"
VOLUME="${NAME}-state"
WRITER="${NAME}-writer"

cleanup() {
  docker rm -f "$NAME" "$WRITER" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  docker volume rm "$VOLUME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Its own network so the upstream names (frontend, backend, ...) do not
# accidentally resolve to something on the default bridge.
docker network create "$NETWORK" >/dev/null 2>&1 || true

# A NAMED VOLUME with a second, read-write container writing the flag - not a host
# bind mount. This is what production does (`deploy-state`, rw on `deployer` and
# ro on `nginx`), and the difference is not cosmetic: with a host bind mount on
# macOS, a delete on the host does not propagate into the already-running nginx
# container's view, so the flag appears stuck on forever and this test fails for a
# reason that has nothing to do with the configuration. Using the production shape
# also means the ro/rw split itself is under test.
docker volume create "$VOLUME" >/dev/null

docker run -d --name "$NAME" --network "$NETWORK" \
  -v "$PROJECT_DIR/config/nginx/nginx-proxy.conf:/etc/nginx/conf.d/default.conf:ro" \
  -v "$PROJECT_DIR/config/nginx/maintenance:/etc/nginx/maintenance:ro" \
  -v "$VOLUME:/var/run/deploy-state:ro" \
  nginx:alpine >/dev/null

# Stands in for the deployer: the only container with write access to the flag.
docker run -d --name "$WRITER" -v "$VOLUME:/var/run/deploy-state" \
  alpine sleep 600 >/dev/null

flag_on() {
  docker exec "$WRITER" touch /var/run/deploy-state/maintenance.on
}

flag_off() {
  docker exec "$WRITER" rm -f /var/run/deploy-state/maintenance.on
}

# Wait for it to accept connections.
for _ in $(seq 1 30); do
  if docker exec "$NAME" curl -sf -o /dev/null http://localhost/healthz 2>/dev/null; then
    break
  fi
  sleep 0.5
done

# Runs curl inside the container, so no host port has to be published.
req() {
  local host="$1" path="${2:-/}" fmt="${3:-%{http_code}}" method="${4:-GET}"
  docker exec "$NAME" curl -s -o /tmp/body -X "$method" -w "$fmt" \
    -H "Host: $host" "http://localhost${path}" 2>/dev/null
}

body() {
  docker exec "$NAME" cat /tmp/body 2>/dev/null
}

# ---------------------------------------------------------------------------
echo "  flag absent: upstreams down -> themed unavailable page"
# ---------------------------------------------------------------------------
code="$(req www.simonrowe.dev /)"
check "www returns a 502-class status when the frontend is down" "[[ '$code' == '502' ]]"
check "www serves the themed unavailable page, not nginx's raw 502" \
  "grep -q 'Temporarily unavailable' <<<\"\$(body)\""
check "the unavailable page needs no external asset" \
  "! grep -qE '<(link|script)|src=\"http' <<<\"\$(body)\""

code="$(req api.simonrowe.dev /api/blogs)"
check "api returns a 502-class status when the backend is down" "[[ '$code' == '502' ]]"
check "api serves the themed unavailable page too" \
  "grep -q 'Temporarily unavailable' <<<\"\$(body)\""

retry="$(req www.simonrowe.dev / '%{header_json}' | tr 'A-Z' 'a-z')"
check "www sends Retry-After with the unavailable page" \
  "grep -q 'retry-after' <<<\"\$retry\""

# During an unplanned outage - no deploy, no flag - the tools used to diagnose it must
# still behave normally, and must show a plain proxy error rather than a themed page:
# these hostnames ARE the debugging tools, and dressing their failure up would hide it.
code="$(req localhost /healthz)"
check "/healthz is 200 during an unplanned outage" "[[ '$code' == '200' ]]"

for host in console.simonrowe.dev temporal.simonrowe.dev langfuse.simonrowe.dev \
  dependency-track.simonrowe.dev; do
  body_text="$(req "$host" / >/dev/null; body)"
  check "$host shows a plain proxy error, not the themed page" \
    "! grep -q 'Temporarily unavailable' <<<\"\$body_text\""
done

# ---------------------------------------------------------------------------
echo "  flag set: themed maintenance page"
# ---------------------------------------------------------------------------
flag_on

code="$(req www.simonrowe.dev /)"
check "www returns 503 while the flag is set" "[[ '$code' == '503' ]]"
check "www serves the themed maintenance page" \
  "grep -q 'Update in progress' <<<\"\$(body)\""
check "the maintenance page needs no external asset" \
  "! grep -qE '<(link|script)|src=\"http' <<<\"\$(body)\""

retry="$(req www.simonrowe.dev / '%{header_json}' | tr 'A-Z' 'a-z')"
check "www sends Retry-After with the maintenance page" \
  "grep -q 'retry-after' <<<\"\$retry\""

code="$(req api.simonrowe.dev /api/blogs)"
check "api returns 503 while the flag is set" "[[ '$code' == '503' ]]"
check "api serves the themed maintenance page" \
  "grep -q 'Update in progress' <<<\"\$(body)\""

# --- the negative assertions, which are the point of the placement ---
code="$(req localhost /healthz)"
check "/healthz is 200 while the flag is set" "[[ '$code' == '200' ]]"

# Not 503: this endpoint is how the running deploy was triggered and how the next
# one will be. If it 503'd, GitHub would retry into a wall for the whole deploy.
# software-factory is not running here, so 502 is the correct answer - what must
# never happen is 503.
code="$(req api.simonrowe.dev /webhooks/github '%{http_code}' POST)"
check "POST /webhooks/github is NOT behind the flag" "[[ '$code' != '503' ]]"

for host in console.simonrowe.dev temporal.simonrowe.dev langfuse.simonrowe.dev \
  dependency-track.simonrowe.dev; do
  code="$(req "$host" /)"
  # These are how a failing deploy gets fixed, so they must stay reachable while
  # the page is up. Their upstreams are not running here, so 502 is expected;
  # 503 would mean the flag had leaked into their server blocks.
  check "$host is NOT behind the flag" "[[ '$code' != '503' ]]"
done

# ---------------------------------------------------------------------------
echo "  flag cleared: back to normal proxying"
# ---------------------------------------------------------------------------
flag_off
code="$(req www.simonrowe.dev /)"
check "www stops serving 503 once the flag is removed" "[[ '$code' != '503' ]]"

# ---------------------------------------------------------------------------
echo
printf '  %d checks, %d failures\n' "$checks" "$failures"
[[ "$failures" -eq 0 ]]
