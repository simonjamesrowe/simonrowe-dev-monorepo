#!/usr/bin/env bash
#
# Coverage for monitor-prod.sh's MONITOR_ROLE switch.
#
# The software factory moved off the Pi onto a NUC (docs/runbooks/factory-host.md),
# and the NUC needs the same watchdog: Docker never restarts an `unhealthy`
# container, and a container left in `created` stays there until something runs
# `up -d`. But the NUC serves no public hostname and has no nginx or pinggy, so the
# site layers must not run there. MONITOR_ROLE=factory is that switch, and the
# default (site) must leave the Pi exactly as it was.
#
# `docker`, `curl`, `getent` and `df` are stubbed on PATH. Every docker call is
# appended to $CALLS so the exact arguments can be asserted. Cases that need to see
# the compose command itself run with DRY_RUN=0 (svc_restart discards run_cmd's
# DRYRUN line), and only after proving `docker` resolves to the stub.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCRIPT="$PROJECT_DIR/scripts/monitor-prod.sh"

export DRY_RUN=1

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

STUBS="$TMP/stubs"
CALLS="$TMP/calls"
mkdir -p "$STUBS"
FACTORY_FILE="$PROJECT_DIR/docker-compose.factory.yml"
SITE_FILE="$PROJECT_DIR/docker-compose.prod.yml"

# curl: every call is recorded. The site check passes unless FAKE_SITE_DOWN=1;
# an endpoint probe answers 200.
cat >"$STUBS/curl" <<'EOF'
#!/usr/bin/env bash
echo "curl $*" >> "$CALLS"
for arg in "$@"; do
  [[ "$arg" == "%{http_code}" ]] && { printf '200'; exit 0; }
done
[[ "${FAKE_SITE_DOWN:-0}" == 1 ]] && exit 7
exit 0
EOF

cat >"$STUBS/getent" <<'EOF'
#!/usr/bin/env bash
echo "140.82.121.4 github.com"
EOF

cat >"$STUBS/df" <<'EOF'
#!/usr/bin/env bash
echo "Filesystem 1024-blocks Used Available Capacity Mounted on"
echo "/dev/sda2 100 50 50 ${FAKE_DISK_PERCENT:-50}% /"
EOF

# docker:
#   exec <c> test -f ...flag   -> FAKE_FLAG=1 means a deploy is in progress
#   exec <c> stat ...          -> FAKE_MTIME, or exit FAKE_STAT_EXIT (default 1)
#   exec <c> getent ...        -> fails for services in FAKE_DNS_BROKEN
#   ps --format                -> FAKE_RUNNING, as <project>-<svc>-1
#   compose ... ps             -> FAKE_PS rows
#   image prune                -> a reclaimed-space summary
cat >"$STUBS/docker" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$CALLS"
case "$1" in
  exec)
    container="$2"
    case "$3" in
      test) [[ "${FAKE_FLAG:-0}" == 1 ]] && exit 0; exit 1 ;;
      stat) [[ -n "${FAKE_MTIME:-}" ]] && { echo "$FAKE_MTIME"; exit 0; }; exit "${FAKE_STAT_EXIT:-1}" ;;
    esac
    svc="${container#"$FAKE_PROJECT"-}"
    svc="${svc%-1}"
    [[ " ${FAKE_DNS_BROKEN:-} " == *" $svc "* ]] && exit 2
    exit 0
    ;;
  ps) for s in ${FAKE_RUNNING:-}; do echo "$FAKE_PROJECT-$s-1"; done ;;
  logs) echo "A tunnel with the same token is already active" ;;
  compose) [[ " $* " == *" ps "* ]] && for row in ${FAKE_PS:-}; do echo "$row"; done ;;
  image) echo "Total reclaimed space: 1GB" ;;
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

# run_tick <role> <state-dir> <dry> [VAR=value ...]. Each call starts a fresh $CALLS.
run_tick() {
  local role="$1" state_dir="$2" dry="$3"
  shift 3
  : > "$CALLS"
  local project="simonrowe-factory"
  [[ "$role" == site ]] && project="simonrowe-dev-monorepo"
  env PATH="$STUBS:$PATH" CALLS="$CALLS" FAKE_PROJECT="$project" STATE_DIR="$state_dir" \
    DRY_RUN="$dry" DISK_CHECK_PATH="$TMP" MONITOR_ROLE="$role" "$@" bash "$SCRIPT" 2>&1
}

resolved="$(PATH="$STUBS:$PATH" command -v docker)"
if [[ "$resolved" != "$STUBS/docker" ]]; then
  echo "    FAIL: docker resolves to $resolved, not the stub - refusing to run DRY_RUN=0 cases"
  exit 1
fi

HEALTHY="software-factory|running|healthy deployer|running|healthy mongodb|running|healthy deploy-state-init|exited|"

# ---------------------------------------------------------------------------
echo "  factory role: the site layers do not run"
# ---------------------------------------------------------------------------
out="$(run_tick factory "$TMP/f-quiet" 0 FAKE_SITE_DOWN=1 FAKE_PS="$HEALTHY" \
  FAKE_RUNNING="software-factory deployer alloy mongodb backend")"
rc=$?
check "the tick exits 0" "[[ $rc -eq 0 ]]"
check "no curl at all: no www check, no public hostname" "! grep -q '^curl' '$CALLS'"
check "nginx is never asked anything" "! grep -q 'nginx' '$CALLS'"
check "pinggy's log is never read and pinggy never restarted" "! grep -q 'pinggy' '$CALLS'"
check "a healthy stack logs nothing" "[[ -z \"\$out\" ]]"
check "layer 2 asks compose for the factory project explicitly" \
  "grep -qxF 'compose -p simonrowe-factory -f $FACTORY_FILE ps --all --format {{.Service}}|{{.State}}|{{.Health}}' '$CALLS'"
check "the maintenance flag is read through the deployer" \
  "grep -qxF 'exec simonrowe-factory-deployer-1 test -f /var/run/deploy-state/maintenance.on' '$CALLS'"
check "a completed deploy-state-init is not a fault" "! grep -q ' up -d' '$CALLS'"
check "DNS is probed in software-factory, deployer and alloy" \
  "grep -qxF 'exec simonrowe-factory-software-factory-1 getent hosts github.com' '$CALLS' \
   && grep -qxF 'exec simonrowe-factory-deployer-1 getent hosts github.com' '$CALLS' \
   && grep -qxF 'exec simonrowe-factory-alloy-1 getent hosts github.com' '$CALLS'"
check "and nowhere else, even if a backend container happens to be running" \
  "[[ \$(grep -c ' getent hosts ' '$CALLS') -eq 3 ]]"

# ---------------------------------------------------------------------------
echo "  factory role: an unhealthy container is still restarted"
# ---------------------------------------------------------------------------
state="$TMP/f-unhealthy"
UNHEALTHY="software-factory|running|unhealthy deployer|running|healthy"
for tick in 1 2; do run_tick factory "$state" 0 FAKE_PS="$UNHEALTHY" >/dev/null; done
check "not before the threshold" "! grep -q ' restart ' '$CALLS'"
out="$(run_tick factory "$state" 0 FAKE_PS="$UNHEALTHY")"
check "tick 3 restarts it" "grep -q 'software-factory: restarting (docker reports unhealthy)' <<<\"\$out\""
check "with exactly the factory compose command" \
  "grep -qxF 'compose -p simonrowe-factory -f $FACTORY_FILE restart software-factory' '$CALLS'"

# ---------------------------------------------------------------------------
echo "  factory role: created/exited containers are reconciled"
# ---------------------------------------------------------------------------
out="$(run_tick factory "$TMP/f-exited" 0 FAKE_PS="software-factory|created| deployer|exited| deploy-state-init|exited|")"
check "it says which container" "grep -q \"software-factory: container state 'created'\" <<<\"\$out\""
check "it runs up -d on the factory project, naming every service but the deployer" \
  "grep -qxF 'compose -p simonrowe-factory -f $FACTORY_FILE up -d alloy deploy-state-init mongodb page-reader software-factory' '$CALLS'"
# A deploy's pull re-tags software-factory:latest, which the deployer shares, and no
# flag stands this watchdog down during a factory host deploy: a bare `up -d` here
# would recreate the deployer in the middle of the deploy it is running.
check "it never runs a bare up -d that could recreate the deployer" \
  "! grep -qxF 'compose -p simonrowe-factory -f $FACTORY_FILE up -d' '$CALLS'"
check "it starts the deployer without recreating it" \
  "grep -qxF 'compose -p simonrowe-factory -f $FACTORY_FILE start deployer' '$CALLS'"
check "deploy-state-init is the factory one-shot and is not reported" \
  "! grep -q 'deploy-state-init' <<<\"\$out\""

out="$(run_tick factory "$TMP/f-exited-dry" 1 FAKE_PS="deployer|exited|")"
# run_cmd's DRYRUN line goes to /dev/null on this path, so assert the log and
# the absence of the call instead.
check "under DRY_RUN it reports the reconcile but runs no up -d" \
  "grep -q 'Reconciling compose stack to start non-running containers' <<<\"\$out\" \
   && ! grep -q ' up -d' '$CALLS'"

# ---------------------------------------------------------------------------
echo "  factory role: DNS layer restarts an internet-facing container"
# ---------------------------------------------------------------------------
state="$TMP/f-dns"
for tick in 1 2; do
  run_tick factory "$state" 0 FAKE_PS="$HEALTHY" FAKE_RUNNING="software-factory deployer" \
    FAKE_DNS_BROKEN="software-factory" >/dev/null
done
out="$(run_tick factory "$state" 0 FAKE_PS="$HEALTHY" FAKE_RUNNING="software-factory deployer" \
  FAKE_DNS_BROKEN="software-factory")"
check "software-factory is restarted on the third failing tick" \
  "grep -qxF 'compose -p simonrowe-factory -f $FACTORY_FILE restart software-factory' '$CALLS'"

# ---------------------------------------------------------------------------
echo "  factory role: the deploy flag and the prune grace go through the deployer"
# ---------------------------------------------------------------------------
out="$(run_tick factory "$TMP/f-flag" 0 FAKE_FLAG=1 FAKE_PS="deployer|exited|")"
check "a set flag stands the watchdog down" "grep -q 'Standing down' <<<\"\$out\" && ! grep -q 'compose' '$CALLS'"

now=$(date +%s)
out="$(run_tick factory "$TMP/f-grace" 0 FAKE_DISK_PERCENT=90 FAKE_MTIME="$((now - 60))")"
check "the grace reads rollback-images through the deployer" \
  "grep -qxF 'exec simonrowe-factory-deployer-1 stat -c %Y /var/run/deploy-state/rollback-images' '$CALLS'"
check "and does not prune within it" "! grep -q '^image prune' '$CALLS' && grep -q 'a deploy pulled images' <<<\"\$out\""

out="$(run_tick factory "$TMP/f-nograce" 0 FAKE_DISK_PERCENT=90)"
check "no rollback-images: says the grace was not applied, and why" \
  "grep -q 'deploy grace not applied - no rollback-images readable through simonrowe-factory-deployer-1 (exit 1' <<<\"\$out\""
check "and prunes anyway" "grep -q '^image prune -af --filter until=72h' '$CALLS'"

out="$(run_tick factory "$TMP/f-nostat" 0 FAKE_DISK_PERCENT=90 FAKE_STAT_EXIT=127)"
check "a deployer image without stat (127) is named as such, not as 'no deploy'" \
  "grep -q 'stat is not runnable in simonrowe-factory-deployer-1 (exit 127)' <<<\"\$out\""

out="$(run_tick factory "$TMP/f-crit" 0 FAKE_DISK_PERCENT=97)"
check "a disk still full after pruning names the factory's growers, not ClickHouse" \
  "grep -q 'factory-mongo-data' <<<\"\$out\" && ! grep -q 'clickhouse' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  overrides and validation"
# ---------------------------------------------------------------------------
run_tick factory "$TMP/f-override" 0 COMPOSE_FILE=/srv/x/docker-compose.factory.yml \
  COMPOSE_PROJECT=other FAKE_PS="" >/dev/null
check "COMPOSE_FILE and COMPOSE_PROJECT override the role defaults" \
  "grep -qF 'compose -p other -f /srv/x/docker-compose.factory.yml ps' '$CALLS' \
   && grep -qF 'exec other-deployer-1 test' '$CALLS'"

out="$(run_tick nonsense "$TMP/f-bad" 0)"
rc=$?
check "an unknown role exits 2" "[[ $rc -eq 2 ]]"
check "and names the allowed values" "grep -q \"MONITOR_ROLE must be 'site' or 'factory'\" <<<\"\$out\""
check "having touched nothing" "[[ ! -s '$CALLS' ]]"

# ---------------------------------------------------------------------------
echo "  site role (the default) is unchanged"
# ---------------------------------------------------------------------------
: > "$CALLS"
out="$(env -u MONITOR_ROLE PATH="$STUBS:$PATH" CALLS="$CALLS" FAKE_PROJECT=simonrowe-dev-monorepo \
  STATE_DIR="$TMP/s-default" DRY_RUN=0 DISK_CHECK_PATH="$TMP" \
  FAKE_PS="backend|running|healthy uploads-init|exited|" FAKE_RUNNING="pinggy" bash "$SCRIPT" 2>&1)"
check "with MONITOR_ROLE unset it checks www" "grep -qF 'curl -sf -o /dev/null -m 10 https://www.simonrowe.dev' '$CALLS'"
check "and every public hostname" "[[ \$(grep -c '^curl -s -o /dev/null -w' '$CALLS') -eq 9 ]]"
check "reads the flag through nginx" \
  "grep -qxF 'exec simonrowe-dev-monorepo-nginx-1 test -f /var/run/deploy-state/maintenance.on' '$CALLS'"
check "asks compose with no -p, as it always has" \
  "grep -qxF 'compose -f $SITE_FILE ps --all --format {{.Service}}|{{.State}}|{{.Health}}' '$CALLS'"
check "keeps the Pi's one-shots (uploads-init exited is fine)" "! grep -q ' up -d' '$CALLS'"
check "and probes pinggy's DNS" "grep -qxF 'exec simonrowe-dev-monorepo-pinggy-1 getent hosts github.com' '$CALLS'"

state="$TMP/s-down"
for tick in 1 2; do run_tick site "$state" 0 FAKE_SITE_DOWN=1 >/dev/null; done
out="$(run_tick site "$state" 0 FAKE_SITE_DOWN=1)"
check "site role still handles a site outage (pinggy token conflict path)" \
  "grep -q 'pinggy: the tunnel token is held by another session' <<<\"\$out\""

echo
printf '  %d checks, %d failures\n' "$checks" "$failures"
[[ "$failures" -eq 0 ]]
