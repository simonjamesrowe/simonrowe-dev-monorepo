#!/usr/bin/env bash
set -euo pipefail

# Production watchdog. Installed by scripts/install-prod-monitoring.sh as a
# once-a-minute cron job logging to /var/log/prod-health/monitor.log.
#
# It runs three independent layers, cheapest first:
#
#   1. SITE   - the public site is reachable. If not, reconcile the whole stack.
#   2. HEALTH - every container reports a healthy/running Docker state. If a
#               container is `unhealthy`, restart just that container.
#   3. ENDPOINT - each public hostname actually serves. If one is down while the
#               site is up, restart the single service behind it.
#
# Layer 2 exists because *Docker never restarts an unhealthy container*.
# `restart: unless-stopped` only fires when the process exits; a container whose
# healthcheck fails forever is left running untouched. On 2026-08-14 the
# Dependency-Track API server hit a NoClassDefFoundError that killed its Jetty
# listener while the JVM stayed alive (its health listener and Hikari pool are
# non-daemon threads). Docker reported `healthy` - because the old healthcheck
# only probed the management port - and the container was never restarted, so
# Dependency-Track was 502ing for 10 days with nobody notified. Layer 3 is the
# backstop for the same class of failure in a service whose healthcheck is
# passing but which is not actually serving through nginx.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

FAILURE_THRESHOLD=${FAILURE_THRESHOLD:-3}
MAX_RESTARTS=${MAX_RESTARTS:-3}
BACKOFF_WINDOW=${BACKOFF_WINDOW:-600}
# Per-service remediation is deliberately less trigger-happy than the whole-stack
# path: a container restart is cheap, but a restart loop is worse than a single
# bad service, so each service gets its own counter and its own backoff.
SERVICE_FAILURE_THRESHOLD=${SERVICE_FAILURE_THRESHOLD:-3}
SERVICE_MAX_RESTARTS=${SERVICE_MAX_RESTARTS:-2}
SERVICE_BACKOFF_WINDOW=${SERVICE_BACKOFF_WINDOW:-1800}
# NOTE: must be a URL nginx actually serves. https://simonrowe.dev (bare domain)
# 301-redirects to www at the Cloudflare edge, so `curl -f` (which treats 3xx as
# success) reports "healthy" even when nginx/frontend/backend/pinggy are all down,
# because the request never reaches origin. www.simonrowe.dev has no such redirect
# and always requires a live origin round-trip.
CHECK_URL=${CHECK_URL:-https://www.simonrowe.dev}
STATE_DIR=${STATE_DIR:-/tmp/prod-health}
# DRY_RUN=1 reports what it would do and touches nothing. Use it to validate a
# change to this script against the live stack: every remediation path here runs
# `docker compose`, so simply executing the script to "see what it says" performs
# real restarts and can recreate containers if the compose file has been edited.
DRY_RUN=${DRY_RUN:-0}

COMPOSE_FILE="$PROJECT_DIR/docker-compose.prod.yml"
COMPOSE_PROJECT=${COMPOSE_PROJECT:-simonrowe-dev-monorepo}

# One-shot init services. These run to completion and then stay `exited 0` for the
# life of the stack - that is success, not failure. They are depended on with
# `condition: service_completed_successfully`, so treating `exited` as broken here
# would fire a stack reconcile on every single cron tick.
ONESHOT_SERVICES=(
  "uploads-init"
  "deploy-state-init"
  "temporal-db-init"
  "temporal-schema-init"
  "temporal-create-namespace"
  "dependencytrack-db-init"
)

is_oneshot() {
  local candidate="$1" s
  for s in "${ONESHOT_SERVICES[@]}"; do
    [[ "$s" == "$candidate" ]] && return 0
  done
  return 1
}

# Public endpoints, and the compose service to restart when one stops serving.
# Each entry is "<service>|<url>|<expected-http-codes>".
#   - api.simonrowe.dev has no route at `/` (404 is correct), so probe a real
#     endpoint instead of the root. It must NOT be an actuator path: management
#     runs on its own port (8081/8082) and is deliberately not routed by nginx, so
#     /actuator/health is a public 404 and would look permanently broken.
#     /api/profile is the smallest public 200 and reaches MongoDB, so it proves the
#     whole backend path rather than just the servlet container.
#   - Dependency-Track's frontend renders even when its API is dead - that was
#     the exact 2026-08-14 symptom - so the API is probed separately and is the
#     entry that restarts the apiserver.
#   - Langfuse and Temporal both sit behind Auth0; an unauthenticated GET of `/`
#     still has to render, so a 200 proves the app server is alive.
ENDPOINTS=(
  "frontend|https://www.simonrowe.dev/|200"
  "backend|https://api.simonrowe.dev/api/profile|200"
  "langfuse|https://langfuse.simonrowe.dev/|200"
  "dependencytrack-apiserver|https://dependency-track.simonrowe.dev/api/version|200"
  "dependencytrack-frontend|https://dependency-track.simonrowe.dev/|200"
  "temporal-ui|https://temporal.simonrowe.dev/|200"
  "portainer|https://console.simonrowe.dev/|200"
)

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
  [[ "$DRY_RUN" != "0" ]] && return 0
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

# Run a mutating docker command, unless DRY_RUN is set.
run_cmd() {
  if [[ "$DRY_RUN" != "0" ]]; then
    log "DRYRUN" "would run: $*"
    return 0
  fi
  "$@"
}

# ---------------------------------------------------------------------------
# Per-service state helpers. State is keyed by compose service name, so a
# flapping service backs off on its own without muting the rest of the stack.
# ---------------------------------------------------------------------------

svc_state_file() {
  # Service names are [a-z0-9-] in this compose file, so they are safe as
  # filenames without escaping.
  echo "$STATE_DIR/svc_${1}"
}

svc_get_failures() {
  local f
  f="$(svc_state_file "$1").failures"
  [[ -f "$f" ]] && cat "$f" || echo "0"
}

svc_set_failures() {
  echo "$2" > "$(svc_state_file "$1").failures"
}

svc_count_recent_restarts() {
  local f now count=0
  f="$(svc_state_file "$1").restarts"
  [[ -f "$f" ]] || { echo "0"; return; }
  now=$(date +%s)
  while IFS= read -r ts; do
    [[ -z "$ts" ]] && continue
    if (( now - ts <= SERVICE_BACKOFF_WINDOW )); then
      (( count++ )) || true
    fi
  done < "$f"
  echo "$count"
}

svc_record_restart() {
  local f tmp now
  # No-op under DRY_RUN so a validation run cannot arm a real backoff window. Side
  # effect: a dry run will therefore re-trigger the same remediation every cycle
  # instead of backing off. That is expected - to exercise the backoff path, seed
  # the `.restarts` file in a throwaway STATE_DIR instead.
  [[ "$DRY_RUN" != "0" ]] && return 0
  f="$(svc_state_file "$1").restarts"
  now=$(date +%s)
  echo "$now" >> "$f"
  # Prune in place so the file cannot grow without bound.
  tmp=$(mktemp)
  while IFS= read -r ts; do
    [[ -z "$ts" ]] && continue
    if (( now - ts <= SERVICE_BACKOFF_WINDOW )); then
      echo "$ts"
    fi
  done < "$f" > "$tmp"
  mv "$tmp" "$f"
}

# Restart one compose service, honouring its own backoff window.
# Returns 0 if a restart was issued, 1 if it was suppressed or failed.
svc_restart() {
  local svc="$1" reason="$2" recent
  recent=$(svc_count_recent_restarts "$svc")
  if (( recent >= SERVICE_MAX_RESTARTS )); then
    log "CRIT" "$svc: backing off - already restarted $recent time(s) in ${SERVICE_BACKOFF_WINDOW}s ($reason). Needs a human."
    svc_set_failures "$svc" 0
    return 1
  fi
  log "ERROR" "$svc: restarting ($reason)"
  svc_record_restart "$svc"
  # `restart` rather than `up -d`: it does not re-evaluate the compose file, so a
  # half-applied local edit or a missing .env variable cannot recreate or destroy
  # anything here. Container recreation is the whole-stack path's job.
  if run_cmd docker compose -f "$COMPOSE_FILE" restart "$svc" >/dev/null 2>&1; then
    log "INFO" "$svc: restart issued"
    svc_set_failures "$svc" 0
    return 0
  fi
  log "ERROR" "$svc: restart command failed"
  svc_set_failures "$svc" 0
  return 1
}

prune_old_restarts

# ---------------------------------------------------------------------------
# Layer 0: is a deploy in progress?
# ---------------------------------------------------------------------------
# Everything below this point treats "www is not serving" and "a container is not
# running" as faults to remediate. During a deploy both are true ON PURPOSE: the
# maintenance page returns 503 by design, and recreate stops and starts containers.
#
# This script and the deployer would otherwise fight each other on every merge. A
# deploy holds the page up for longer than FAILURE_THRESHOLD ticks, so the watchdog
# would reconcile the stack underneath a running deploy - and a bare `up -d` in the
# middle of `recreate` can undo a rollback, restart a container the deploy is
# waiting on, or recreate the deployer itself and kill the workflow orchestrating
# the whole thing.
#
# The maintenance flag is the signal because it is exactly the window that matters:
# the deployer raises it before pulling and drops it once the stack verifies. It is
# read through nginx, which mounts the same volume read-only, so this needs no root
# and no knowledge of the volume's host path. If nginx is not answering, the exec
# fails and we fall through and remediate - which is right, because nginx being
# down is a real emergency and is not something a deploy causes.
deploy_in_progress() {
  docker exec "${COMPOSE_PROJECT}-nginx-1" \
    test -f /var/run/deploy-state/maintenance.on 2>/dev/null
}

if deploy_in_progress; then
  log "INFO" "Maintenance flag is set - a deploy is in progress. Standing down."
  # Deliberately reset, not preserved: the 503s counted here were caused by the
  # deploy, and carrying them into the next tick would let a deploy that finishes
  # at count 2 trigger a reconcile on the first real failure afterwards.
  set_failure_count 0
  exit 0
fi

# ---------------------------------------------------------------------------
# Layer 1: is the public site up at all?
# ---------------------------------------------------------------------------

site_up=true
if ! curl -sf -o /dev/null -m 10 "$CHECK_URL"; then
  site_up=false
fi

if [[ "$site_up" == false ]]; then
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
  if run_cmd docker compose -f "$COMPOSE_FILE" up -d; then
    # nginx now defers upstream DNS resolution to request time (see the resolver
    # directive in config/nginx/nginx-proxy.conf), so it no longer caches a dead
    # upstream IP for the lifetime of the process and does not need bouncing here.
    log "INFO" "Compose stack reconciled successfully"
    record_restart
  else
    log "ERROR" "Failed to reconcile compose stack"
    record_restart
  fi

  set_failure_count 0
  # The stack was just reconciled; let the next run assess the result rather than
  # restarting individual services on top of a stack that is still settling.
  exit 0
fi

# Site is up.
site_failures=$(get_failure_count)
if (( site_failures > 0 )); then
  log "INFO" "Health check recovered after $site_failures consecutive failures"
fi
set_failure_count 0

# ---------------------------------------------------------------------------
# Layer 2: container-level health.
#
# Docker does NOT act on `unhealthy` - it only restarts on process exit - so an
# unhealthy container stays broken until something here restarts it. Containers
# stuck in `created` or `exited` are handled by `up -d` instead, since there is
# no process for `restart` to signal.
# ---------------------------------------------------------------------------

needs_reconcile=false
while IFS='|' read -r svc state health; do
  [[ -z "$svc" ]] && continue
  # A completed one-shot is expected to be `exited`; never remediate it.
  is_oneshot "$svc" && continue

  case "$state" in
    running)
      case "$health" in
        healthy|"")
          # "" means the service declares no healthcheck; nothing to assert here,
          # the endpoint layer below is the only signal for those.
          svc_set_failures "$svc" 0
          ;;
        starting)
          # Still inside start_period - not a failure yet.
          ;;
        unhealthy)
          f=$(svc_get_failures "$svc")
          (( f++ )) || true
          svc_set_failures "$svc" "$f"
          log "WARN" "$svc: container unhealthy ($f/$SERVICE_FAILURE_THRESHOLD)"
          if (( f >= SERVICE_FAILURE_THRESHOLD )); then
            svc_restart "$svc" "docker reports unhealthy" || true
          fi
          ;;
      esac
      ;;
    created|exited|dead)
      # A container that was built but never started, or that died and whose
      # restart policy gave up. `restart` cannot fix `created`; `up -d` can.
      log "WARN" "$svc: container state '$state' - queuing stack reconcile"
      needs_reconcile=true
      ;;
    restarting|paused|removing) ;;
  esac
done < <(
  docker compose -f "$COMPOSE_FILE" ps --all --format '{{.Service}}|{{.State}}|{{.Health}}' 2>/dev/null || true
)

if [[ "$needs_reconcile" == true ]]; then
  recent_restarts=$(count_recent_restarts)
  if (( recent_restarts >= MAX_RESTARTS )); then
    log "CRIT" "Containers need reconciling but stack backoff is active - needs a human."
  else
    log "ERROR" "Reconciling compose stack to start non-running containers"
    if run_cmd docker compose -f "$COMPOSE_FILE" up -d >/dev/null 2>&1; then
      log "INFO" "Compose stack reconciled"
    else
      log "ERROR" "Failed to reconcile compose stack"
    fi
    record_restart
  fi
  # Give the stack a cycle to settle before judging endpoints.
  exit 0
fi

# ---------------------------------------------------------------------------
# Layer 3: does each public hostname actually serve?
#
# This is the layer that would have caught both 2026-08-14 regressions on the
# next cron tick, regardless of what the container healthchecks claimed.
# ---------------------------------------------------------------------------

for entry in "${ENDPOINTS[@]}"; do
  IFS='|' read -r svc url expected <<< "$entry"

  # curl already prints `000` on a connection/timeout failure *and* exits non-zero,
  # so a `|| echo 000` fallback would concatenate into a bogus "000000". Swallow the
  # exit status instead and only substitute when curl produced nothing at all.
  code=$(curl -s -o /dev/null -w '%{http_code}' -m 15 "$url" 2>/dev/null) || true
  [[ -z "$code" ]] && code="000"

  if [[ ",$expected," == *",$code,"* ]]; then
    svc_set_failures "$svc" 0
    continue
  fi

  f=$(svc_get_failures "$svc")
  (( f++ )) || true
  svc_set_failures "$svc" "$f"
  log "WARN" "$svc: $url returned $code (want $expected) ($f/$SERVICE_FAILURE_THRESHOLD)"

  if (( f >= SERVICE_FAILURE_THRESHOLD )); then
    svc_restart "$svc" "$url returned $code" || true
  fi
done

exit 0
