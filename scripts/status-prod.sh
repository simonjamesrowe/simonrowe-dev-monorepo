#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

compose_output="$(docker compose -f "$PROJECT_DIR/docker-compose.prod.yml" ps --format json 2>/dev/null || true)"

if [[ -z "$compose_output" ]]; then
  echo "Production services are not running."
  exit 1
fi

printf "\nProduction Service Status\n"
printf "=========================\n"
printf "%-16s %-10s %-10s\n" "Service" "State" "Health"
printf "%-16s %-10s %-10s\n" "-------" "-----" "------"

total_running=0
total_unhealthy=0

while IFS= read -r line; do
  [[ -z "$line" ]] && continue

  name="$(echo "$line" | grep -o '"Name":"[^"]*"' | sed 's/"Name":"//;s/"//')"
  state="$(echo "$line" | grep -o '"State":"[^"]*"' | sed 's/"State":"//;s/"//')"
  health="$(echo "$line" | grep -o '"Health":"[^"]*"' | sed 's/"Health":"//;s/"//')"

  display_health="${health:-"-"}"

  printf "%-16s %-10s %-10s\n" "$name" "$state" "$display_health"

  if [[ "$state" == "running" ]]; then
    total_running=$((total_running + 1))
    if [[ -n "$health" && "$health" != "healthy" ]]; then
      total_unhealthy=$((total_unhealthy + 1))
    fi
  fi
done <<< "$compose_output"

printf "\n"

if curl -sf -o /dev/null -m 5 https://simonrowe.dev 2>/dev/null; then
  printf "%-30s %s\n" "External: simonrowe.dev" "reachable"
else
  printf "%-30s %s\n" "External: simonrowe.dev" "UNREACHABLE"
fi

printf "\n"

if [[ "$total_running" -eq 0 ]]; then
  overall="DOWN"
elif [[ "$total_unhealthy" -gt 0 ]]; then
  overall="DEGRADED"
else
  overall="ALL HEALTHY"
fi

printf "Overall: %s\n\n" "$overall"

if [[ "$overall" == "ALL HEALTHY" ]]; then
  exit 0
else
  exit 1
fi
