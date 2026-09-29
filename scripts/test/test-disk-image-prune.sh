#!/usr/bin/env bash
#
# Coverage for the 2026-09-28 disk-full outage fix: unused Docker images are now
# pruned (a) at the end of every successful deploy, by restart-prod.sh, and (b) by
# monitor-prod.sh whenever the filesystem holding /var/lib/docker crosses a
# threshold. Before this nothing ever deleted an image, and 217 of them (45GB
# reclaimable) filled the Pi's disk and took Kafka, Postgres and Mongo down.
#
# `docker`, `df`, `curl` and `getent` are stubbed on PATH. The docker stub records
# every call, so the assertions check the EXACT prune arguments the scripts sent,
# not just that something mentioning "prune" was printed: a test that only grepped
# the output would pass for a prune that was logged and never run, or run with the
# wrong filter.
#
# Most cases run with DRY_RUN=1, as everywhere in this suite. The ones asserting
# what the stub actually received opt out, the way the sync-config tests do, and
# are safe for the same kind of reason: they only ever run after
# assert_docker_is_stubbed has proved `docker` resolves to the stub, so there is no
# real daemon for them to reach.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
MONITOR="$PROJECT_DIR/scripts/monitor-prod.sh"
RESTART="$PROJECT_DIR/scripts/restart-prod.sh"

export DRY_RUN=1

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PROJECT="test-disk-prune"
STUBS="$TMP/stubs"
CALLS="$TMP/calls"
DISK_STATE="$TMP/disk-percent"
mkdir -p "$STUBS"

# df -P <path>: reports DISK_STATE's value if a prune has written one (the "after"
# reading), otherwise FAKE_DISK_PERCENT. FAKE_DF_BROKEN makes it fail outright.
cat >"$STUBS/df" <<'EOF'
#!/usr/bin/env bash
echo "df $*" >> "$CALLS"
[[ -n "${FAKE_DF_BROKEN:-}" ]] && exit 1
pct="$(cat "$DISK_STATE" 2>/dev/null || echo "${FAKE_DISK_PERCENT:-50}")"
echo "Filesystem 1024-blocks Used Available Capacity Mounted on"
echo "/dev/sda2 122000000 100000000 22000000 ${pct}% /"
EOF

# docker:
#   exec <nginx> test -f <flag>        -> FAKE_DEPLOY_FLAG=1 means a deploy is running
#   exec <nginx> stat -c %Y <rollback> -> FAKE_ROLLBACK_MTIME, or "no such file"
#   image prune ...                     -> docker's real output shape; FAKE_PRUNE_EXIT,
#                                          FAKE_PRUNE_SLEEP, FAKE_PRUNE_LINES, and
#                                          FAKE_DISK_AFTER (the disk once it has run)
#   anything else (compose ps, ps)      -> nothing, success
cat >"$STUBS/docker" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$CALLS"
if [[ "$1" == "exec" ]]; then
  case "$3" in
    test) [[ "${FAKE_DEPLOY_FLAG:-0}" == "1" ]] && exit 0 || exit 1 ;;
    stat) [[ -n "${FAKE_ROLLBACK_MTIME:-}" ]] && { echo "$FAKE_ROLLBACK_MTIME"; exit 0; }
          echo "stat: can't stat: No such file or directory" >&2; exit 1 ;;
    *) exit 0 ;;
  esac
fi
if [[ "$1" == "image" && "$2" == "prune" ]]; then
  # exec, so the process timeout(1) kills IS the sleep: a child sleep would outlive
  # it holding the output pipe open, and the caller would wait for it anyway.
  [[ -n "${FAKE_PRUNE_SLEEP:-}" ]] && exec sleep "$FAKE_PRUNE_SLEEP"
  if [[ "${FAKE_PRUNE_EXIT:-0}" != "0" ]]; then
    echo "Error response from daemon: a prune operation is already running" >&2
    exit "$FAKE_PRUNE_EXIT"
  fi
  [[ -n "${FAKE_DISK_AFTER:-}" ]] && echo "$FAKE_DISK_AFTER" > "$DISK_STATE"
  echo "Deleted Images:"
  echo "untagged: ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend:abc123"
  for ((i = 0; i < ${FAKE_PRUNE_LINES:-1}; i++)); do
    echo "deleted: sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  done
  echo
  echo "Total reclaimed space: 1.5GB"
fi
exit 0
EOF

# Every URL serves.
cat >"$STUBS/curl" <<'EOF'
#!/usr/bin/env bash
for arg in "$@"; do
  [[ "$arg" == "%{http_code}" ]] && { printf '200'; exit 0; }
done
exit 0
EOF

cat >"$STUBS/getent" <<'EOF'
#!/usr/bin/env bash
echo "140.82.121.4 github.com"
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

# The guard for every DRY_RUN=0 case below. If `docker` on the stubbed PATH were
# ever the real binary, stop the whole file rather than run a real prune.
assert_docker_is_stubbed() {
  local resolved
  resolved="$(PATH="$STUBS:$PATH" command -v docker)"
  if [[ "$resolved" != "$STUBS/docker" ]]; then
    echo "    ABORT: docker resolves to $resolved, not the stub - refusing to run without DRY_RUN"
    exit 1
  fi
}
assert_docker_is_stubbed

prune_calls() { grep -c '^image prune' "$CALLS" 2>/dev/null || true; }

# run_monitor <state-dir> <dry-run> [VAR=value ...]
run_monitor() {
  local state="$1" dry="$2"
  shift 2
  : >"$CALLS"
  rm -f "$DISK_STATE"
  env PATH="$STUBS:$PATH" CALLS="$CALLS" DISK_STATE="$DISK_STATE" \
    COMPOSE_PROJECT="$PROJECT" STATE_DIR="$state" DRY_RUN="$dry" \
    DISK_CHECK_PATH="$TMP" CHECK_URL="https://www.simonrowe.dev" "$@" \
    bash "${MONITOR_SCRIPT:-$MONITOR}" 2>&1
}

# run_restart <state-dir> <dry-run> <phase> [VAR=value ...]
# dry-run "" means DRY_RUN unset, which is how restart-prod.sh reads "off".
run_restart() {
  local state="$1" dry="$2" phase="$3"
  shift 3
  : >"$CALLS"
  local unset_dry=()
  [[ -z "$dry" ]] && unset_dry=(-u DRY_RUN)
  env ${unset_dry[@]+"${unset_dry[@]}"} PATH="$STUBS:$PATH" CALLS="$CALLS" DISK_STATE="$DISK_STATE" \
    STATE_DIR="$state" ${dry:+DRY_RUN="$dry"} "$@" \
    bash "${RESTART_SCRIPT:-$RESTART}" "$phase" 2>&1
}

WARN_ARGS='image prune -af --filter until=72h'
CRIT_ARGS='image prune -af'
# What run_cmd prints under DRY_RUN, with or without the `timeout` wrapper (CI has
# coreutils timeout; a Mac without coreutils does not).
DRY_WARN_RE='(timeout [0-9]+ )?docker image prune -af --filter until=72h$'

now=$(date +%s)

echo "  monitor-prod.sh"

# ---------------------------------------------------------------------------
echo "  below the threshold: nothing is pruned"
# ---------------------------------------------------------------------------
out="$(run_monitor "$TMP/m-below" 0 FAKE_DISK_PERCENT=50)"
# Without this the "no prune" assertion would pass just as well if the disk
# layer had never run at all.
check "the disk layer ran, on the configured path" "grep -qx 'df -P $TMP' '$CALLS'"
check "no prune below DISK_WARN_PERCENT" "[[ \$(prune_calls) -eq 0 ]]"
check "and nothing logged about the disk" "! grep -q 'disk:' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  above the warn threshold: the keep-window prune, exactly once"
# ---------------------------------------------------------------------------
state="$TMP/m-warn"
out="$(run_monitor "$state" 0 FAKE_DISK_PERCENT=90 FAKE_DISK_AFTER=70)"
rc=$?
check "the tick still exits 0" "[[ $rc -eq 0 ]]"
check "docker received exactly '$WARN_ARGS'" "grep -qx '$WARN_ARGS' '$CALLS'"
check "exactly one prune" "[[ \$(prune_calls) -eq 1 ]]"
check "logged at WARN with the usage" "grep -q '\[WARN\] disk: 90% used' <<<\"\$out\""
check "the reclaimed-space summary is logged" "grep -q 'disk: Total reclaimed space: 1.5GB' <<<\"\$out\""
check "per-layer 'deleted:' lines are not" "! grep -q 'sha256:' <<<\"\$out\""
check "the usage after the prune is logged" "grep -q 'disk: 70% used after the prune' <<<\"\$out\""
check "the rate-limit stamp is written" "[[ -s '$state/disk_prune_warn.last' ]]"
check "the rest of the tick ran afterwards (layer 2 asked compose for ps)" \
  "grep -q '^compose .* ps' '$CALLS'"

# ---------------------------------------------------------------------------
echo "  the rate limit"
# ---------------------------------------------------------------------------
out="$(run_monitor "$state" 0 FAKE_DISK_PERCENT=90)"
check "a second tick inside DISK_PRUNE_INTERVAL does not prune" "[[ \$(prune_calls) -eq 0 ]]"
check "and says why, in one WARN line" \
  "[[ \$(grep -c 'disk:' <<<\"\$out\") -eq 1 ]] && grep -q 'rate-limited to one per 3600s' <<<\"\$out\""

echo "$((now - 4000))" >"$state/disk_prune_warn.last"
run_monitor "$state" 0 FAKE_DISK_PERCENT=90 >/dev/null
check "a stamp older than the interval allows the next prune" "grep -qx '$WARN_ARGS' '$CALLS'"

# ---------------------------------------------------------------------------
echo "  DRY_RUN"
# ---------------------------------------------------------------------------
state="$TMP/m-dry"
out="$(run_monitor "$state" 1 FAKE_DISK_PERCENT=90)"
check "DRY_RUN prints the exact prune" "grep -qE 'DRYRUN.*would run: $DRY_WARN_RE' <<<\"\$out\""
check "DRY_RUN never reaches docker image prune" "[[ \$(prune_calls) -eq 0 ]]"
check "DRY_RUN does not arm the rate limit" "[[ ! -e '$state/disk_prune_warn.last' ]]"

# ---------------------------------------------------------------------------
echo "  above the crit threshold: every unused image"
# ---------------------------------------------------------------------------
state="$TMP/m-crit"
mkdir -p "$state"
# A WARN prune a minute ago must not delay the aggressive one.
echo "$((now - 60))" >"$state/disk_prune_warn.last"
out="$(run_monitor "$state" 0 FAKE_DISK_PERCENT=97 FAKE_DISK_AFTER=60)"
check "docker received exactly '$CRIT_ARGS' (no until filter)" "grep -qx '$CRIT_ARGS' '$CALLS'"
check "and not the keep-window prune" "! grep -q 'until=' '$CALLS'"
check "logged at CRIT" "grep -q '\[CRIT\] disk: 97% used' <<<\"\$out\""
check "a recent WARN stamp does not block it" "[[ \$(prune_calls) -eq 1 ]]"
check "it has its own stamp" "[[ -s '$state/disk_prune_crit.last' ]]"
check "a prune that fixed it says so, without a second CRIT" \
  "grep -q '60% used after the prune' <<<\"\$out\" && ! grep -q 'Needs a human' <<<\"\$out\""

out="$(run_monitor "$TMP/m-crit-stuck" 0 FAKE_DISK_PERCENT=97 FAKE_DISK_AFTER=96)"
check "still critical after pruning everything: hand it to a human" \
  "grep -q '\[CRIT\] disk: still 96% used after the prune.*Needs a human' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  a deploy's rollback image is never pruned from under it"
# ---------------------------------------------------------------------------
out="$(run_monitor "$TMP/m-deploy" 0 FAKE_DISK_PERCENT=99 FAKE_DEPLOY_FLAG=1)"
check "no prune while the maintenance flag is up, even at 99%" "[[ \$(prune_calls) -eq 0 ]]"
check "it stood down instead" "grep -q 'Standing down' <<<\"\$out\""

state="$TMP/m-grace"
out="$(run_monitor "$state" 0 FAKE_DISK_PERCENT=90 FAKE_ROLLBACK_MTIME="$((now - 60))")"
check "no prune within DISK_PRUNE_DEPLOY_GRACE of a deploy's pull" "[[ \$(prune_calls) -eq 0 ]]"
check "the grace read rollback-images through nginx" \
  "grep -q 'exec $PROJECT-nginx-1 stat -c %Y /var/run/deploy-state/rollback-images' '$CALLS'"
check "it says why" "grep -q 'a deploy pulled images 6[0-9]s ago' <<<\"\$out\""
check "and does not burn the rate limit" "[[ ! -e '$state/disk_prune_warn.last' ]]"

out="$(run_monitor "$state" 0 FAKE_DISK_PERCENT=90 FAKE_ROLLBACK_MTIME="$((now - 7200))")"
check "a pull older than the grace no longer blocks it" "grep -qx '$WARN_ARGS' '$CALLS'"

# ---------------------------------------------------------------------------
echo "  failures stay failures of the disk layer alone"
# ---------------------------------------------------------------------------
out="$(run_monitor "$TMP/m-fail" 0 FAKE_DISK_PERCENT=90 FAKE_PRUNE_EXIT=1)"
rc=$?
check "a failing prune does not fail the tick" "[[ $rc -eq 0 ]]"
check "it is logged as an ERROR with docker's reason" \
  "grep -q '\[ERROR\] disk: image prune failed (exit 1)' <<<\"\$out\" && grep -q 'already running' <<<\"\$out\""
check "and the later layers still ran" "grep -q '^compose .* ps' '$CALLS'"

out="$(run_monitor "$TMP/m-nodf" 0 FAKE_DF_BROKEN=1)"
rc=$?
check "unreadable df: no prune, no crash" \
  "[[ $rc -eq 0 && \$(prune_calls) -eq 0 ]] && grep -q 'could not read usage' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  configuration"
# ---------------------------------------------------------------------------
run_monitor "$TMP/m-conf" 0 FAKE_DISK_PERCENT=60 DISK_WARN_PERCENT=50 IMAGE_PRUNE_KEEP=24h >/dev/null
check "DISK_WARN_PERCENT and IMAGE_PRUNE_KEEP are honoured" \
  "grep -qx 'image prune -af --filter until=24h' '$CALLS'"

# Docker prints one line per deleted layer. The summariser must drop them without
# a pathological slowdown - 20,000 lines is ~1.5MB, well past the 100k chars the
# repo asks unbounded-input parsing to be tested against.
start=$(date +%s)
out="$(run_monitor "$TMP/m-big" 0 FAKE_DISK_PERCENT=90 FAKE_PRUNE_LINES=20000)"
elapsed=$(( $(date +%s) - start ))
check "a 20,000-layer prune output is summarised to one line" \
  "[[ \$(grep -c 'disk: Total reclaimed space' <<<\"\$out\") -eq 1 ]] && ! grep -q 'sha256:' <<<\"\$out\""
check "and in bounded time (${elapsed}s)" "[[ $elapsed -lt 30 ]]"

echo "  restart-prod.sh"

# ---------------------------------------------------------------------------
echo "  verify-public: prunes on success only"
# ---------------------------------------------------------------------------
out="$(run_restart "$TMP/r-ok" 1 verify-public)"
rc=$?
check "a passing verify-public exits 0" "[[ $rc -eq 0 ]]"
check "and prunes with exactly the keep-window filter" "grep -qE 'DRY-RUN: $DRY_WARN_RE' <<<\"\$out\""
check "after the hostname checks, not before" \
  "[[ \$(grep -n 'api.simonrowe.dev' <<<\"\$out\" | head -1 | cut -d: -f1) -lt \$(grep -n 'image prune' <<<\"\$out\" | head -1 | cut -d: -f1) ]]"

out="$(run_restart "$TMP/r-fail" 1 verify-public DRY_RUN_HTTP_CODE=503)"
rc=$?
check "a failing verify-public still exits 1" "[[ $rc -eq 1 ]]"
check "and does NOT prune - the rollback it enters needs the old images" \
  "! grep -q 'image prune' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  no other deploy phase prunes"
# ---------------------------------------------------------------------------
state="$TMP/r-phases"
mkdir -p "$state"
printf 'backend\tsha256:aaa\n' >"$state/rollback-images"
pruned_in=""
for phase in maintenance-on pull recreate verify maintenance-off; do
  run_restart "$state" 1 "$phase" >"$TMP/phase.out"
  grep -q 'image prune' "$TMP/phase.out" && pruned_in="$pruned_in $phase"
done
printf 'backend\tsha256:aaa\n' >"$state/rollback-images"
run_restart "$state" 1 rollback >"$TMP/phase.out"
grep -q 'image prune' "$TMP/phase.out" && pruned_in="$pruned_in rollback"
check "only verify-public prunes (pruned in:${pruned_in:- none})" "[[ -z '$pruned_in' ]]"

# ---------------------------------------------------------------------------
echo "  never on the rollback path"
# ---------------------------------------------------------------------------
# DeployWorkflowImpl.handleFailure: rollback -> verify -> maintenance-off ->
# verify-public. That last verify-public passes on a good rollback and must not
# prune: the images it rolled back from are what an investigation needs.
state="$TMP/r-rollback"
mkdir -p "$state"
printf 'backend\tsha256:aaa\n' >"$state/rollback-images"
run_restart "$state" 1 rollback >/dev/null
check "rollback marks the deploy as rolled back" "[[ -e '$state/rollback-taken' ]]"
out="$(run_restart "$state" 1 verify-public)"
rc=$?
check "the rollback's verify-public passes" "[[ $rc -eq 0 ]]"
check "and does not prune" "! grep -q 'image prune' <<<\"\$out\""
check "and says why" "grep -q 'Not pruning images: this deploy rolled back' <<<\"\$out\""

rm -f "$state/rollback-images"
run_restart "$state" 1 rollback >/dev/null
check "a rollback with nothing recorded still marks it (it was attempted)" \
  "[[ -e '$state/rollback-taken' ]]"

run_restart "$state" 1 pull >/dev/null
check "the next deploy's pull clears the mark" "[[ ! -e '$state/rollback-taken' ]]"
out="$(run_restart "$state" 1 verify-public)"
check "so its verify-public prunes again" "grep -qE 'DRY-RUN: $DRY_WARN_RE' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  the prune cannot fail a deploy (stubbed docker, DRY_RUN off)"
# ---------------------------------------------------------------------------
assert_docker_is_stubbed
out="$(run_restart "$TMP/r-real" "" verify-public)"
rc=$?
check "verify-public exits 0" "[[ $rc -eq 0 ]]"
check "docker received exactly '$WARN_ARGS'" "grep -qx '$WARN_ARGS' '$CALLS'"
check "exactly once" "[[ \$(prune_calls) -eq 1 ]]"
check "the phase output carries the summary, not every layer" \
  "grep -q 'Total reclaimed space: 1.5GB' <<<\"\$out\" && ! grep -q 'sha256:' <<<\"\$out\""

out="$(run_restart "$TMP/r-real-fail" "" verify-public FAKE_PRUNE_EXIT=1)"
rc=$?
check "a FAILING prune still leaves verify-public at exit 0" "[[ $rc -eq 0 ]]"
check "and warns" "grep -q 'WARNING: the image prune failed' <<<\"\$out\""

if command -v timeout >/dev/null 2>&1; then
  start=$(date +%s)
  out="$(run_restart "$TMP/r-real-slow" "" verify-public FAKE_PRUNE_SLEEP=30 IMAGE_PRUNE_TIMEOUT=1)"
  rc=$?
  elapsed=$(( $(date +%s) - start ))
  # The phase timeout (30m) turns an overrun into a FAILED verify-public, and
  # that into a rollback of a deploy that worked.
  check "a hung prune is cut off by IMAGE_PRUNE_TIMEOUT (${elapsed}s) and the phase passes" \
    "[[ $rc -eq 0 && $elapsed -lt 20 ]]"
else
  echo "    skip: no timeout(1) on this machine; CI runs this case"
fi

# ---------------------------------------------------------------------------
echo "  all (the human path)"
# ---------------------------------------------------------------------------
out="$(run_restart "$TMP/r-all" 1 all)"
check "a verified 'all' prunes, after its success message" \
  "grep -qE 'DRY-RUN: $DRY_WARN_RE' <<<\"\$out\" && [[ \$(grep -n 'refreshed and verified' <<<\"\$out\" | cut -d: -f1) -lt \$(grep -n 'image prune' <<<\"\$out\" | cut -d: -f1) ]]"
out="$(run_restart "$TMP/r-all-fail" 1 all DRY_RUN_HTTP_CODE=502)"
rc=$?
check "an INCOMPLETE 'all' does not prune" "[[ $rc -eq 1 ]] && ! grep -q 'image prune' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  a missing lib degrades to no prune, never to a broken script"
# ---------------------------------------------------------------------------
# Under `set -e` a bare `source` of a missing file would fail every phase of every
# deploy, and every watchdog tick.
mkdir -p "$TMP/nolib/scripts"
cp "$RESTART" "$MONITOR" "$TMP/nolib/scripts/"
out="$(RESTART_SCRIPT="$TMP/nolib/scripts/restart-prod.sh" run_restart "$TMP/r-nolib" 1 verify-public)"
rc=$?
check "restart-prod.sh verify-public still passes" \
  "[[ $rc -eq 0 ]] && grep -q 'image prune unavailable' <<<\"\$out\""
out="$(MONITOR_SCRIPT="$TMP/nolib/scripts/monitor-prod.sh" run_monitor "$TMP/m-nolib" 0 FAKE_DISK_PERCENT=90)"
rc=$?
check "monitor-prod.sh still completes its tick" \
  "[[ $rc -eq 0 ]] && grep -q 'image prune unavailable' <<<\"\$out\" && grep -q '^compose .* ps' '$CALLS'"

# ---------------------------------------------------------------------------
echo
printf '  %d checks, %d failures\n' "$checks" "$failures"
[[ "$failures" -eq 0 ]]
