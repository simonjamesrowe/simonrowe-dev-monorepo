#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

FAILURE_THRESHOLD=${FAILURE_THRESHOLD:-3}
MAX_RESTARTS=${MAX_RESTARTS:-3}
BACKOFF_WINDOW=${BACKOFF_WINDOW:-600}
# NOTE: must be a URL nginx actually serves. https://simonrowe.dev (bare domain)
# 301-redirects to www at the Cloudflare edge, so `curl -f` (which treats 3xx as
# success) reports "healthy" even when nginx/frontend/backend/pinggy are all down,
# because the request never reaches origin. www.simonrowe.dev has no such redirect
# and always requires a live origin round-trip.
CHECK_URL=${CHECK_URL:-https://www.simonrowe.dev}
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

# Reconcile the whole stack rather than just bouncing pinggy: an interrupted
# `docker compose up` can leave nginx/frontend/backend/pinggy stuck in `Created`
# (never started), which a plain `restart` cannot fix since there is no running
# process to restart. `up -d` starts any non-running container, in dependency
# order, and is a no-op for services that are already healthy.
log "ERROR" "Reconciling compose stack ($failure_count consecutive failures)"
if docker compose -f "$COMPOSE_FILE" up -d; then
  # nginx has no `resolver` directive, so it resolves the frontend/backend/
  # portainer/langfuse hostnames once at startup and caches those IPs. If `up -d`
  # (re)created any upstream container, it gets a new IP while a still-running
  # nginx keeps proxying to the old dead address (502 / connection refused). All
  # upstreams are confirmed up at this point, so it's always safe to bounce nginx
  # here to force it to re-resolve.
  log "INFO" "Compose stack reconciled successfully; restarting nginx to refresh upstream DNS"
  if docker compose -f "$COMPOSE_FILE" restart nginx; then
    record_restart
  else
    log "ERROR" "Failed to restart nginx after reconciling stack"
    record_restart
  fi
else
  log "ERROR" "Failed to reconcile compose stack"
fi

set_failure_count 0
exit 0
