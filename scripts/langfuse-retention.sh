#!/usr/bin/env bash
#
# Puts a retention limit on Langfuse's data, and clears out the ClickHouse system log
# tables that config/clickhouse/backup-disk.xml stops writing.
#
# WHY THIS EXISTS
#   Nothing in the stack ever deleted a trace, and ClickHouse kept its own diagnostics
#   for ever. On 2026-11-07 the ClickHouse volume held 27GB, of which Langfuse's data was
#   about 340MB: the rest was system.text_log, trace_log, metric_log and friends. Langfuse's
#   own data-retention feature is Enterprise-only, so the limit is set in ClickHouse
#   directly, as a table TTL, and in MinIO, as a lifecycle rule on the raw event uploads.
#
#   A TTL is part of the table definition, so this only has to run once per data
#   directory. A platform restore (RESTORE DATABASE) brings the TTL back with the table.
#   Re-running it is harmless; run it again after changing RETENTION_DAYS.
#
# WHAT IT DOES
#   1. traces, observations, scores: TTL on the event time, with ttl_only_drop_parts so
#      ClickHouse drops whole expired parts instead of rewriting them. They are
#      partitioned by month, so a part expires at most a month after its newest row.
#      materialize_ttl_recalculate_only stops the ALTER rewriting every existing part:
#      it recalculates their TTL metadata, and parts already wholly expired go at once.
#   2. event_log, blob_storage_file_log: a row TTL on created_at. They are not
#      partitioned, so a part-only TTL would almost never find a whole part to drop.
#      Datasets (dataset_run_items*) are left alone, as Langfuse's own retention does.
#   3. Drops the system tables the overlay removed, and the `<name>_N` copies ClickHouse
#      leaves behind when an overlay changes a system table's definition.
#   4. Expires MinIO objects under events/ (raw ingestion payloads) after the same
#      period. Never media/: Langfuse's docs say media must not get a lifecycle rule.
#   5. Checks that the overlay is loaded and exits non-zero if not.
#
# WHERE THIS RUNS
#   On the Pi, from the deploy directory, after the overlay has been loaded:
#
#     docker compose -f docker-compose.prod.yml up -d --force-recreate --no-deps langfuse-clickhouse
#     ./scripts/langfuse-retention.sh --dry-run
#     ./scripts/langfuse-retention.sh
#
# USAGE
#   langfuse-retention.sh [--dry-run]
#   RETENTION_DAYS=30 (default), between 3 and 3650
#   COMPOSE_PROJECT=simonrowe-dev-monorepo (default)
set -euo pipefail

RETENTION_DAYS=${RETENTION_DAYS:-30}
COMPOSE_PROJECT=${COMPOSE_PROJECT:-simonrowe-dev-monorepo}
DRY_RUN=0

# Match LANGFUSE_S3_EVENT_UPLOAD_BUCKET and LANGFUSE_S3_EVENT_UPLOAD_PREFIX in
# docker-compose.prod.yml. scripts/test/test-langfuse-retention.sh keeps them in step.
EVENT_BUCKET=langfuse
EVENT_PREFIX=events/

# Langfuse tables partitioned by month, and the column each one's TTL reads.
PARTITIONED_TABLES="traces:timestamp observations:start_time scores:timestamp"
# Unpartitioned tables that get a row TTL.
ROW_TTL_TABLES="event_log:created_at blob_storage_file_log:created_at"
# Must match the `remove="1"` entries in config/clickhouse/backup-disk.xml.
REMOVED_SYSTEM_TABLES="trace_log text_log opentelemetry_span_log asynchronous_metric_log metric_log latency_log"

log()  { printf '[langfuse-retention] %s\n' "$*"; }
die()  { printf '[langfuse-retention] ERROR: %s\n' "$*" >&2; exit 1; }

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    *) die "unknown argument: $arg" ;;
  esac
done

case "$RETENTION_DAYS" in
  ''|*[!0-9]*) die "RETENTION_DAYS must be a whole number of days, got '$RETENTION_DAYS'" ;;
esac
if [ "$RETENTION_DAYS" -lt 3 ] || [ "$RETENTION_DAYS" -gt 3650 ]; then
  die "RETENTION_DAYS must be between 3 and 3650, got $RETENTION_DAYS"
fi

# By compose label, never by formatted name: see test-platform-container-resolution.sh.
compose_container() {
  docker ps --format '{{.Names}}' \
    --filter "label=com.docker.compose.project=${COMPOSE_PROJECT}" \
    --filter "label=com.docker.compose.service=$1" \
    2>/dev/null | awk 'NR==1{print}'
}

CLICKHOUSE=$(compose_container langfuse-clickhouse)
[ -n "$CLICKHOUSE" ] || die "no running langfuse-clickhouse container in project $COMPOSE_PROJECT"
MINIO=$(compose_container langfuse-minio)
[ -n "$MINIO" ] || die "no running langfuse-minio container in project $COMPOSE_PROJECT"

# The password stays inside the container: it is read from the container's own
# environment, never passed on this host's command line.
query() {
  docker exec "$CLICKHOUSE" sh -c \
    'clickhouse-client --user "${CLICKHOUSE_USER:-clickhouse}" \
       --password "${CLICKHOUSE_PASSWORD:-}" --query "$1"' \
    clickhouse-query "$1" </dev/null
}

# Statements that change something. Reads always run, so a dry run reports real state.
change() {
  if [ "$DRY_RUN" = 1 ]; then
    log "would run: $1"
  else
    log "running: $1"
    query "$1"
  fi
}

table_exists() {
  [ "$(query "SELECT count() FROM system.tables WHERE database = '$1' AND name = '$2'")" = 1 ]
}

disk_used() {
  query "SELECT formatReadableSize(sum(bytes_on_disk)) FROM system.parts WHERE active"
}

log "ClickHouse parts on disk before: $(disk_used)"

for entry in $PARTITIONED_TABLES; do
  table=${entry%%:*} column=${entry#*:}
  if ! table_exists default "$table"; then
    log "default.$table does not exist, skipping"
    continue
  fi
  change "ALTER TABLE default.$table MODIFY SETTING ttl_only_drop_parts = 1, materialize_ttl_recalculate_only = 1"
  change "ALTER TABLE default.$table MODIFY TTL toDateTime($column) + toIntervalDay($RETENTION_DAYS)"
done

for entry in $ROW_TTL_TABLES; do
  table=${entry%%:*} column=${entry#*:}
  if ! table_exists default "$table"; then
    log "default.$table does not exist, skipping"
    continue
  fi
  change "ALTER TABLE default.$table MODIFY TTL toDateTime($column) + toIntervalDay($RETENTION_DAYS)"
done

# A flush makes ClickHouse create each enabled log table, renaming any whose definition
# the overlay changed to <name>_N, so the leftovers exist before they are listed.
query "SYSTEM FLUSH LOGS"

removed_pattern=$(printf '%s|' $REMOVED_SYSTEM_TABLES)
removed_pattern="^(${removed_pattern%|})(_[0-9]+)?\$"
stale_tables=$(query "SELECT name FROM system.tables WHERE database = 'system'
  AND (match(name, '$removed_pattern') OR match(name, '^[a-z_]+_log_[0-9]+\$'))
  ORDER BY name")
if [ -z "$stale_tables" ]; then
  log "no stale system log tables"
fi
for table in $stale_tables; do
  change "DROP TABLE IF EXISTS system.$table SYNC"
done

policy=$(cat <<EOF
{"Rules":[{"ID":"langfuse-events-expiry","Status":"Enabled","Filter":{"Prefix":"$EVENT_PREFIX"},"Expiration":{"Days":$RETENTION_DAYS}}]}
EOF
)
# `ilm import` replaces the bucket's whole lifecycle configuration, so re-running it
# does not stack duplicate rules.
if [ "$DRY_RUN" = 1 ]; then
  log "would import MinIO lifecycle on $EVENT_BUCKET: $policy"
else
  log "importing MinIO lifecycle on $EVENT_BUCKET: $policy"
  printf '%s' "$policy" | docker exec -i "$MINIO" sh -c \
    'mc alias set retention http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null \
       && mc ilm import "retention/$1"' minio-ilm "$EVENT_BUCKET"
fi

if [ "$DRY_RUN" = 1 ]; then
  log "dry run: nothing changed"
  exit 0
fi

log "ClickHouse parts on disk after: $(disk_used)"

# Last, and fatal: if the overlay is not loaded, the flush above re-created the tables
# that were just dropped and they will fill the disk again.
query "SYSTEM FLUSH LOGS"
still_there=$(query "SELECT name FROM system.tables WHERE database = 'system'
  AND match(name, '$removed_pattern') ORDER BY name")
if [ -n "$still_there" ]; then
  die "system tables the overlay should remove are still being written: $(echo $still_there).
  The running container has not loaded config/clickhouse/backup-disk.xml. Recreate it with
  docker compose -f docker-compose.prod.yml up -d --force-recreate --no-deps langfuse-clickhouse
  and run this again."
fi
for entry in $PARTITIONED_TABLES $ROW_TTL_TABLES; do
  table=${entry%%:*}
  table_exists default "$table" || continue
  [ "$(query "SELECT count() FROM system.tables WHERE database = 'default' AND name = '$table' AND engine_full LIKE '% TTL %'")" = 1 ] \
    || die "default.$table has no TTL after the ALTER"
done
log "done: Langfuse data expires after $RETENTION_DAYS days and the overlay is loaded"
