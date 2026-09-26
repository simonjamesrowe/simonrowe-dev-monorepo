#!/usr/bin/env bash
#
# Coverage for monitor-prod.sh's layer 4: restart an internet-facing container
# that cannot resolve external names while the host can.
#
# The 2026-09-24 incident this exists for: the Pi rebooted with the Wi-Fi down,
# Docker started the stack while the host had no nameservers, and twenty
# containers could resolve compose service names (so every healthcheck and every
# public hostname stayed green) but nothing outside the stack, for ~23 hours.
#
# `docker`, `curl` and `getent` are stubbed on PATH, so the site and every
# endpoint read as healthy and the script reaches layer 4 without touching the
# network or this machine's containers. DRY_RUN=1 throughout, as always.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCRIPT="$PROJECT_DIR/scripts/monitor-prod.sh"

export DRY_RUN=1

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PROJECT="test-monitor-dns"
STUBS="$TMP/stubs"
mkdir -p "$STUBS"

# Every URL serves: `curl -sf` exits 0, `curl -w '%{http_code}'` prints 200.
cat >"$STUBS/curl" <<'EOF'
#!/usr/bin/env bash
for arg in "$@"; do
  [[ "$arg" == "%{http_code}" ]] && { printf '200'; exit 0; }
done
exit 0
EOF

# The host's resolver: FAKE_HOST_DNS=broken makes it fail.
cat >"$STUBS/getent" <<'EOF'
#!/usr/bin/env bash
[[ "${FAKE_HOST_DNS:-ok}" == "broken" ]] && exit 2
echo "140.82.121.4 github.com"
EOF

# docker:
#   exec <nginx> test -f ...           -> no maintenance flag (no deploy running)
#   compose ... ps ...                 -> nothing, so layer 2 finds nothing to fix
#   ps --format ...                    -> FAKE_RUNNING (space-separated services)
#   exec <container> getent hosts ...  -> fails for services in FAKE_DNS_BROKEN,
#                                         exits 126 (not runnable) for FAKE_NO_GETENT
# Every call is appended to $CALLS so the test can see what was probed.
cat >"$STUBS/docker" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$CALLS"
case "$1" in
  exec)
    container="$2"
    [[ "$3" == "test" ]] && exit 1
    svc="${container#"$FAKE_PROJECT"-}"
    svc="${svc%-1}"
    [[ " ${FAKE_NO_GETENT:-} " == *" $svc "* ]] && exit 126
    [[ " ${FAKE_DNS_BROKEN:-} " == *" $svc "* ]] && exit 2
    echo "140.82.121.4 github.com"
    ;;
  ps)
    for s in ${FAKE_RUNNING:-}; do echo "$FAKE_PROJECT-$s-1"; done
    ;;
  compose) ;;
esac
exit 0
EOF
chmod +x "$STUBS"/*

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

ALL_EGRESS="alloy backend software-factory deployer dependencytrack-apiserver temporal-ui langfuse langfuse-worker searxng trivy-server pinggy"

# run_tick <state-dir> [VAR=value ...]
run_tick() {
  local state_dir="$1"
  shift
  : > "$TMP/calls"
  env PATH="$STUBS:$PATH" CALLS="$TMP/calls" FAKE_PROJECT="$PROJECT" \
    COMPOSE_PROJECT="$PROJECT" STATE_DIR="$state_dir" DRY_RUN=1 \
    FAKE_RUNNING="$ALL_EGRESS mongodb kafka" "$@" \
    bash "$SCRIPT" 2>&1
}

# ---------------------------------------------------------------------------
echo "  healthy stack: every egress service probed, nothing restarted"
# ---------------------------------------------------------------------------
out="$(run_tick "$TMP/healthy")"
check "no restarts and no DNS warnings" \
  "! grep -qE 'restarting|cannot resolve' <<<\"\$out\""
check "all eleven egress services were probed" \
  "[[ \$(grep -c 'getent hosts github.com' '$TMP/calls') -eq 11 ]]"
check "internal-only services (mongodb, kafka) are never probed" \
  "! grep -qE '(mongodb|kafka)-1 getent' '$TMP/calls'"

# ---------------------------------------------------------------------------
echo "  a container that cannot resolve while the host can"
# ---------------------------------------------------------------------------
state="$TMP/broken"
out1="$(run_tick "$state" FAKE_DNS_BROKEN="dependencytrack-apiserver")"
check "tick 1 counts it but restarts nothing" \
  "grep -q 'dependencytrack-apiserver: cannot resolve github.com although the host can (1/3)' <<<\"\$out1\" && ! grep -q restarting <<<\"\$out1\""
out2="$(run_tick "$state" FAKE_DNS_BROKEN="dependencytrack-apiserver")"
check "tick 2 still restarts nothing" \
  "grep -q '(2/3)' <<<\"\$out2\" && ! grep -q restarting <<<\"\$out2\""
out3="$(run_tick "$state" FAKE_DNS_BROKEN="dependencytrack-apiserver")"
check "tick 3 restarts exactly that service, naming the reason" \
  "grep -q 'dependencytrack-apiserver: restarting (cannot resolve external names' <<<\"\$out3\""
check "tick 3 restarts nothing else" \
  "[[ \$(grep -c 'restarting' <<<\"\$out3\") -eq 1 ]]"
check "the counter resets after the restart" \
  "[[ \$(cat '$state/svc_dependencytrack-apiserver.dns-failures') == 0 ]]"

# ---------------------------------------------------------------------------
echo "  a recovered container resets its count"
# ---------------------------------------------------------------------------
state_flap="$TMP/flap"
run_tick "$state_flap" FAKE_DNS_BROKEN="alloy" >/dev/null
run_tick "$state_flap" FAKE_DNS_BROKEN="alloy" >/dev/null
run_tick "$state_flap" >/dev/null
out_flap="$(run_tick "$state_flap" FAKE_DNS_BROKEN="alloy")"
check "two failures, a success, then a failure is 1/3 again - not a restart" \
  "grep -q 'alloy: cannot resolve github.com although the host can (1/3)' <<<\"\$out_flap\" && ! grep -q restarting <<<\"\$out_flap\""

# ---------------------------------------------------------------------------
echo "  the host cannot resolve either: an outage, not a container fault"
# ---------------------------------------------------------------------------
state_outage="$TMP/outage"
# Accumulated across all four ticks: the restart would fire on tick 3 and its
# counter reset, so asserting on tick 4 alone could not see it.
out_outage=""
for _ in 1 2 3 4; do
  out_outage+="$(run_tick "$state_outage" FAKE_HOST_DNS=broken FAKE_DNS_BROKEN="$ALL_EGRESS")"$'\n'
done
check "says why it is skipping" \
  "grep -q 'host cannot resolve github.com - skipping container DNS checks' <<<\"\$out_outage\""
check "restarts nothing, even after four ticks" "! grep -q restarting <<<\"\$out_outage\""
check "does not even probe the containers" "! grep -q 'getent' '$TMP/calls'"

# ---------------------------------------------------------------------------
echo "  a stopped container is layer 2's business, not a DNS failure"
# ---------------------------------------------------------------------------
state_stopped="$TMP/stopped"
out_stopped="$(run_tick "$state_stopped" FAKE_RUNNING="backend" FAKE_DNS_BROKEN="alloy")"
check "alloy is not running, so it is not probed or counted" \
  "! grep -q 'alloy' '$TMP/calls' && ! grep -q 'alloy: cannot resolve' <<<\"\$out_stopped\""

# ---------------------------------------------------------------------------
echo "  an image with no runnable getent is 'cannot probe', never a DNS failure"
# ---------------------------------------------------------------------------
state_noget="$TMP/noget"
out_noget=""
for _ in 1 2 3 4; do
  out_noget+="$(run_tick "$state_noget" FAKE_NO_GETENT="searxng")"$'\n'
done
check "says the probe cannot run, naming the exit code" \
  "grep -q 'searxng: cannot probe DNS - getent is not runnable in the image (exit 126)' <<<\"\$out_noget\""
check "never counts it or restarts it across four ticks" \
  "! grep -qE 'searxng: (cannot resolve|restarting)' <<<\"\$out_noget\""

# ---------------------------------------------------------------------------
echo "  the shared restart budget still applies"
# ---------------------------------------------------------------------------
state_budget="$TMP/budget"
mkdir -p "$state_budget"
echo 2 > "$state_budget/svc_alloy.dns-failures"
now=$(date +%s)
printf '%s\n%s\n' "$now" "$now" > "$state_budget/svc_alloy.restarts"
out_budget="$(run_tick "$state_budget" FAKE_DNS_BROKEN="alloy")"
check "a service already restarted SERVICE_MAX_RESTARTS times backs off" \
  "grep -q 'alloy: backing off' <<<\"\$out_budget\""

echo
printf '  %d checks, %d failures\n' "$checks" "$failures"
[[ "$failures" -eq 0 ]]
