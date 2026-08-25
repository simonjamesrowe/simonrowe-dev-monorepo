#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$PROJECT_DIR/docker-compose.prod.yml"

# How long to wait for every container to settle before declaring the deploy bad.
# Elasticsearch alone needs ~130s to bind 9200 on the Pi and the backend another
# ~90s after that, so this has to be generous or we report a failure that isn't one.
VERIFY_TIMEOUT="${VERIFY_TIMEOUT:-420}"
VERIFY_POLL="${VERIFY_POLL:-10}"

PUBLIC_HOSTS=(
  www.simonrowe.dev
  api.simonrowe.dev
  console.simonrowe.dev
  langfuse.simonrowe.dev
  temporal.simonrowe.dev
  dependency-track.simonrowe.dev
)

echo "Pulling latest production images..."
docker compose -f "$COMPOSE_FILE" pull

# Deliberately not fatal. A dependency gate that expires (for example
# "dependency failed to start: container ... elasticsearch-1 is unhealthy") makes
# `up -d` exit non-zero *having already created* the services behind that gate, so
# they sit in Docker `created` and never start. Under `set -e` that aborted the
# script here, skipping every check below - which is how a deploy could leave a
# 502 on www while the operator saw only a wall of "Container ... Running" lines.
# Record the failure and press on to the verification, which explains what is
# actually broken.
echo "Recreating production services..."
deploy_rc=0
docker compose -f "$COMPOSE_FILE" up -d || deploy_rc=$?
if [[ "$deploy_rc" -ne 0 ]]; then
  echo
  echo "WARNING: 'docker compose up -d' exited ${deploy_rc}. Continuing to verification"
  echo "         so the actual state of the stack gets reported."
fi

# nginx now resolves container names at request time through Docker DNS, but it
# still needs a reload when the mounted config gains a new route (for example
# software-factory or temporal-ui). Restarting after reconciliation covers that
# case and remains safe because nginx no longer requires every upstream to
# resolve at startup.
echo "Restarting nginx to load the current proxy configuration..."
docker compose -f "$COMPOSE_FILE" restart nginx

# Containers that are neither healthy nor a cleanly-finished one-shot. `created`
# is the important one to catch: it means the container was built for this deploy
# and then never started because a dependency gate failed.
unsettled_containers() {
  docker compose -f "$COMPOSE_FILE" ps -a --format json 2>/dev/null |
    python3 -c '
import json, sys
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        c = json.loads(line)
    except ValueError:
        continue
    name = c.get("Name", "?")
    state = c.get("State", "")
    health = c.get("Health") or ""
    exit_code = c.get("ExitCode") or 0
    if health:
        if health != "healthy":
            print(f"{name}\t{state}\t{health}")
    elif state == "exited":
        if exit_code != 0:
            print(f"{name}\t{state}\texit={exit_code}")
    elif state != "running":
        print(f"{name}\t{state}\t-")
'
}

echo
echo "Waiting up to ${VERIFY_TIMEOUT}s for all containers to settle..."
elapsed=0
pending="$(unsettled_containers)"
while [[ -n "$pending" && "$elapsed" -lt "$VERIFY_TIMEOUT" ]]; do
  count="$(printf '%s\n' "$pending" | grep -c .)"
  echo "  [${elapsed}s/${VERIFY_TIMEOUT}s] ${count} container(s) not settled yet"
  sleep "$VERIFY_POLL"
  elapsed=$((elapsed + VERIFY_POLL))
  pending="$(unsettled_containers)"
done

failed=0

if [[ -n "$pending" ]]; then
  failed=1
  echo
  echo "The following containers did not reach a healthy state:"
  printf '%s\n' "$pending" | while IFS=$'\t' read -r name state health; do
    printf '  %-52s %-10s %s\n' "$name" "$state" "$health"
  done
  if printf '%s\n' "$pending" | grep -q 'created'; then
    echo
    echo "  A container in 'created' was built for this deploy but never started,"
    echo "  because a service it depends_on never went healthy in time. Check that"
    echo "  dependency's healthcheck start_period, then re-run this script."
  fi
else
  echo "All containers settled."
fi

# A green 'docker compose ps' is not proof the site serves - on 2026-08-14 two
# containers reported healthy while serving errors for 10 days. Always curl the
# public hostnames. Any 2xx/3xx/4xx means nginx reached its upstream; a 502/504
# (or no response) means the upstream is down.
echo
echo "Checking public hostnames..."
for host in "${PUBLIC_HOSTS[@]}"; do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "https://${host}/" || echo 000)"
  case "$code" in
    000 | 502 | 503 | 504)
      printf '  %-34s %s  <-- FAILED\n' "$host" "$code"
      failed=1
      ;;
    *)
      printf '  %-34s %s\n' "$host" "$code"
      ;;
  esac
done

echo
if [[ "$failed" -ne 0 ]]; then
  echo "Production refresh INCOMPLETE - see the failures above."
  exit 1
fi

echo "Production services refreshed and verified."
