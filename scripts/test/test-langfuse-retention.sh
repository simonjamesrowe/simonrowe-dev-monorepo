#!/usr/bin/env bash
#
# Guards scripts/langfuse-retention.sh and the ClickHouse overlay it depends on
# (config/clickhouse/backup-disk.xml).
#
# WHY THIS EXISTS
#   On 2026-11-07 ClickHouse's own system log tables were 26GB of the Pi's disk and
#   Langfuse's data was 340MB. The overlay stops writing the worst of them and the
#   script drops what was already written and puts a TTL on Langfuse's tables.
#
#   Three ways it breaks quietly, each checked here:
#   - A `--` inside the overlay's XML comment. ClickHouse then refuses to start at all,
#     which took a real ClickHouse to find the first time.
#   - The script's list of removed tables drifting from the overlay's. A table the
#     overlay removes but the script does not drop stays on disk for ever; a table the
#     script drops but the overlay still writes comes back, and the final check fails.
#   - The script dropping something outside `system`, or changing anything on a dry run.
#
#   The ClickHouse behaviour itself (part-only TTL, the `_0` leftovers a TTL change
#   makes, the overlay loading) was checked against clickhouse-server:26.7.1.1315 with
#   Langfuse 3.212.0's migrations; see docs/runbooks/langfuse-observability.md.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

SCRIPT="$PROJECT_DIR/scripts/langfuse-retention.sh"
OVERLAY="$PROJECT_DIR/config/clickhouse/backup-disk.xml"
COMPOSE="$PROJECT_DIR/docker-compose.prod.yml"

failures=0
checks=0

pass() { checks=$((checks + 1)); echo "    ok: $1"; }
fail() { checks=$((checks + 1)); failures=$((failures + 1)); echo "    FAIL: $1"; }

check() {
  local description="$1" condition="$2"
  if eval "$condition"; then pass "$description"; else fail "$description"; fi
}

echo "  Subjects:"
echo "    $SCRIPT"
echo "    $OVERLAY"

[[ -f "$SCRIPT" ]] || { echo "    FAIL: missing $SCRIPT"; exit 1; }
[[ -f "$OVERLAY" ]] || { echo "    FAIL: missing $OVERLAY"; exit 1; }

# ---------------------------------------------------------------------------
# The overlay
# ---------------------------------------------------------------------------
check "the overlay is well-formed XML (ClickHouse will not start otherwise)" \
  "python3 -c 'import sys, xml.dom.minidom; xml.dom.minidom.parse(sys.argv[1])' \"\$OVERLAY\" 2>/dev/null"
check "the overlay sets the server log level to warning" \
  "grep -q '<level>warning</level>' \"\$OVERLAY\""
check "the overlay still allows /backups for the platform backup" \
  "grep -q '<allowed_path>/backups/</allowed_path>' \"\$OVERLAY\""

overlay_removed=$(grep -oE '<[a-z_]+ remove="1"/>' "$OVERLAY" | sed -E 's/<([a-z_]+) .*/\1/' | sort | tr '\n' ' ')
script_removed=$(sed -nE 's/^REMOVED_SYSTEM_TABLES="(.*)"$/\1/p' "$SCRIPT" | tr ' ' '\n' | sort | tr '\n' ' ')
check "the script drops exactly the tables the overlay removes ($overlay_removed)" \
  "[ -n \"\$overlay_removed\" ] && [ \"\$overlay_removed\" = \"\$script_removed\" ]"

# The overlay holds the log settings precisely so the service's mounts do not change.
check "langfuse-clickhouse mounts the overlay into config.d" \
  "grep -q 'config/clickhouse/backup-disk.xml:/etc/clickhouse-server/config.d/backup-disk.xml:ro' \"\$COMPOSE\""

check "EVENT_BUCKET matches LANGFUSE_S3_EVENT_UPLOAD_BUCKET" \
  "grep -q \"LANGFUSE_S3_EVENT_UPLOAD_BUCKET: \$(sed -nE 's/^EVENT_BUCKET=(.*)$/\1/p' \"\$SCRIPT\")\$\" \"\$COMPOSE\""
check "EVENT_PREFIX matches LANGFUSE_S3_EVENT_UPLOAD_PREFIX" \
  "grep -q \"LANGFUSE_S3_EVENT_UPLOAD_PREFIX: \$(sed -nE 's/^EVENT_PREFIX=(.*)$/\1/p' \"\$SCRIPT\")\$\" \"\$COMPOSE\""

# ---------------------------------------------------------------------------
# A fake docker
# ---------------------------------------------------------------------------
# Answers each SQL statement the script sends by its shape, records everything, and
# reads the MinIO policy from stdin. STUB_STILL_WRITTEN is what the final check sees.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"
cat >"$WORK/bin/docker" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$DOCKER_STUB_LOG"
case "${1:-}" in
  ps)
    for arg in "$@"; do
      case "$arg" in
        label=com.docker.compose.service=*) printf 'stub-%s-1\n' "${arg#label=com.docker.compose.service=}" ;;
      esac
    done
    exit 0 ;;
  exec)
    if [ "${2:-}" = "-i" ]; then
      printf 'ILM %s\n' "$(cat)" >> "$DOCKER_STUB_LOG"
      exit 0
    fi
    sql="${!#}"
    case "$sql" in
      *"OR match(name"*) printf 'query_log_0\ntext_log\n' ;;
      *"match(name"*) printf '%s' "${STUB_STILL_WRITTEN:-}" ;;
      *"SELECT count()"*) echo 1 ;;
      *formatReadableSize*) echo "1.00 GiB" ;;
    esac
    exit 0 ;;
esac
exit 0
STUB
chmod +x "$WORK/bin/docker"

run() {
  : >"$WORK/docker.log"
  env PATH="$WORK/bin:$PATH" DOCKER_STUB_LOG="$WORK/docker.log" COMPOSE_PROJECT=stubstack \
    "$@" bash "$SCRIPT" ${ARGS:-} >"$WORK/out" 2>&1
}

# ---------------------------------------------------------------------------
# Dry run changes nothing
# ---------------------------------------------------------------------------
ARGS=--dry-run run
check "a dry run exits 0" "[ $? -eq 0 ]"
# The whole log, not lines starting `exec`: the sh -c script spans lines, so the SQL
# argument lands on a line of its own.
check "a dry run sends no ALTER or DROP to ClickHouse" \
  "! grep -qE 'ALTER|DROP' \"\$WORK/docker.log\""
check "a dry run imports no MinIO lifecycle" "! grep -q '^ILM ' \"\$WORK/docker.log\""
check "a dry run still reports what it would drop" \
  "grep -q 'would run: DROP TABLE IF EXISTS system.text_log SYNC' \"\$WORK/out\""

# ---------------------------------------------------------------------------
# A real run
# ---------------------------------------------------------------------------
ARGS= run RETENTION_DAYS=45
status=$?
check "a real run exits 0 when the overlay is loaded" "[ $status -eq 0 ]"
check "it resolves ClickHouse by compose label" \
  "grep -q 'label=com.docker.compose.service=langfuse-clickhouse' \"\$WORK/docker.log\""
for table in traces:timestamp observations:start_time scores:timestamp; do
  check "${table%%:*} gets a part-only TTL on ${table#*:}, at RETENTION_DAYS" \
    "grep -q 'ALTER TABLE default.${table%%:*} MODIFY SETTING ttl_only_drop_parts = 1, materialize_ttl_recalculate_only = 1' \"\$WORK/docker.log\" \
       && grep -q 'ALTER TABLE default.${table%%:*} MODIFY TTL toDateTime(${table#*:}) + toIntervalDay(45)' \"\$WORK/docker.log\""
done
check "event_log gets a row TTL, not a part-only one" \
  "grep -q 'ALTER TABLE default.event_log MODIFY TTL toDateTime(created_at) + toIntervalDay(45)' \"\$WORK/docker.log\" \
     && ! grep -q 'ALTER TABLE default.event_log MODIFY SETTING' \"\$WORK/docker.log\""
check "it drops the stale system tables it found" \
  "grep -q 'DROP TABLE IF EXISTS system.query_log_0 SYNC' \"\$WORK/docker.log\" \
     && grep -q 'DROP TABLE IF EXISTS system.text_log SYNC' \"\$WORK/docker.log\""
check "it never drops anything outside the system database" \
  "! grep -E 'DROP TABLE' \"\$WORK/docker.log\" | grep -vq 'DROP TABLE IF EXISTS system\\.'"
check "the MinIO rule expires only events/, after RETENTION_DAYS" \
  "grep -q '^ILM .*\"Prefix\":\"events/\"},\"Expiration\":{\"Days\":45}' \"\$WORK/docker.log\" \
     && ! grep -q '^ILM .*media/' \"\$WORK/docker.log\""
check "the ClickHouse password never reaches the host command line" \
  "grep -q -- '--password \"\\\${CLICKHOUSE_PASSWORD' \"\$WORK/docker.log\" \
     && ! grep -qE -- '--password +([^\" ]|\"[^\$])' \"\$WORK/docker.log\""

# ---------------------------------------------------------------------------
# The final check is fatal
# ---------------------------------------------------------------------------
ARGS= run STUB_STILL_WRITTEN=text_log
status=$?
check "a run exits non-zero when ClickHouse still writes a removed table" "[ $status -ne 0 ]"
check "and says to recreate the container" "grep -q 'force-recreate --no-deps langfuse-clickhouse' \"\$WORK/out\""

ARGS= run RETENTION_DAYS=2
check "RETENTION_DAYS below 3 is refused before anything runs" \
  "[ $? -ne 0 ] && ! grep -q '^exec' \"\$WORK/docker.log\""
ARGS= run RETENTION_DAYS=30d
check "a non-numeric RETENTION_DAYS is refused" "[ $? -ne 0 ]"

echo
echo "  checks: $checks  failures: $failures"
[[ "$failures" -eq 0 ]]
