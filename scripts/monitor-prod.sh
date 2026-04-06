#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

FAILURE_THRESHOLD=${FAILURE_THRESHOLD:-3}
MAX_RESTARTS=${MAX_RESTARTS:-3}
BACKOFF_WINDOW=${BACKOFF_WINDOW:-600}
CHECK_URL=${CHECK_URL:-https://simonrowe.dev}
STATE_DIR=${STATE_DIR:-/tmp/prod-health}

COMPOSE_FILE="$PROJECT_DIR/docker-compose.prod.yml"

mkdir -p "$STATE_DIR"

FAILURE_FILE="$STATE_DIR/failure_count"
RESTART_LOG="$STATE_DIR/restart_timestamps"

log() {
  local level="$1"
  local message="$2"
  echo "$(date -Iseconds) [$level] $message"
}

get_failure_count() {
  if [[ -f "$FAILURE_FILE" ]]; then
    cat "$FAILURE_FILE"
  else
    echo "0"
  fi
}

set_failure_count() {
  echo "$1" > "$FAILURE_FILE"
}

count_recent_restarts() {
  if [[ ! -f "$RESTART_LOG" ]]; then
    echo "0"
    return
  fi
  local now
  now=$(date +%s)
  local count=0
  while IFS= read -r ts; do
    [[ -z "$ts" ]] && continue
    if (( now - ts <= BACKOFF_WINDOW )); then
      (( count++ )) || true
    fi
  done < "$RESTART_LOG"
  echo "$count"
}

record_restart() {
  date +%s >> "$RESTART_LOG"
}

prune_old_restarts() {
  if [[ ! -f "$RESTART_LOG" ]]; then
    return
  fi
  local now
  now=$(date +%s)
  local tmp
  tmp=$(mktemp)
  while IFS= read -r ts; do
    [[ -z "$ts" ]] && continue
    if (( now - ts <= BACKOFF_WINDOW )); then
      echo "$ts"
    fi
  done < "$RESTART_LOG" > "$tmp"
  mv "$tmp" "$RESTART_LOG"
}

prune_old_restarts

if curl -sf -o /dev/null -m 10 "$CHECK_URL"; then
  local_failures=$(get_failure_count)
  if (( local_failures > 0 )); then
    log "INFO" "Health check recovered after $local_failures consecutive failures"
  fi
  set_failure_count 0
  exit 0
fi

failure_count=$(get_failure_count)
(( failure_count++ )) || true
set_failure_count "$failure_count"

log "WARN" "Health check failed ($failure_count/$FAILURE_THRESHOLD) - $CHECK_URL unreachable"

if (( failure_count < FAILURE_THRESHOLD )); then
  exit 0
fi

recent_restarts=$(count_recent_restarts)
if (( recent_restarts >= MAX_RESTARTS )); then
  log "CRIT" "Max restarts reached ($MAX_RESTARTS in ${BACKOFF_WINDOW}s window) - backing off"
  set_failure_count 0
  exit 1
fi

log "ERROR" "Restarting pinggy container ($failure_count consecutive failures)"
if docker compose -f "$COMPOSE_FILE" restart pinggy; then
  record_restart
  log "INFO" "Pinggy container restarted successfully"
else
  log "ERROR" "Failed to restart pinggy container"
fi

set_failure_count 0
exit 0
