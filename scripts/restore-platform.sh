#!/usr/bin/env bash
#
# Restores platform datastores from a platform-backup archive.
#
# Covers the four Postgres databases in langfuse-db (langfuse, dtrack, temporal,
# temporal_visibility) and the ClickHouse `default` database. The archives are
# produced by the backend's nightly PlatformBackupService and live in the
# `simonrowe-platform-backups` Google Drive folder.
#
# WHY THIS IS A SHELL SCRIPT AND NOT AN ADMIN BUTTON
#   The scenario that motivates restore is "the Pi died, here is a new one". In
#   that scenario the backend is the thing being rebuilt, so a restore button
#   inside it is the wrong tool. This works whether or not the application runs.
#
# USAGE
#   restore-platform.sh --list
#   restore-platform.sh --target langfuse --latest
#   restore-platform.sh --target dtrack   --file ./platform-backup-20260825-020000.zip
#   restore-platform.sh --target temporal --latest --dry-run
#   restore-platform.sh --target all      --latest
#
# FLAGS
#   --target <langfuse|dtrack|temporal|all>  what to restore
#   --file <zip>    restore from a local archive
#   --latest        fetch the newest archive from Google Drive
#   --list          list the archives available in Drive, then exit
#   --dry-run       print every command and change nothing
#   --force         proceed despite a secret-fingerprint mismatch (dangerous)
#
# ALWAYS --dry-run FIRST. Every path here shells out to `docker compose`, so
# simply running the script to "see what it says" performs real restarts. Same
# precedent, and same reason, as scripts/monitor-prod.sh.
#
# ACCEPTED DATA LOSS: restored Langfuse traces keep their metadata, scores and
# observations, but very large payload bodies are missing, because those live in
# MinIO and MinIO is out of scope for this backup. Do not discover this during an
# incident.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_DIR/docker-compose.prod.yml}"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-simonrowe-dev-monorepo}"
ENV_FILE="${ENV_FILE:-$PROJECT_DIR/.env}"

POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-langfuse-db}"
CLICKHOUSE_CONTAINER="${CLICKHOUSE_CONTAINER:-langfuse-clickhouse}"
CLICKHOUSE_DATABASE="${CLICKHOUSE_DATABASE:-default}"
# Where the ClickHouse container sees the shared langfuse-clickhouse-backups volume.
CLICKHOUSE_BACKUP_DIR="${CLICKHOUSE_BACKUP_DIR:-/backups}"
# The uid:gid the ClickHouse server process runs as. `docker cp` preserves the
# HOST file's ownership, so a copied-in archive is unreadable to the server
# without an explicit chown — verified: it fails with CANNOT_OPEN_FILE, an error
# that gives no hint that ownership is the problem.
CLICKHOUSE_UID_GID="${CLICKHOUSE_UID_GID:-101:101}"

DRIVE_FOLDER_NAME="${DRIVE_FOLDER_NAME:-simonrowe-platform-backups}"
FINGERPRINT_PREFIX="platform-backup-fingerprint-v1:"
FINGERPRINTED_KEYS=(ENCRYPTION_KEY SALT NEXTAUTH_SECRET DEPENDENCYTRACK_KEK)

# Written outside the repo so a safety dump is never committed or wiped by a
# clean checkout.
PRE_RESTORE_DIR="${PRE_RESTORE_DIR:-$HOME/backups/platform-pre-restore}"

TARGET=""
ARCHIVE_FILE=""
USE_LATEST=0
LIST_ONLY=0
DRY_RUN=0
FORCE=0
WORK_DIR=""
STOPPED_SERVICES=()

# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

log()  { printf '[restore-platform] %s\n' "$*"; }
warn() { printf '[restore-platform] WARNING: %s\n' "$*" >&2; }
die()  { printf '[restore-platform] ERROR: %s\n' "$*" >&2; exit 1; }

# Runs a command, or prints it under --dry-run. Every mutating action goes
# through this; anything that bypasses it will run during a dry run.
run() {
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '  would run: %s\n' "$*"
    return 0
  fi
  # stdin closed: run() is called from inside `while read` loops fed by process
  # substitution, and any command that reads stdin (docker exec -i, docker
  # compose) would consume the loop's remaining input.
  "$@" </dev/null
}

# Like run(), but for a psql statement, so the SQL is visible in a dry run
# instead of being buried in a docker exec argument list.
run_psql() {
  local database="$1" sql="$2"
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '  would run psql (%s): %s\n' "$database" "$sql"
    return 0
  fi
  # NO `-i`, and stdin explicitly closed. This function is called from inside
  # `while read` loops fed by process substitution; with `-i`, docker attaches
  # stdin and consumes the loop's remaining input, silently swallowing the rest of
  # the list. That bug created roles from their CREATE statement while their
  # ALTER ... LOGIN ... PASSWORD line was eaten — a passwordless role that then
  # fails authentication forever. Use `-i` only where a dump is piped in
  # deliberately (see restore_database).
  docker exec -e PGPASSWORD "$POSTGRES_CONTAINER" \
    psql --no-password -v ON_ERROR_STOP=1 -U "$PG_USER" -d "$database" -c "$sql" \
    >/dev/null </dev/null
}

run_clickhouse() {
  local sql="$1"
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '  would run clickhouse-client: %s\n' "$sql"
    return 0
  fi
  docker exec "$CLICKHOUSE_CONTAINER" sh -c \
    'clickhouse-client --user "${CLICKHOUSE_USER:-clickhouse}" \
       --password "${CLICKHOUSE_PASSWORD:-}" --query "$1"' \
    clickhouse-query "$sql" </dev/null
}

# ---------------------------------------------------------------------------
# Arguments
# ---------------------------------------------------------------------------

usage() {
  sed -n '3,38p' "$0" | sed 's|^# \{0,1\}||'
}

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --target)
        [ $# -ge 2 ] || die "--target requires a value"
        TARGET="$2"; shift 2 ;;
      --file)
        [ $# -ge 2 ] || die "--file requires a value"
        ARCHIVE_FILE="$2"; shift 2 ;;
      --latest) USE_LATEST=1; shift ;;
      --list)   LIST_ONLY=1; shift ;;
      --dry-run) DRY_RUN=1; shift ;;
      --force)  FORCE=1; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "unknown argument: $1 (try --help)" ;;
    esac
  done
}

validate_args() {
  if [ "$LIST_ONLY" -eq 1 ]; then
    return 0
  fi
  case "$TARGET" in
    langfuse|dtrack|temporal|all) : ;;
    "") die "--target is required (langfuse|dtrack|temporal|all), or use --list" ;;
    *)  die "unknown target '$TARGET' (expected langfuse|dtrack|temporal|all)" ;;
  esac
  if [ "$USE_LATEST" -eq 1 ] && [ -n "$ARCHIVE_FILE" ]; then
    die "--latest and --file are mutually exclusive"
  fi
  if [ "$USE_LATEST" -eq 0 ] && [ -z "$ARCHIVE_FILE" ]; then
    die "specify either --file <zip> or --latest"
  fi
  if [ -n "$ARCHIVE_FILE" ] && [ ! -f "$ARCHIVE_FILE" ]; then
    die "archive not found: $ARCHIVE_FILE"
  fi
}

check_prerequisites() {
  command -v docker >/dev/null 2>&1 || die "docker is not on PATH"
  command -v python3 >/dev/null 2>&1 || die "python3 is required (JSON parsing)"
  command -v unzip >/dev/null 2>&1 || die "unzip is required"
  command -v shasum >/dev/null 2>&1 || command -v sha256sum >/dev/null 2>&1 \
    || die "shasum or sha256sum is required (fingerprint verification)"
  [ -f "$ENV_FILE" ] || die "env file not found: $ENV_FILE"
  [ -f "$COMPOSE_FILE" ] || die "compose file not found: $COMPOSE_FILE"
}

# ---------------------------------------------------------------------------
# Environment
# ---------------------------------------------------------------------------

# Reads one key from .env WITHOUT sourcing the file. Sourcing would execute
# arbitrary shell and would clobber this script's own variables.
env_value() {
  local key="$1"
  python3 - "$ENV_FILE" "$key" <<'PY'
import sys
path, key = sys.argv[1], sys.argv[2]
value = ""
with open(path, encoding="utf-8", errors="replace") as handle:
    for raw in handle:
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, _, candidate = line.partition("=")
        if name.strip() != key:
            continue
        candidate = candidate.strip()
        if len(candidate) >= 2 and candidate[0] == candidate[-1] and candidate[0] in "\"'":
            candidate = candidate[1:-1]
        value = candidate
print(value, end="")
PY
}

load_environment() {
  PG_USER="$(env_value LANGFUSE_DB_USER)"
  [ -n "$PG_USER" ] || PG_USER="postgres"
  PGPASSWORD="$(env_value LANGFUSE_DB_PASSWORD)"
  export PGPASSWORD
}

sha256_hex() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | cut -d' ' -f1
  else
    shasum -a 256 | cut -d' ' -f1
  fi
}

# MUST use printf '%s', never echo. echo appends a newline, the Java side hashes
# the value without one, and every legitimate restore would be refused. See
# SecretFingerprinter and its known-answer test.
fingerprint_of() {
  local key="$1" value="$2"
  if [ -z "$value" ]; then
    printf ''
    return 0
  fi
  printf '%s' "${FINGERPRINT_PREFIX}${key}:${value}" | sha256_hex
}

# ---------------------------------------------------------------------------
# Google Drive
# ---------------------------------------------------------------------------

drive_access_token() {
  local client_id client_secret refresh_token response
  client_id="$(env_value GOOGLE_DRIVE_CLIENT_ID)"
  client_secret="$(env_value GOOGLE_DRIVE_CLIENT_SECRET)"
  refresh_token="$(env_value GOOGLE_DRIVE_REFRESH_TOKEN)"
  [ -n "$client_id" ] && [ -n "$client_secret" ] && [ -n "$refresh_token" ] \
    || die "GOOGLE_DRIVE_CLIENT_ID/SECRET/REFRESH_TOKEN must be set in $ENV_FILE"

  response="$(curl -sS -X POST https://oauth2.googleapis.com/token \
    -d "client_id=${client_id}" \
    -d "client_secret=${client_secret}" \
    -d "refresh_token=${refresh_token}" \
    -d "grant_type=refresh_token")"

  printf '%s' "$response" | python3 -c '
import json, sys
payload = json.load(sys.stdin)
token = payload.get("access_token")
if not token:
    sys.exit("token exchange failed: " + json.dumps(payload))
print(token, end="")
'
}

drive_folder_id() {
  local token="$1" query
  query="name = '${DRIVE_FOLDER_NAME}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
  curl -sS -G https://www.googleapis.com/drive/v3/files \
    -H "Authorization: Bearer ${token}" \
    --data-urlencode "q=${query}" \
    --data-urlencode "fields=files(id,name)" \
    | python3 -c '
import json, sys
files = json.load(sys.stdin).get("files") or []
if not files:
    sys.exit("Drive folder not found. Has a platform backup ever run?")
print(files[0]["id"], end="")
'
}

# Prints "<id>\t<name>\t<size>\t<createdTime>" newest first.
drive_list_archives() {
  local token="$1" folder_id="$2"
  curl -sS -G https://www.googleapis.com/drive/v3/files \
    -H "Authorization: Bearer ${token}" \
    --data-urlencode "q='${folder_id}' in parents and trashed = false and mimeType = 'application/zip'" \
    --data-urlencode "fields=files(id,name,size,createdTime)" \
    --data-urlencode "orderBy=createdTime desc" \
    | python3 -c '
import json, sys
for f in json.load(sys.stdin).get("files") or []:
    print("\t".join([f["id"], f["name"], str(f.get("size", 0)), f.get("createdTime", "")]))
'
}

list_archives() {
  local token folder_id
  token="$(drive_access_token)"
  folder_id="$(drive_folder_id "$token")"
  log "Archives in Drive folder '$DRIVE_FOLDER_NAME' (newest first):"
  drive_list_archives "$token" "$folder_id" | while IFS=$'\t' read -r _ name size created; do
    printf '  %-44s %12s  %s\n' "$name" "$(human_size "$size")" "$created"
  done
}

human_size() {
  python3 -c '
import sys
size = float(sys.argv[1] or 0)
for unit in ("B", "KB", "MB", "GB"):
    if size < 1024 or unit == "GB":
        print(f"{size:.1f} {unit}" if unit != "B" else f"{int(size)} B", end="")
        break
    size /= 1024
' "$1"
}

download_latest_archive() {
  local token folder_id first id name
  token="$(drive_access_token)"
  folder_id="$(drive_folder_id "$token")"
  first="$(drive_list_archives "$token" "$folder_id" | head -1)"
  [ -n "$first" ] || die "no archives found in Drive folder '$DRIVE_FOLDER_NAME'"
  id="$(printf '%s' "$first" | cut -f1)"
  name="$(printf '%s' "$first" | cut -f2)"

  ARCHIVE_FILE="$WORK_DIR/$name"
  log "Downloading $name from Drive..."
  curl -sS -L -o "$ARCHIVE_FILE" \
    -H "Authorization: Bearer ${token}" \
    "https://www.googleapis.com/drive/v3/files/${id}?alt=media"
  [ -s "$ARCHIVE_FILE" ] || die "download produced an empty file"
  log "Downloaded to $ARCHIVE_FILE"
}

# ---------------------------------------------------------------------------
# Archive
# ---------------------------------------------------------------------------

extract_archive() {
  EXTRACT_DIR="$WORK_DIR/extracted"
  mkdir -p "$EXTRACT_DIR"
  log "Extracting $(basename "$ARCHIVE_FILE")..."
  unzip -q -o "$ARCHIVE_FILE" -d "$EXTRACT_DIR"
  [ -f "$EXTRACT_DIR/manifest.json" ] || die "archive has no manifest.json — is this a platform backup?"

  local schema_version
  schema_version="$(manifest_value 'd.get("schemaVersion")')"
  if [ "$schema_version" != "1" ]; then
    die "archive schemaVersion is '$schema_version'; this script understands 1"
  fi
  log "Archive created at $(manifest_value 'd.get("createdAt")') (schemaVersion $schema_version)"
  log "Captured under images: $(manifest_value '" ".join(f"{k}={v}" for k, v in (d.get("images") or {}).items())')"
}

manifest_value() {
  python3 - "$EXTRACT_DIR/manifest.json" "$1" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    d = json.load(handle)
value = eval(sys.argv[2], {"d": d})  # noqa: S307 - expression is script-supplied, not user input
print("" if value is None else value, end="")
PY
}

# ---------------------------------------------------------------------------
# The fingerprint gate
# ---------------------------------------------------------------------------

# A restored database is worthless without the .env it was encrypted under.
# Langfuse encrypts stored LLM API keys with ENCRYPTION_KEY and hashes API keys
# with SALT; Dependency-Track encrypts its secrets with the KEK. Restoring onto a
# host with a freshly generated .env produces rows that load without error and
# then fail to decrypt — a failure that presents as success. This is the gate
# that stops it.
verify_fingerprints() {
  local mismatched=() unverifiable=()
  local key recorded actual

  for key in "${FINGERPRINTED_KEYS[@]}"; do
    recorded="$(manifest_value "(d.get('secretFingerprints') or {}).get('$key') or ''")"
    actual="$(fingerprint_of "$key" "$(env_value "$key")")"

    if [ -z "$recorded" ]; then
      # Absent in the manifest means "the secret was unset at capture time", not
      # "it matches". Reported, not silently accepted.
      unverifiable+=("$key (not recorded in the archive)")
    elif [ -z "$actual" ]; then
      mismatched+=("$key (recorded in the archive, but absent from $ENV_FILE)")
    elif [ "$recorded" != "$actual" ]; then
      mismatched+=("$key")
    fi
  done

  # ${arr[@]} on an empty array is an unbound-variable error under `set -u` on
  # bash 3.2, which is what macOS ships — so guard on the count first.
  if [ "${#unverifiable[@]}" -gt 0 ]; then
    for key in "${unverifiable[@]}"; do
      warn "cannot verify $key"
    done
  fi

  if [ "${#mismatched[@]}" -eq 0 ]; then
    log "Secret fingerprints match: ${FINGERPRINTED_KEYS[*]}"
    return 0
  fi

  warn "secret fingerprint mismatch: ${mismatched[*]}"
  warn "This archive was captured under DIFFERENT secrets than $ENV_FILE holds."
  warn "Restoring will load rows that then fail to decrypt — a failure that looks"
  warn "like success. Recover the original .env from ~/workspace/simonjamesrowe/env"
  warn "rather than overriding this."
  if [ "$FORCE" -eq 1 ]; then
    warn "--force given; proceeding anyway."
    return 0
  fi
  die "refusing to restore (pass --force to override, having understood the above)"
}

# ---------------------------------------------------------------------------
# Targets
# ---------------------------------------------------------------------------

# Databases per target. Kept as functions rather than associative arrays so the
# script runs on bash 3.2, which is what macOS ships.
databases_for() {
  case "$1" in
    langfuse) printf 'langfuse\n' ;;
    dtrack)   printf 'dtrack\n' ;;
    temporal) printf 'temporal\ntemporal_visibility\n' ;;
  esac
}

# Consumers to stop. langfuse-db itself is deliberately NEVER stopped: every
# target drops and recreates databases inside a running server, which is what
# keeps the targets independent. Stopping the server would take Dependency-Track
# and Temporal down with a Langfuse restore.
consumers_for() {
  case "$1" in
    langfuse) printf 'langfuse\nlangfuse-worker\n' ;;
    # dependencytrack-frontend is NOT stopped: it is static nginx with no database
    # connection, so stopping it would extend the outage for nothing.
    dtrack)   printf 'dependencytrack-apiserver\n' ;;
    # software-factory hosts the Temporal workers; a poller against a dropped
    # `temporal` database is the "healthy container, no poller" failure mode.
    temporal) printf 'temporal\ntemporal-ui\nsoftware-factory\n' ;;
  esac
}

owner_for() {
  case "$1" in
    dtrack) printf 'dtrack' ;;
    temporal|temporal_visibility) printf 'temporal' ;;
    *) printf '%s' "$PG_USER" ;;
  esac
}

targets_to_restore() {
  if [ "$TARGET" = "all" ]; then
    printf 'langfuse\ndtrack\ntemporal\n'
  else
    printf '%s\n' "$TARGET"
  fi
}

# ---------------------------------------------------------------------------
# Restore steps
# ---------------------------------------------------------------------------

# The same safety net BackupService.createLocalBackup() gives the Mongo restore.
take_pre_restore_dump() {
  local database="$1" out
  out="$PRE_RESTORE_DIR/${database}-$(archive_timestamp).sql"
  run mkdir -p "$PRE_RESTORE_DIR"
  log "Pre-restore safety dump of '$database' -> $out"
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '  would run: docker exec -e PGPASSWORD %s pg_dump -U %s -d %s > %s\n' \
      "$POSTGRES_CONTAINER" "$PG_USER" "$database" "$out"
    return 0
  fi
  if ! docker exec -e PGPASSWORD "$POSTGRES_CONTAINER" \
      pg_dump --no-password -U "$PG_USER" -d "$database" > "$out" 2>/dev/null; then
    rm -f "$out"
    warn "could not dump '$database' (it may not exist yet); continuing without a safety net for it"
  fi
}

archive_timestamp() {
  python3 -c 'import datetime; print(datetime.datetime.now(datetime.UTC).strftime("%Y%m%d-%H%M%S"), end="")'
}

stop_consumers() {
  local target="$1" service
  while IFS= read -r service; do
    [ -n "$service" ] || continue
    log "Stopping $service"
    run docker compose -f "$COMPOSE_FILE" -p "$COMPOSE_PROJECT" stop "$service"
    STOPPED_SERVICES+=("$service")
  done < <(consumers_for "$target")
}

# Restarts everything this run stopped. Called from the EXIT trap, so it also
# runs on the failure path: a failed restore must leave services running against
# the pre-restore database rather than stopped.
restart_stopped_services() {
  local service
  if [ "${#STOPPED_SERVICES[@]}" -eq 0 ]; then
    return 0
  fi
  for service in "${STOPPED_SERVICES[@]}"; do
    log "Starting $service"
    run docker compose -f "$COMPOSE_FILE" -p "$COMPOSE_PROJECT" up -d --no-deps "$service" \
      || warn "could not start $service — start it by hand"
  done
  STOPPED_SERVICES=()
}

# DROP DATABASE fails while any session is attached, and a just-stopped consumer
# can leave one for a moment.
terminate_connections() {
  local database="$1"
  log "Terminating remaining connections to '$database'"
  run_psql postgres \
    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${database}' AND pid <> pg_backend_pid()"
}

# Roles are created only when absent. The *-db-init compose services normally own
# them, and an unconditional CREATE would either fail or reset a password that the
# running services are already using.
#
# CRITICAL: both the CREATE and its ALTER must be applied. pg_dumpall emits
#
#   CREATE ROLE dtrack;
#   ALTER ROLE dtrack WITH ... LOGIN ... PASSWORD 'SCRAM-SHA-256$...';
#
# so the CREATE alone yields a role with NO password and NO LOGIN. That is exactly
# the failure mode docker-compose.prod.yml warns about on dependencytrack-db-init:
# the role exists, the service starts, and authentication fails forever — and
# because the role now exists, simply re-running this would skip it.
restore_roles() {
  local roles_file="$EXTRACT_DIR/postgres/roles.sql" role statement
  [ -f "$roles_file" ] || { warn "archive has no postgres/roles.sql; skipping roles"; return 0; }

  while IFS= read -r role; do
    [ -n "$role" ] || continue
    if role_exists "$role"; then
      log "Role '$role' already exists; leaving it untouched"
      continue
    fi
    log "Creating absent role '$role' from the archive"
    while IFS= read -r statement; do
      [ -n "$statement" ] || continue
      run_psql postgres "${statement%;}"
    done < <(role_statements "$roles_file" "$role")
  done < <(roles_in_dump "$roles_file")
}

roles_in_dump() {
  # Excludes the superuser the dump was taken as: it necessarily already exists
  # (we are connected as it), and reporting it every run is noise.
  grep -oE '^CREATE ROLE [A-Za-z0-9_]+' "$1" 2>/dev/null \
    | awk '{print $3}' | sort -u | grep -vxF "$PG_USER" || true
}

# Every statement that defines one role, in dump order: the CREATE, then the ALTER
# carrying LOGIN and the password hash.
role_statements() {
  grep -E "^(CREATE|ALTER) ROLE $2( |;)" "$1" || true
}

role_exists() {
  if [ "$DRY_RUN" -eq 1 ]; then
    # Assume present in a dry run: the honest answer is "unknown", and claiming
    # it would be created is more misleading than claiming it exists.
    return 0
  fi
  docker exec -e PGPASSWORD "$POSTGRES_CONTAINER" \
    psql --no-password -tAc "SELECT 1 FROM pg_roles WHERE rolname='$1'" -U "$PG_USER" \
    </dev/null | grep -q 1
}

restore_database() {
  local database="$1" dump owner
  dump="$EXTRACT_DIR/postgres/${database}.sql"
  [ -f "$dump" ] || die "archive has no dump for '$database' (expected postgres/${database}.sql)"
  if [ ! -s "$dump" ]; then
    die "dump for '$database' is empty — refusing to replace a live database with nothing"
  fi
  owner="$(owner_for "$database")"

  terminate_connections "$database"
  log "Dropping and recreating '$database' (owner $owner)"
  run_psql postgres "DROP DATABASE IF EXISTS \"$database\""
  run_psql postgres "CREATE DATABASE \"$database\" OWNER \"$owner\""

  log "Loading $(basename "$dump") into '$database'"
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '  would run: docker exec -i %s psql -U %s -d %s < %s\n' \
      "$POSTGRES_CONTAINER" "$PG_USER" "$database" "$dump"
    return 0
  fi
  docker exec -e PGPASSWORD -i "$POSTGRES_CONTAINER" \
    psql --no-password -q -v ON_ERROR_STOP=1 -U "$PG_USER" -d "$database" < "$dump" >/dev/null
}

# Verified against the pinned clickhouse-server:26.7.1.1315 — see research.md R5.
#
#   1. DROP DATABASE ... SYNC. SYNC matters: the Atomic engine drops
#      asynchronously, so a following RESTORE can race the deletion.
#   2. RESTORE DATABASE ... FROM File(...). This succeeds whether `default` is
#      absent or exists-but-empty, so no `allow_different_database_def` dance is
#      needed even if the container restarted in between.
#
# `allow_non_empty_tables` is deliberately NOT used, even though ClickHouse's own
# error message suggests it: it APPENDS rather than replaces, which would silently
# duplicate every trace row. A loud failure beats duplicated data.
restore_clickhouse() {
  local source="$EXTRACT_DIR/clickhouse/${CLICKHOUSE_DATABASE}.zip"
  local staged
  staged="restore-$(archive_timestamp).zip"
  [ -f "$source" ] || die "archive has no clickhouse/${CLICKHOUSE_DATABASE}.zip"

  log "Copying the ClickHouse archive into $CLICKHOUSE_CONTAINER:$CLICKHOUSE_BACKUP_DIR/$staged"
  run docker cp "$source" "$CLICKHOUSE_CONTAINER:$CLICKHOUSE_BACKUP_DIR/$staged"
  # docker cp preserves the HOST file's ownership, so the server (uid 101) cannot
  # read it and the restore fails with CANNOT_OPEN_FILE — an error that gives no
  # hint that ownership is the cause. Verified; do not remove this.
  run docker exec -u 0 "$CLICKHOUSE_CONTAINER" \
    chown "$CLICKHOUSE_UID_GID" "$CLICKHOUSE_BACKUP_DIR/$staged"

  log "Restoring ClickHouse database '$CLICKHOUSE_DATABASE'"
  run_clickhouse "DROP DATABASE IF EXISTS ${CLICKHOUSE_DATABASE} SYNC"
  run_clickhouse "RESTORE DATABASE ${CLICKHOUSE_DATABASE} FROM File('${staged}')"

  run docker exec -u 0 "$CLICKHOUSE_CONTAINER" rm -f "$CLICKHOUSE_BACKUP_DIR/$staged"

  if [ "$DRY_RUN" -eq 0 ]; then
    log "Restored ClickHouse row counts:"
    run_clickhouse "SELECT table, sum(rows) FROM system.parts WHERE active AND database = '${CLICKHOUSE_DATABASE}' AND table NOT LIKE '.inner%' GROUP BY table ORDER BY table" \
      | sed 's/^/    /'
    log "Manifest recorded: $(manifest_value '" ".join(f"{k}={v}" for k, v in ((d.get("clickhouse") or {}).get("tables") or {}).items())')"
  fi
}

wait_for_health() {
  local service="$1" container attempt state
  container="${COMPOSE_PROJECT}-${service}-1"
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '  would poll health of %s\n' "$container"
    return 0
  fi
  for attempt in $(seq 1 60); do
    state="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
      "$container" 2>/dev/null || printf 'missing')"
    case "$state" in
      healthy|running) log "$service is $state (after ${attempt}0s)"; return 0 ;;
      missing) warn "$container not found; skipping health poll"; return 0 ;;
    esac
    sleep 10
  done
  # A warning, not a failure: the data is restored either way, and a slow-starting
  # Langfuse must not make a successful restore report as failed.
  warn "$service did not become healthy within 10 minutes — check it by hand"
}

restore_target() {
  local target="$1" database
  log "=============================================================="
  log "Restoring target: $target"
  log "=============================================================="

  stop_consumers "$target"
  restore_roles

  while IFS= read -r database; do
    [ -n "$database" ] || continue
    take_pre_restore_dump "$database"
    restore_database "$database"
  done < <(databases_for "$target")

  if [ "$target" = "langfuse" ]; then
    restore_clickhouse
  fi

  restart_stopped_services
  while IFS= read -r service; do
    [ -n "$service" ] || continue
    wait_for_health "$service"
  done < <(consumers_for "$target")

  log "Target '$target' restored."
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

cleanup() {
  local status=$?
  # Restarts anything still stopped, so a failure part-way leaves services
  # running against the pre-restore database rather than down.
  if [ "${#STOPPED_SERVICES[@]}" -gt 0 ]; then
    warn "restore did not complete; restarting the services it stopped"
    restart_stopped_services
  fi
  if [ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ]; then
    rm -rf "$WORK_DIR"
  fi
  exit $status
}

main() {
  parse_args "$@"
  validate_args
  check_prerequisites
  load_environment

  WORK_DIR="$(mktemp -d)"
  trap cleanup EXIT INT TERM

  if [ "$LIST_ONLY" -eq 1 ]; then
    list_archives
    return 0
  fi

  if [ "$DRY_RUN" -eq 1 ]; then
    log "DRY RUN — no command below will actually be executed."
  fi

  if [ "$USE_LATEST" -eq 1 ]; then
    download_latest_archive
  fi

  extract_archive
  verify_fingerprints

  local target
  while IFS= read -r target; do
    [ -n "$target" ] || continue
    restore_target "$target"
  done < <(targets_to_restore)

  log "Done."
  if [ "$DRY_RUN" -eq 0 ]; then
    log "Pre-restore safety dumps are in $PRE_RESTORE_DIR"
    log "NOTE: restored Langfuse traces are missing very large payload bodies —"
    log "those live in MinIO, which this backup deliberately does not cover."
  fi
}

main "$@"
