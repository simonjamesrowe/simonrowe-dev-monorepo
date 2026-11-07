#!/usr/bin/env bash
#
# Coverage for monitor-prod.sh's layer 3 (public hostnames): it leaves a container
# alone while Docker still reports it `starting`, and it counts on its own.
#
# The 2026-11-07 incident this exists for: the Pi came back on a new SSD and
# cold-started every container at once (load average ~30). The backend was still
# inside its healthcheck's start_period when api.simonrowe.dev had failed three
# ticks in a row, so layer 3 restarted it at 10:36 and again at 10:38, each time
# sending a nearly-booted JVM back to the start. Layer 2 already knew it was
# `starting`; layer 3 never asked.
#
# Writing this test found the second fault. Layers 2 and 3 shared one failure
# counter, and layer 2 zeroes it every tick a container is `healthy`, so a healthy
# container whose hostname failed never got past 1/3. Layer 3 could only restart a
# `starting` container: the harmful case, and never the 2026-08-14 one it exists
# for (Docker saying healthy while the API was dead). The control below is that.
#
# `docker` and `curl` are stubbed on PATH, as in test-monitor-prod-dns.sh. DRY_RUN=1.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCRIPT="$PROJECT_DIR/scripts/monitor-prod.sh"

export DRY_RUN=1

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PROJECT="test-monitor-starting"
STUBS="$TMP/stubs"
mkdir -p "$STUBS"

# The site check (`curl -sf`) passes. An endpoint probe (`-w '%{http_code}'`)
# answers 502 when its URL contains any word in FAKE_FAILING, else 200.
cat >"$STUBS/curl" <<'EOF'
#!/usr/bin/env bash
url="" code=0
for arg in "$@"; do
  [[ "$arg" == http* ]] && url="$arg"
  [[ "$arg" == "%{http_code}" ]] && code=1
done
(( code )) || exit 0
for word in ${FAKE_FAILING:-}; do
  [[ "$url" == *"$word"* ]] && { printf '502'; exit 0; }
done
printf '200'
EOF

# docker:
#   exec <nginx> test -f ...   -> no maintenance flag (no deploy running)
#   compose ... ps --all ...   -> FAKE_PS, one `service|state|health` per word
#   ps --format ...            -> nothing, so layer 4 probes nobody
cat >"$STUBS/docker" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  exec) [[ "$3" == "test" ]] && exit 1 ;;
  compose) [[ " $* " == *" ps "* ]] && for row in ${FAKE_PS:-}; do echo "$row"; done ;;
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

# run_tick <state-dir> [VAR=value ...]
run_tick() {
  local state_dir="$1"
  shift
  env PATH="$STUBS:$PATH" COMPOSE_PROJECT="$PROJECT" STATE_DIR="$state_dir" DRY_RUN=1 \
    "$@" bash "$SCRIPT" 2>&1
}

STARTING="backend|running|starting langfuse|running|"
HEALTHY="backend|running|healthy langfuse|running|"

# ---------------------------------------------------------------------------
echo "  a healthy backend whose hostname fails is restarted on tick 3"
# ---------------------------------------------------------------------------
# Also the control: without it, every "nothing restarted" check below could pass
# because the stubs never reached layer 3 at all.
state="$TMP/healthy"
for tick in 1 2; do run_tick "$state" FAKE_PS="$HEALTHY" FAKE_FAILING="api.simonrowe.dev" >/dev/null; done
out="$(run_tick "$state" FAKE_PS="$HEALTHY" FAKE_FAILING="api.simonrowe.dev")"
check "the third failing tick restarts the backend" \
  "grep -q 'backend: restarting (https://api.simonrowe.dev/api/profile returned 502)' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  a starting backend is not judged, however many ticks it fails"
# ---------------------------------------------------------------------------
state="$TMP/starting"
# Every tick's output, not just the last: a restart on tick 3 resets the count, so
# tick 4 alone would look clean.
out=""
for tick in 1 2 3 4; do
  out+="$(run_tick "$state" FAKE_PS="$STARTING" FAKE_FAILING="api.simonrowe.dev")"$'\n'
done
check "four failing ticks restart nothing" "! grep -q 'restarting' <<<\"\$out\""
check "it says why the hostname was skipped" \
  "grep -q 'backend: still starting, not judging https://api.simonrowe.dev/api/profile yet' <<<\"\$out\""
check "no failure is counted against it" "! grep -q 'backend: hostname failing' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  only the starting service is skipped"
# ---------------------------------------------------------------------------
# langfuse has no healthcheck (health ""), so the hostname is its only signal and
# must still be judged while the backend beside it is starting.
state="$TMP/mixed"
for tick in 1 2; do run_tick "$state" FAKE_PS="$STARTING" FAKE_FAILING="api.simonrowe.dev langfuse" >/dev/null; done
out="$(run_tick "$state" FAKE_PS="$STARTING" FAKE_FAILING="api.simonrowe.dev langfuse")"
check "a failing langfuse beside a starting backend is still restarted" \
  "grep -q 'langfuse: restarting' <<<\"\$out\""
check "and the starting backend is still left alone" "! grep -q 'backend: restarting' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  failures from before a restart do not carry into the new process"
# ---------------------------------------------------------------------------
state="$TMP/carry"
for tick in 1 2; do run_tick "$state" FAKE_PS="$HEALTHY" FAKE_FAILING="api.simonrowe.dev" >/dev/null; done
run_tick "$state" FAKE_PS="$STARTING" FAKE_FAILING="api.simonrowe.dev" >/dev/null
out="$(run_tick "$state" FAKE_PS="$HEALTHY" FAKE_FAILING="api.simonrowe.dev")"
check "the first failure once healthy counts as 1, not 3" \
  "grep -q 'backend: hostname failing (1/3)' <<<\"\$out\" && ! grep -q 'backend: restarting' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  three failing hostnames on one service count once per tick"
# ---------------------------------------------------------------------------
# frontend serves www, coparents and meals. Counted per hostname, one bad tick
# would reach 3/3 and restart it on its first failure.
state="$TMP/frontend"
FRONTEND_PS="frontend|running|healthy backend|running|healthy langfuse|running|"
out="$(run_tick "$state" FAKE_PS="$FRONTEND_PS" FAKE_FAILING="www. coparents. meals.")"
check "tick 1 logs each failing hostname" \
  "[[ \$(grep -c 'frontend: https://.* returned 502' <<<\"\$out\") -eq 3 ]]"
check "but counts frontend once, and restarts nothing" \
  "grep -q 'frontend: hostname failing (1/3)' <<<\"\$out\" && ! grep -q 'restarting' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  a backed-off service does not retry on every tick"
# ---------------------------------------------------------------------------
# svc_record_restart is a no-op under DRY_RUN, so seed the backoff by hand, as
# test-monitor-prod-pinggy-restart.sh does.
state="$TMP/backoff"
mkdir -p "$state"
now=$(date +%s)
printf '%s\n%s\n' "$now" "$now" > "$state/svc_backend.restarts"
for tick in 1 2; do run_tick "$state" FAKE_PS="$HEALTHY" FAKE_FAILING="api.simonrowe.dev" >/dev/null; done
out="$(run_tick "$state" FAKE_PS="$HEALTHY" FAKE_FAILING="api.simonrowe.dev")"
check "tick 3 backs off instead of restarting" "grep -q 'backend: backing off' <<<\"\$out\""
out="$(run_tick "$state" FAKE_PS="$HEALTHY" FAKE_FAILING="api.simonrowe.dev")"
check "tick 4 starts counting again from 1" \
  "grep -q 'backend: hostname failing (1/3)' <<<\"\$out\" && ! grep -q 'backing off' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo
printf '  %d checks, %d failures\n' "$checks" "$failures"
[[ "$failures" -eq 0 ]]
