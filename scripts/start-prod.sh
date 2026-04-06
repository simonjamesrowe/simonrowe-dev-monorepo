#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

COMPOSE_FILE="$PROJECT_DIR/docker-compose.prod.yml"

echo "Pulling latest production images..."
docker compose -f "$COMPOSE_FILE" pull

echo "Starting production services..."
docker compose -f "$COMPOSE_FILE" up -d

TIMEOUT=180
POLL_INTERVAL=5
ELAPSED=0

echo "Waiting for services to become healthy (timeout: ${TIMEOUT}s)..."

print_status_table() {
  local json_output
  json_output=$(docker compose -f "$COMPOSE_FILE" ps --format json 2>/dev/null || true)

  printf "\n%-20s %-12s %-12s\n" "SERVICE" "STATE" "HEALTH"
  printf "%-20s %-12s %-12s\n" "-------" "-----" "------"

  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    local name state health
    name=$(echo "$line" | grep -o '"Name":"[^"]*"' | sed 's/"Name":"//;s/"//')
    state=$(echo "$line" | grep -o '"State":"[^"]*"' | sed 's/"State":"//;s/"//')
    health=$(echo "$line" | grep -o '"Health":"[^"]*"' | sed 's/"Health":"//;s/"//')
    printf "%-20s %-12s %-12s\n" "$name" "$state" "${health:--}"
  done <<< "$json_output"
  printf "\n"
}

all_services_ready() {
  local json_output
  json_output=$(docker compose -f "$COMPOSE_FILE" ps --format json 2>/dev/null || true)

  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    local state health exit_code
    state=$(echo "$line" | grep -o '"State":"[^"]*"' | sed 's/"State":"//;s/"//')
    health=$(echo "$line" | grep -o '"Health":"[^"]*"' | sed 's/"Health":"//;s/"//')
    exit_code=$(echo "$line" | grep -o '"ExitCode":[0-9]*' | sed 's/"ExitCode"://' || echo "0")

    if [[ -n "$health" ]]; then
      if [[ "$health" != "healthy" ]]; then
        return 1
      fi
    else
      if [[ "$state" == "exited" ]]; then
        if [[ "${exit_code:-0}" != "0" ]]; then
          return 1
        fi
      elif [[ "$state" != "running" ]]; then
        return 1
      fi
    fi
  done <<< "$json_output"

  return 0
}

while [[ $ELAPSED -lt $TIMEOUT ]]; do
  if all_services_ready; then
    echo "All services are ready."
    print_status_table
    exit 0
  fi

  echo "  [${ELAPSED}s/${TIMEOUT}s] Services not yet ready, retrying in ${POLL_INTERVAL}s..."
  sleep "$POLL_INTERVAL"
  ELAPSED=$((ELAPSED + POLL_INTERVAL))
done

echo "Timeout reached after ${TIMEOUT}s. One or more services failed to become healthy."
print_status_table
exit 1
