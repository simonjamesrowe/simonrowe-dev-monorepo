#!/usr/bin/env bash
#
# Coverage for the 2026-09-06 outage fix: scripts/monitor-prod.sh must restart
# `pinggy` once the public site check has failed FAILURE_THRESHOLD times, in
# addition to (never instead of) the existing whole-stack reconcile.
#
# Why this matters: every public hostname goes through the pinggy tunnel, and
# `docker compose up -d` never touches a container that is already `Up` - which
# is exactly the state a hung tunnel client sits in. Before this change, layer 1
# detected an unreachable site correctly every single minute and did nothing
# that could fix a wedged-but-running pinggy; only a human running
# `docker restart pinggy` ended the outage.
#
# DRY_RUN=1 throughout (exported by run-tests.sh, re-asserted here): every
# remediation path shells out to `docker compose`, so running this for real
# would perform actual restarts.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCRIPT="$PROJECT_DIR/scripts/monitor-prod.sh"

export DRY_RUN=1

# A URL nothing listens on. curl fails instantly with "connection refused"
# rather than waiting out a timeout, so a 3-tick failure run costs nothing.
UNREACHABLE_URL="http://127.0.0.1:1/"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

failures=0
checks=0

fail() {
  failures=$((failures + 1))
  echo "    FAIL: $1"
}

ok() {
  echo "    ok: $1"
}

check() {
  local description="$1" condition="$2"
  checks=$((checks + 1))
  if eval "$condition"; then
    ok "$description"
  else
    fail "$description"
  fi
}

# Runs monitor-prod.sh once against the unreachable check URL, in an isolated
# STATE_DIR, and points COMPOSE_PROJECT at a name that matches no real
# container on this machine - the same reason deploy_in_progress()'s `docker
# exec` is safe to leave unmocked: it fails fast ("No such container") and the
# layer-0 check falls through, which is documented as correct behaviour when
# nginx cannot be reached.
run_tick() {
  local state_dir="$1"
  CHECK_URL="$UNREACHABLE_URL" \
    STATE_DIR="$state_dir" \
    COMPOSE_PROJECT="test-monitor-pinggy-$$" \
    bash "$SCRIPT" 2>&1
}

# ---------------------------------------------------------------------------
echo "  below FAILURE_THRESHOLD: no remediation yet"
# ---------------------------------------------------------------------------
state="$TMP/state-below-threshold"
out1="$(run_tick "$state")"
check "tick 1 counts a failure but restarts nothing" \
  "grep -q 'Health check failed (1/3)' <<<\"\$out1\" && ! grep -q 'restarting' <<<\"\$out1\""

out2="$(run_tick "$state")"
check "tick 2 still restarts nothing" \
  "grep -q 'Health check failed (2/3)' <<<\"\$out2\" && ! grep -q 'restarting' <<<\"\$out2\""

# ---------------------------------------------------------------------------
echo "  at FAILURE_THRESHOLD: pinggy is restarted, and so is the rest of the stack"
# ---------------------------------------------------------------------------
out3="$(run_tick "$state")"
check "tick 3 reaches the threshold" "grep -q 'Health check failed (3/3)' <<<\"\$out3\""
check "tick 3 attempts a pinggy restart, naming the reason" \
  "grep -q 'pinggy: restarting (site unreachable' <<<\"\$out3\""
# svc_restart() redirects run_cmd's own stdout (>/dev/null 2>&1), so its
# "[DRYRUN] would run: ..." echo never surfaces here - only svc_restart's own
# log lines do. That is pre-existing behaviour, not something this test can or
# should change; asserting on svc_restart's own log output is what proves the
# real code path (docker compose restart pinggy) ran under the hood.
check "the pinggy restart is issued via svc_restart, not silently" \
  "grep -q 'pinggy: restart issued' <<<\"\$out3\""
# The whole-stack reconcile must still run - this is additive, not a
# replacement. A held-back `Created`/`exited` container is a different,
# equally real failure mode that only `up -d` (not a pinggy restart) can fix.
check "tick 3 still reconciles the whole stack" \
  "grep -q 'Reconciling compose stack' <<<\"\$out3\" && grep -qE 'DRYRUN.*docker compose -f .*up -d\$' <<<\"\$out3\""
# Order matters for the reasoning in the comment: pinggy first, since it is
# the more specific and more likely fix for an ingress-shaped outage.
pinggy_line=$(grep -n 'pinggy: restarting' <<<"$out3" | head -1 | cut -d: -f1)
reconcile_line=$(grep -n 'Reconciling compose stack' <<<"$out3" | head -1 | cut -d: -f1)
check "pinggy is restarted before the whole-stack reconcile" \
  "[[ -n '$pinggy_line' && -n '$reconcile_line' && $pinggy_line -lt $reconcile_line ]]"

# ---------------------------------------------------------------------------
echo "  pinggy's own backoff still applies"
# ---------------------------------------------------------------------------
# svc_record_restart() is a no-op under DRY_RUN (by design - see the comment in
# monitor-prod.sh - so a dry run cannot arm a real backoff window), so the
# backoff path is exercised the same way test-restart-prod-phases.sh exercises
# other stateful paths: seed the counters by hand in a throwaway STATE_DIR.
state_backoff="$TMP/state-backoff"
mkdir -p "$state_backoff"
echo 3 > "$state_backoff/failure_count"
now=$(date +%s)
{
  echo "$now"
  echo "$now"
} > "$state_backoff/svc_pinggy.restarts"

out_backoff="$(run_tick "$state_backoff")"
check "pinggy backs off once its own SERVICE_MAX_RESTARTS is exhausted" \
  "grep -q 'pinggy: backing off' <<<\"\$out_backoff\""
check "a backed-off pinggy still lets the whole-stack reconcile run" \
  "grep -q 'Reconciling compose stack' <<<\"\$out_backoff\""

# ---------------------------------------------------------------------------
echo
printf '  %d checks, %d failures\n' "$checks" "$failures"
[[ "$failures" -eq 0 ]]
