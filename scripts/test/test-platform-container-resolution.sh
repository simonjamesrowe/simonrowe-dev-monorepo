#!/usr/bin/env bash
#
# Asserts that backup-platform.sh and restore-platform.sh address the datastores by
# their REAL container names, resolved from compose's own labels.
#
# WHY THIS EXISTS
#   Both scripts shipped with:
#
#     POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-langfuse-db}"
#
#   `langfuse-db` is a compose SERVICE name. `docker exec` addresses a container by
#   name or id and knows nothing about services, and compose calls the container
#   `<project>-<service>-<index>` - so every one of those calls failed with "No such
#   container", inside the deployer and on the host alike. That is what the nightly
#   platform backup did every night once test-platform-backup-prereqs.sh's missing
#   python3 was fixed and it could finally reach the docker calls:
#
#     [backup-platform] WARNING: could not sweep /backups (continuing)   (SIM-46)
#     [backup-platform] ERROR: pg_dumpall --roles-only failed            (SIM-43)
#     Activity failure. activityType=Capture                             (SIM-34)
#
#   Neither message mentions a container, which is why it read as a ClickHouse
#   permissions problem and then a Postgres one. The same defect sat unnoticed in
#   restore-platform.sh, whose OWN wait_for_health() has always formatted the full
#   `<project>-<service>-1` name - the two halves of one file disagreed.
#
#   No unit test could catch it: both scripts mock nothing, and the failure only
#   appears when a real docker daemon is asked for a container that does not exist.
#   This test supplies a fake `docker` on PATH and reads what the script asks it for.
#
# A shell test rather than a Java one because the subjects are host scripts owned by
# neither Gradle module - as with test-platform-backup-prereqs.sh, which it sits beside.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

BACKUP_SCRIPT="$PROJECT_DIR/scripts/backup-platform.sh"
RESTORE_SCRIPT="$PROJECT_DIR/scripts/restore-platform.sh"

failures=0
checks=0

pass() { checks=$((checks + 1)); echo "    ok: $1"; }
fail() { checks=$((checks + 1)); failures=$((failures + 1)); echo "    FAIL: $1"; }

check() {
  local description="$1" condition="$2"
  if eval "$condition"; then pass "$description"; else fail "$description"; fi
}

echo "  Subjects:"
echo "    $BACKUP_SCRIPT"
echo "    $RESTORE_SCRIPT"

[[ -f "$BACKUP_SCRIPT" ]] || { echo "    FAIL: missing $BACKUP_SCRIPT"; exit 1; }
[[ -f "$RESTORE_SCRIPT" ]] || { echo "    FAIL: missing $RESTORE_SCRIPT"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------
# A fake docker
# ---------------------------------------------------------------------------
# `ps` answers the compose-label query with a name that is DELIBERATELY NOT
# `<project>-<service>-1`. The scripts format that name themselves as a dry-run
# fallback, so a stub returning it would pass whether or not the label query is
# used at all - the assertion has to be able to tell the two apart.
#
# Everything else is recorded and succeeds, so a dry run cannot reach the daemon.
mkdir -p "$WORK/bin"
cat >"$WORK/bin/docker" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${DOCKER_STUB_LOG:-/dev/null}"
if [ "${1:-}" = "ps" ]; then
  if [ "${DOCKER_STUB_PS_EMPTY:-0}" = "1" ]; then
    exit 0
  fi
  service=""
  for arg in "$@"; do
    case "$arg" in
      label=com.docker.compose.service=*) service="${arg#label=com.docker.compose.service=}" ;;
    esac
  done
  [ -n "$service" ] && printf 'stubstack-%s-7\n' "$service"
  exit 0
fi
exit 0
STUB
chmod +x "$WORK/bin/docker"

# A throwaway env file. The real .env is never read by this test.
cat >"$WORK/env" <<'ENVFILE'
LANGFUSE_DB_USER=stubuser
LANGFUSE_DB_PASSWORD=stubpassword
ENVFILE

run_backup_dry() {
  env PATH="$WORK/bin:$PATH" \
      DOCKER_STUB_LOG="$WORK/docker.log" \
      ENV_FILE="$WORK/env" \
      COMPOSE_PROJECT_NAME=stubstack \
      "$@" \
      bash "$BACKUP_SCRIPT" --dry-run 2>&1
}

# ---------------------------------------------------------------------------
# backup-platform.sh resolves from the labels
# ---------------------------------------------------------------------------
: >"$WORK/docker.log"
output="$(run_backup_dry)"

check "backup names the resolved Postgres container, not the service" \
  "grep -q 'stubstack-langfuse-db-7' <<<\"\$output\""
check "backup names the resolved ClickHouse container, not the service" \
  "grep -q 'stubstack-langfuse-clickhouse-7' <<<\"\$output\""

# The bug, stated as the assertion. `docker exec ... langfuse-db pg_dumpall` is
# precisely the line that failed in production every night.
check "backup never execs a bare compose service name" \
  "! grep -qE 'docker exec[^>]* (langfuse-db|langfuse-clickhouse)( |\$)' <<<\"\$output\""
check "backup never copies from a bare compose service name" \
  "! grep -qE 'langfuse-clickhouse:/' <<<\"\$output\""

# It must have ASKED, rather than guessed. Without this the two checks above pass
# on the dry-run fallback path even if the label query were deleted.
check "backup queries docker ps by compose project and service label" \
  "grep -q 'label=com.docker.compose.project=stubstack' \"\$WORK/docker.log\" \
     && grep -q 'label=com.docker.compose.service=langfuse-db' \"\$WORK/docker.log\""

# ---------------------------------------------------------------------------
# An explicit override still wins
# ---------------------------------------------------------------------------
output="$(run_backup_dry POSTGRES_CONTAINER=chosen-by-hand)"
check "POSTGRES_CONTAINER overrides resolution" \
  "grep -q 'chosen-by-hand' <<<\"\$output\" && ! grep -q 'stubstack-langfuse-db-7' <<<\"\$output\""

# ---------------------------------------------------------------------------
# A real run refuses rather than exec'ing into nothing
# ---------------------------------------------------------------------------
# Not a dry run: the fallback is deliberately dry-run-only, because guessing a
# container name in a real capture is how you get "No such container" attributed
# to Postgres. The stub means no daemon is touched even so.
: >"$WORK/docker.log"
status=0
output="$(env PATH="$WORK/bin:$PATH" \
      DOCKER_STUB_LOG="$WORK/docker.log" \
      DOCKER_STUB_PS_EMPTY=1 \
      ENV_FILE="$WORK/env" \
      COMPOSE_PROJECT_NAME=stubstack \
      bash "$BACKUP_SCRIPT" --no-upload --out-dir "$WORK/out" 2>&1)" || status=$?

check "an unresolvable service fails the run" "[[ \$status -ne 0 ]]"
check "and says which compose service and project it could not find" \
  "grep -q \"compose service 'langfuse-db'\" <<<\"\$output\" && grep -q \"project 'stubstack'\" <<<\"\$output\""
check "and never reaches a docker exec" \
  "! grep -q '^exec' \"\$WORK/docker.log\""

# ---------------------------------------------------------------------------
# restore-platform.sh, the matched pair
# ---------------------------------------------------------------------------
# Structural rather than behavioural: a restore dry run needs a real archive with a
# manifest and matching secret fingerprints, which is a great deal of scaffolding to
# assert one variable. The defect here is a default value, and a default value is
# something a grep can see.
for script in "$BACKUP_SCRIPT" "$RESTORE_SCRIPT"; do
  name="$(basename "$script")"
  check "$name does not default a datastore container to a bare service name" \
    "! grep -qE '^(POSTGRES|CLICKHOUSE)_CONTAINER=\"\\\$\\{(POSTGRES|CLICKHOUSE)_CONTAINER:-[a-z]' \"\$script\""
  check "$name defines resolve_containers()" \
    "grep -q '^resolve_containers()' \"\$script\""
  # Called, not merely defined. The whole feature was dead code away from this.
  check "$name calls resolve_containers()" \
    "grep -qE '^[[:space:]]+resolve_containers\$' \"\$script\""
  check "$name resolves via the compose service label" \
    "grep -q 'label=com.docker.compose.service=' \"\$script\""
done

# restore's wait_for_health() formats `<project>-<service>-1` from COMPOSE_PROJECT and
# has always been right. If the two ever disagree about the project, the health poll
# watches a different stack from the one being restored.
check "restore-platform.sh resolves and polls health under the same project variable" \
  "grep -q 'COMPOSE_PROJECT=' \"\$RESTORE_SCRIPT\" \
     && grep -q 'container=\"\${COMPOSE_PROJECT}-\${service}-1\"' \"\$RESTORE_SCRIPT\""

echo
echo "  checks: $checks  failures: $failures"
[[ "$failures" -eq 0 ]]
