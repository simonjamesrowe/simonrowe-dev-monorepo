#!/usr/bin/env bash
#
# Coverage for the 2026-10-22 outage: when pinggy's own log says its token is held
# by another session, scripts/monitor-prod.sh must report that as CRIT and leave
# the stack alone. Restarting pinggy re-dials a refused token (keeping it blocked)
# and the whole-stack reconcile re-runs every one-shot init container - neither can
# fix a token conflict, and on 2026-10-22 they ran every few minutes for a day.
#
# `docker` is stubbed on PATH: `logs` prints $FAKE_PINGGY_LOG, `exec` fails (no
# deploy in progress), and every call is recorded. DRY_RUN=1 throughout, so even a
# stub miss could not restart anything.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCRIPT="$PROJECT_DIR/scripts/monitor-prod.sh"

export DRY_RUN=1

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
STUBS="$TMP/stubs"
mkdir -p "$STUBS"
CALLS="$TMP/calls"

cat >"$STUBS/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $*" >> "$CALLS"
case "$1" in
  logs) printf '%s\n' "${FAKE_PINGGY_LOG:-}"; exit 0 ;;
  exec) exit 1 ;;
  *)    exit 0 ;;
esac
EOF
chmod +x "$STUBS/docker"

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

# Three ticks against a URL nothing listens on, so the third reaches
# FAILURE_THRESHOLD. Sets $out (the third tick's output) and $rc.
run_to_threshold() {
  local state="$TMP/state-$1"
  : >"$CALLS"
  for _ in 1 2 3; do
    out="$(PATH="$STUBS:$PATH" CALLS="$CALLS" FAKE_PINGGY_LOG="$2" \
      CHECK_URL="http://127.0.0.1:1/" STATE_DIR="$state" \
      COMPOSE_PROJECT="test-pinggy-conflict-$$" bash "$SCRIPT" 2>&1)"
    rc=$?
  done
}

ACTIVE='✖ Error: A tunnel with the same token (abc) is already active. Close the existing tunnel, or use the force option.'
BLOCKED='✖ Error: Too many failed connection attempts for this token (abc). This token is temporarily blocked.'
RESET='⚠ Warning: Connection Reset'

# ---------------------------------------------------------------------------
echo "  'already active' in pinggy's log: CRIT, and nothing restarted"
# ---------------------------------------------------------------------------
run_to_threshold active "$RESET
⠙ Connecting to Pinggy...$ACTIVE"
check "exits non-zero, like the other give-up paths" "[[ $rc -ne 0 ]]"
check "logs CRIT naming the token conflict and the +force remedy" \
  "grep -q '\[CRIT\] pinggy: the tunnel token is held by another session' <<<\"\$out\" && grep -q 'PINGGY_TOKEN=<token>+force' <<<\"\$out\""
check "does not restart pinggy" "! grep -q 'pinggy: restarting' <<<\"\$out\""
check "does not reconcile the stack" "! grep -q 'Reconciling compose stack' <<<\"\$out\""
check "reads pinggy's recent log by container name" \
  "grep -q 'docker logs --since 5m --tail 500 test-pinggy-conflict-$$-pinggy-1' '$CALLS'"
check "never calls docker compose" "! grep -q 'docker compose' '$CALLS'"

# ---------------------------------------------------------------------------
echo "  'temporarily blocked' alone is the same fault"
# ---------------------------------------------------------------------------
run_to_threshold blocked "$BLOCKED"
check "is reported as a token conflict" \
  "grep -q 'held by another session' <<<\"\$out\" && ! grep -q 'Reconciling compose stack' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  any other pinggy log: the existing remediation is unchanged"
# ---------------------------------------------------------------------------
run_to_threshold other "$RESET
✖ Error: Tunnel disconnected before establishment: Could not connect: Resource temporarily unavailable"
check "pinggy is restarted" "grep -q 'pinggy: restarting (site unreachable' <<<\"\$out\""
check "the stack is reconciled" "grep -q 'Reconciling compose stack' <<<\"\$out\""
check "no conflict is claimed" "! grep -q 'held by another session' <<<\"\$out\""

run_to_threshold empty ""
check "an empty or unreadable log is not a conflict" \
  "grep -q 'pinggy: restarting' <<<\"\$out\" && ! grep -q 'held by another session' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  a long log is still read, whatever is in it"
# ---------------------------------------------------------------------------
# pinggy's spinner writes 100k+ character lines; the grep is fixed-string, so
# there is no pattern to backtrack, and the conflict at the end is still found.
spinner="$(printf '⠙ Connecting to Pinggy...%.0s' $(seq 1 6000))"
run_to_threshold long "$spinner$ACTIVE"
check "a conflict at the end of a 150k-character line is found" \
  "grep -q 'held by another session' <<<\"\$out\""

echo
printf '  %d checks, %d failures\n' "$checks" "$failures"
[[ "$failures" -eq 0 ]]
