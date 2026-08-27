#!/usr/bin/env bash
#
# Captures the platform datastores and uploads the archive to Google Drive.
#
# Covers the four Postgres databases in langfuse-db (langfuse, dtrack, temporal,
# temporal_visibility) and the ClickHouse `default` database. The archive it produces
# is what scripts/restore-platform.sh consumes; the two are a matched pair and share
# their manifest format, their secret-fingerprint scheme and their .env handling.
#
# WHERE THIS RUNS
#   In the `deployer` container, invoked by a Temporal activity on a nightly schedule
#   — and by a human, by hand, for an on-demand capture. Constitution 2.0.0 forbids
#   the backend (the container terminating public traffic) from holding the Docker
#   socket or launching a host process, so the capture lives here. The Java side never
#   invokes docker itself; this script is the single capture mechanism, exactly as
#   restart-prod.sh is the single deploy mechanism.
#
# USAGE
#   backup-platform.sh                 # capture, upload, prune to the retention limit
#   backup-platform.sh --dry-run       # print every command, change nothing
#   backup-platform.sh --no-upload     # capture to --out-dir and stop (no Drive calls)
#   backup-platform.sh --keep-local    # upload, but do not delete the local archive
#   backup-platform.sh --out-dir DIR   # where to build the archive (default: mktemp -d)
#
# ALWAYS --dry-run FIRST when changing this script. It `docker exec`s into live
# datastores, so simply running it to "see what it does" reads production data and
# writes to Drive.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

ENV_FILE="${ENV_FILE:-$PROJECT_DIR/.env}"

POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-langfuse-db}"
CLICKHOUSE_CONTAINER="${CLICKHOUSE_CONTAINER:-langfuse-clickhouse}"
CLICKHOUSE_DATABASE="${CLICKHOUSE_DATABASE:-default}"
# The langfuse-clickhouse-backups volume as the ClickHouse container sees it. The
# archive comes back out via `docker cp`, so this script needs no mount of its own.
CLICKHOUSE_BACKUP_DIR="${CLICKHOUSE_BACKUP_DIR:-/backups}"

DATABASES=(langfuse dtrack temporal temporal_visibility)
# Recorded in the manifest so a restore knows which tool version produced the dump.
IMAGE_CONTAINERS=(langfuse dependencytrack-apiserver langfuse-clickhouse)

DRIVE_FOLDER_NAME="${DRIVE_FOLDER_NAME:-simonrowe-platform-backups}"
RETENTION="${RETENTION:-7}"

# MUST match SecretFingerprinter's old scheme byte for byte: archives written by the
# previous implementation stay verifiable, and restore-platform.sh already computes it
# this way.
FINGERPRINT_PREFIX="platform-backup-fingerprint-v1:"
FINGERPRINTED_KEYS=(ENCRYPTION_KEY SALT NEXTAUTH_SECRET DEPENDENCYTRACK_KEK)

SCHEMA_VERSION=1
ARCHIVE_PREFIX="platform-backup-"
STAGING_PREFIX="platform-clickhouse-"

DRY_RUN=0
NO_UPLOAD=0
KEEP_LOCAL=0
OUT_DIR=""
WORK_DIR=""
STAGED_CLICKHOUSE_FILE=""

# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

log()  { printf '[backup-platform] %s\n' "$*"; }
warn() { printf '[backup-platform] WARNING: %s\n' "$*" >&2; }
die()  { printf '[backup-platform] ERROR: %s\n' "$*" >&2; exit 1; }

# NOTE on `docker exec` and stdin, applied throughout this file: every invocation
# below redirects `</dev/null`. Several run inside `while read` loops, and a docker
# exec that inherits stdin consumes the loop's remaining input. That exact bug in
# restore-platform.sh swallowed an `ALTER ROLE ... PASSWORD` line and produced a
# passwordless role, which only surfaced when the restored service could not
# authenticate. Do not remove the redirections.

# ---------------------------------------------------------------------------
# Arguments
# ---------------------------------------------------------------------------

usage() { sed -n '3,27p' "$0" | sed 's|^# \{0,1\}||'; }

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --dry-run)    DRY_RUN=1; shift ;;
      --no-upload)  NO_UPLOAD=1; shift ;;
      --keep-local) KEEP_LOCAL=1; shift ;;
      --out-dir)
        [ $# -ge 2 ] || die "--out-dir requires a value"
        OUT_DIR="$2"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) die "unknown argument: $1 (try --help)" ;;
    esac
  done
  if [ "$NO_UPLOAD" -eq 1 ] && [ -z "$OUT_DIR" ]; then
    die "--no-upload needs --out-dir, or the archive would be built and then deleted"
  fi
}

check_prerequisites() {
  command -v docker >/dev/null 2>&1 || die "docker is not on PATH"
  command -v python3 >/dev/null 2>&1 || die "python3 is required (JSON handling)"
  command -v zip >/dev/null 2>&1 || die "zip is required"
  command -v curl >/dev/null 2>&1 || die "curl is required (Google Drive)"
  command -v shasum >/dev/null 2>&1 || command -v sha256sum >/dev/null 2>&1 \
    || die "shasum or sha256sum is required (secret fingerprints)"
  [ -f "$ENV_FILE" ] || die "env file not found: $ENV_FILE"
}

# ---------------------------------------------------------------------------
# Environment and fingerprints — identical to restore-platform.sh
# ---------------------------------------------------------------------------

# Reads one key from .env WITHOUT sourcing it. Sourcing would execute arbitrary shell
# and clobber this script's own variables.
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

sha256_hex() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | cut -d' ' -f1
  else
    shasum -a 256 | cut -d' ' -f1
  fi
}

# MUST use printf '%s', never echo. echo appends a newline; restore-platform.sh hashes
# without one, so an `echo` here would make every archive fail its own restore check.
fingerprint_of() {
  local key="$1" value="$2"
  if [ -z "$value" ]; then
    printf ''
    return 0
  fi
  printf '%s' "${FINGERPRINT_PREFIX}${key}:${value}" | sha256_hex
}

load_environment() {
  PG_USER="$(env_value LANGFUSE_DB_USER)"
  [ -n "$PG_USER" ] || PG_USER="postgres"
  PGPASSWORD="$(env_value LANGFUSE_DB_PASSWORD)"
  export PGPASSWORD
}

# ---------------------------------------------------------------------------
# Capture
# ---------------------------------------------------------------------------

# Residue from crashed prior runs. Without this a few failed nights quietly fill the
# SD card, and the first symptom is some unrelated service failing to write.
sweep_orphans() {
  log "Sweeping orphaned ClickHouse backup files from previous runs"
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '  would remove %s/%s* inside %s\n' \
      "$CLICKHOUSE_BACKUP_DIR" "$STAGING_PREFIX" "$CLICKHOUSE_CONTAINER"
    return 0
  fi
  docker exec -u 0 "$CLICKHOUSE_CONTAINER" \
    sh -c "rm -f ${CLICKHOUSE_BACKUP_DIR}/${STAGING_PREFIX}*" </dev/null \
    || warn "could not sweep $CLICKHOUSE_BACKUP_DIR (continuing)"
}

# `-e PGPASSWORD` with a BARE NAME and no value: the Docker CLI forwards the value
# from its own environment, so the password appears in no process's argv and cannot
# be read from `ps` on the host.
pg_exec() {
  docker exec -e PGPASSWORD "$POSTGRES_CONTAINER" "$@" </dev/null
}

# Dumps run as the superuser because the four databases have three different owners:
# langfuse belongs to the superuser, dtrack to `dtrack`, and both Temporal databases
# to `temporal`. Only the superuser can read all four.
dump_postgres() {
  local out_dir="$1" database target

  log "Exporting Postgres roles"
  target="$out_dir/postgres/roles.sql"
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '  would run: docker exec -e PGPASSWORD %s pg_dumpall -U %s --roles-only > %s\n' \
      "$POSTGRES_CONTAINER" "$PG_USER" "$target"
  else
    pg_exec pg_dumpall --no-password -U "$PG_USER" --roles-only > "$target" \
      || die "pg_dumpall --roles-only failed"
    assert_non_empty "$target" "roles"
  fi

  for database in "${DATABASES[@]}"; do
    log "Exporting database: $database"
    target="$out_dir/postgres/${database}.sql"
    if [ "$DRY_RUN" -eq 1 ]; then
      printf '  would run: docker exec -e PGPASSWORD %s pg_dump -U %s -d %s > %s\n' \
        "$POSTGRES_CONTAINER" "$PG_USER" "$database" "$target"
      continue
    fi
    # Exit status is checked here, not after the whole loop: a dump that fails
    # halfway still leaves a plausible-looking file, and a partial archive that
    # reaches Drive and prunes a good older one is worse than no backup at all.
    pg_exec pg_dump --no-password -U "$PG_USER" -d "$database" > "$target" \
      || die "pg_dump of '$database' failed"
    assert_non_empty "$target" "$database"
  done
}

assert_non_empty() {
  local file="$1" what="$2"
  [ -s "$file" ] || die "dump for '$what' is empty — refusing to archive nothing"
}

clickhouse_query() {
  local sql="$1"
  docker exec "$CLICKHOUSE_CONTAINER" sh -c \
    'clickhouse-client --user "${CLICKHOUSE_USER:-clickhouse}" \
       --password "${CLICKHOUSE_PASSWORD:-}" --query "$1"' \
    clickhouse-query "$sql" </dev/null
}

# Lets ClickHouse serialise its own state. Deliberately not a hand-rolled per-table
# export: Langfuse's schema has materialized views whose data lives in `.inner_id.*`
# tables and which move with Langfuse versions, so a blind spot there would produce a
# backup that restores looking correct and is quietly missing a view.
dump_clickhouse() {
  local out_dir="$1" timestamp="$2"
  STAGED_CLICKHOUSE_FILE="${STAGING_PREFIX}${timestamp}.zip"

  log "Backing up ClickHouse database '$CLICKHOUSE_DATABASE'"
  if [ "$DRY_RUN" -eq 1 ]; then
    printf "  would run clickhouse-client: BACKUP DATABASE %s TO File('%s')\n" \
      "$CLICKHOUSE_DATABASE" "$STAGED_CLICKHOUSE_FILE"
    printf '  would copy %s:%s/%s -> %s/clickhouse/%s.zip\n' \
      "$CLICKHOUSE_CONTAINER" "$CLICKHOUSE_BACKUP_DIR" "$STAGED_CLICKHOUSE_FILE" \
      "$out_dir" "$CLICKHOUSE_DATABASE"
    return 0
  fi

  clickhouse_query \
    "BACKUP DATABASE ${CLICKHOUSE_DATABASE} TO File('${STAGED_CLICKHOUSE_FILE}')" \
    >/dev/null || die "ClickHouse BACKUP failed"

  docker cp "${CLICKHOUSE_CONTAINER}:${CLICKHOUSE_BACKUP_DIR}/${STAGED_CLICKHOUSE_FILE}" \
    "$out_dir/clickhouse/${CLICKHOUSE_DATABASE}.zip" \
    || die "ClickHouse reported success but produced no readable file at \
${CLICKHOUSE_BACKUP_DIR}/${STAGED_CLICKHOUSE_FILE} — check that the \
langfuse-clickhouse-backups volume is mounted and writable by uid 101"
  assert_non_empty "$out_dir/clickhouse/${CLICKHOUSE_DATABASE}.zip" "clickhouse"
}

# Per-table row counts, the only practical way to verify a ClickHouse restore landed
# everything, because the archive itself is opaque.
#
# `.inner%` is excluded deliberately: a restore regenerates materialized-view inner
# table UUIDs, so recording them would make consecutive manifests incomparable and
# would read as a missing table after a restore that actually succeeded. Verified
# against the pinned 26.7.1.1315.
clickhouse_row_counts_json() {
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '{}'
    return 0
  fi
  clickhouse_query "SELECT table, sum(rows) FROM system.parts WHERE active \
AND database = '${CLICKHOUSE_DATABASE}' AND table NOT LIKE '.inner%' \
GROUP BY table ORDER BY table" | python3 -c '
import json, sys
tables = {}
for line in sys.stdin.read().splitlines():
    parts = line.split("\t")
    if len(parts) == 2:
        try:
            tables[parts[0].strip()] = int(parts[1].strip())
        except ValueError:
            pass
print(json.dumps(tables), end="")
'
}

# Best-effort: a missing container costs a manifest entry, not the backup.
image_tags_json() {
  local container tag
  local pairs=""
  for container in "${IMAGE_CONTAINERS[@]}"; do
    if [ "$DRY_RUN" -eq 1 ]; then
      continue
    fi
    tag="$(docker inspect --format '{{.Config.Image}}' "$container" 2>/dev/null || true)"
    [ -n "$tag" ] || { warn "could not read image tag for $container"; continue; }
    pairs="${pairs}${container}=${tag}"$'\n'
  done
  printf '%s' "$pairs" | python3 -c '
import json, sys
images = {}
for line in sys.stdin.read().splitlines():
    if "=" in line:
        name, _, tag = line.partition("=")
        images[name] = tag
print(json.dumps(images), end="")
'
}

fingerprints_json() {
  local key value
  local pairs=""
  for key in "${FINGERPRINTED_KEYS[@]}"; do
    value="$(fingerprint_of "$key" "$(env_value "$key")")"
    pairs="${pairs}${key}=${value}"$'\n'
  done
  printf '%s' "$pairs" | python3 -c '
import json, sys
out = {}
for line in sys.stdin.read().splitlines():
    if "=" in line:
        name, _, value = line.partition("=")
        # Absent secrets are recorded as null, never as the digest of the empty
        # string: the restore script must tell "unverifiable" from "matches".
        out[name] = value or None
print(json.dumps(out), end="")
'
}

# Written LAST: it records byte counts and row counts only known once the dumps
# complete. Schema is specs/034-platform-datastore-backup/data-model.md section 2.
write_manifest() {
  local out_dir="$1" created_at="$2"
  log "Writing manifest.json"
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '  would write %s/manifest.json\n' "$out_dir"
    return 0
  fi
  SCHEMA_VERSION="$SCHEMA_VERSION" \
  CREATED_AT="$created_at" \
  PG_CONTAINER="$POSTGRES_CONTAINER" \
  CH_CONTAINER="$CLICKHOUSE_CONTAINER" \
  CH_DATABASE="$CLICKHOUSE_DATABASE" \
  CH_TABLES="$(clickhouse_row_counts_json)" \
  IMAGES="$(image_tags_json)" \
  FINGERPRINTS="$(fingerprints_json)" \
  DATABASE_LIST="${DATABASES[*]}" \
  python3 - "$out_dir" <<'PY'
import json, os, sys
out_dir = sys.argv[1]


def entry(relative):
    path = os.path.join(out_dir, relative)
    return {"entry": relative, "bytes": os.path.getsize(path)}


manifest = {
    "schemaVersion": int(os.environ["SCHEMA_VERSION"]),
    "createdAt": os.environ["CREATED_AT"],
    "postgres": {
        "container": os.environ["PG_CONTAINER"],
        "databases": {
            name: entry(f"postgres/{name}.sql")
            for name in os.environ["DATABASE_LIST"].split()
        },
        "roles": entry("postgres/roles.sql"),
    },
    "clickhouse": {
        "container": os.environ["CH_CONTAINER"],
        "database": os.environ["CH_DATABASE"],
        **entry(f"clickhouse/{os.environ['CH_DATABASE']}.zip"),
        "tables": json.loads(os.environ["CH_TABLES"]),
    },
    "images": json.loads(os.environ["IMAGES"]),
    "secretFingerprints": json.loads(os.environ["FINGERPRINTS"]),
}
with open(os.path.join(out_dir, "manifest.json"), "w", encoding="utf-8") as handle:
    json.dump(manifest, handle, indent=2)
    handle.write("\n")
PY
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

# Resolves the PLATFORM folder by name, creating it on first use.
#
# It must never fall back to GOOGLE_DRIVE_FOLDER_ID, which points at the application
# backups. Retention deletes everything past the newest N zips in a folder, so sharing
# one would make the two backup types evict each other and silently halve both
# recovery windows.
drive_platform_folder_id() {
  local token="$1" query result folder_id
  query="name = '${DRIVE_FOLDER_NAME}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
  result="$(curl -sS -G https://www.googleapis.com/drive/v3/files \
    -H "Authorization: Bearer ${token}" \
    --data-urlencode "q=${query}" \
    --data-urlencode "fields=files(id,name)")"
  folder_id="$(printf '%s' "$result" | python3 -c '
import json, sys
files = json.load(sys.stdin).get("files") or []
print(files[0]["id"] if files else "", end="")
')"
  if [ -n "$folder_id" ]; then
    printf '%s' "$folder_id"
    return 0
  fi

  log "Drive folder '$DRIVE_FOLDER_NAME' not found; creating it" >&2
  curl -sS -X POST https://www.googleapis.com/drive/v3/files \
    -H "Authorization: Bearer ${token}" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"${DRIVE_FOLDER_NAME}\",\"mimeType\":\"application/vnd.google-apps.folder\"}" \
    | python3 -c '
import json, sys
payload = json.load(sys.stdin)
folder = payload.get("id")
if not folder:
    sys.exit("could not create Drive folder: " + json.dumps(payload))
print(folder, end="")
'
}

# Google's resumable upload: POST for a session URI, then PUT the bytes, retrying the
# PUT from wherever the server says it got to.
#
# Resumable rather than a single multipart PUT because this archive is potentially
# multi-GB over a residential uplink, and restarting from zero on a dropped connection
# is expensive. Temporal retries the whole activity, but that is a coarser net.
drive_upload_resumable() {
  local token="$1" folder_id="$2" file="$3" name="$4"
  local size session_uri attempt offset status
  size="$(wc -c < "$file" | tr -d ' ')"

  session_uri="$(curl -sS -X POST \
    "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable" \
    -H "Authorization: Bearer ${token}" \
    -H "Content-Type: application/json; charset=UTF-8" \
    -H "X-Upload-Content-Type: application/zip" \
    -H "X-Upload-Content-Length: ${size}" \
    -d "{\"name\":\"${name}\",\"parents\":[\"${folder_id}\"]}" \
    -D - -o /dev/null | awk 'BEGIN{IGNORECASE=1} /^location:/ {print $2}' | tr -d '\r')"
  [ -n "$session_uri" ] || die "Drive did not return a resumable session URI"

  offset=0
  for attempt in 1 2 3 4 5; do
    log "Uploading $name ($(human_size "$size")), attempt $attempt, from byte $offset"
    if [ "$offset" -eq 0 ]; then
      status="$(curl -sS -X PUT "$session_uri" \
        -H "Content-Type: application/zip" \
        --data-binary "@${file}" \
        -o /dev/null -w '%{http_code}' || true)"
    else
      status="$(curl -sS -X PUT "$session_uri" \
        -H "Content-Type: application/zip" \
        -H "Content-Range: bytes ${offset}-$((size - 1))/${size}" \
        --data-binary "@-" \
        -o /dev/null -w '%{http_code}' < <(tail -c "+$((offset + 1))" "$file") || true)"
    fi

    case "$status" in
      200|201) log "Upload complete"; return 0 ;;
      308)
        offset="$(drive_resume_offset "$session_uri" "$token")"
        log "Interrupted; server has $offset bytes, resuming"
        ;;
      *)
        warn "upload attempt $attempt returned HTTP $status"
        offset="$(drive_resume_offset "$session_uri" "$token")"
        ;;
    esac
  done
  die "upload of $name failed after 5 attempts"
}

# Asks the session how many bytes it already holds, so a retry resumes rather than
# restarting. A 308 with no Range header means it has nothing yet.
drive_resume_offset() {
  local session_uri="$1" token="$2" range
  range="$(curl -sS -X PUT "$session_uri" \
    -H "Content-Range: bytes */*" \
    -H "Authorization: Bearer ${token}" \
    -D - -o /dev/null | awk 'BEGIN{IGNORECASE=1} /^range:/ {print $2}' | tr -d '\r')"
  if [ -z "$range" ]; then
    printf '0'
  else
    printf '%s' "$(( ${range##*-} + 1 ))"
  fi
}

human_size() {
  python3 -c '
import sys
size = float(sys.argv[1] or 0)
for unit in ("B", "KB", "MB", "GB"):
    if size < 1024 or unit == "GB":
        print(f"{int(size)} B" if unit == "B" else f"{size:.1f} {unit}", end="")
        break
    size /= 1024
' "$1"
}

# Prunes only after a successful upload: deleting an older good archive to make room
# for one that never arrived is the worst outcome available here. A per-file failure
# is logged without aborting the sweep.
drive_prune() {
  local token="$1" folder_id="$2" listing id name count=0
  listing="$(curl -sS -G https://www.googleapis.com/drive/v3/files \
    -H "Authorization: Bearer ${token}" \
    --data-urlencode "q='${folder_id}' in parents and trashed = false and mimeType = 'application/zip'" \
    --data-urlencode "fields=files(id,name,createdTime)" \
    --data-urlencode "orderBy=createdTime desc" \
    | python3 -c '
import json, sys
for f in json.load(sys.stdin).get("files") or []:
    print(f["id"] + "\t" + f["name"])
')"

  while IFS=$'\t' read -r id name; do
    [ -n "$id" ] || continue
    count=$((count + 1))
    [ "$count" -le "$RETENTION" ] && continue
    log "Retention: deleting $name"
    curl -sS -X DELETE "https://www.googleapis.com/drive/v3/files/${id}" \
      -H "Authorization: Bearer ${token}" -o /dev/null \
      || warn "could not delete $name (continuing)"
  done <<< "$listing"

  log "Retention: $count archives present, keeping the newest $RETENTION"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

cleanup() {
  local status=$?
  # Both paths. A failed run must leave nothing behind, or a few failures compound
  # into a full disk.
  if [ -n "$STAGED_CLICKHOUSE_FILE" ] && [ "$DRY_RUN" -eq 0 ]; then
    docker exec -u 0 "$CLICKHOUSE_CONTAINER" \
      rm -f "${CLICKHOUSE_BACKUP_DIR}/${STAGED_CLICKHOUSE_FILE}" </dev/null 2>/dev/null || true
  fi
  if [ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ] && [ "$KEEP_LOCAL" -eq 0 ]; then
    rm -rf "$WORK_DIR"
  fi
  exit $status
}

main() {
  parse_args "$@"
  check_prerequisites
  load_environment

  if [ "$DRY_RUN" -eq 1 ]; then
    log "DRY RUN — no command below will actually be executed."
  fi

  local timestamp created_at build_dir archive_name archive_path token folder_id
  timestamp="$(date -u +%Y%m%d-%H%M%S)"
  created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  archive_name="${ARCHIVE_PREFIX}${timestamp}.zip"

  WORK_DIR="$(mktemp -d)"
  trap cleanup EXIT INT TERM
  build_dir="$WORK_DIR/${ARCHIVE_PREFIX}${timestamp}"
  mkdir -p "$build_dir/postgres" "$build_dir/clickhouse"

  sweep_orphans
  dump_postgres "$build_dir"
  dump_clickhouse "$build_dir" "$timestamp"
  write_manifest "$build_dir" "$created_at"

  archive_path="${OUT_DIR:-$WORK_DIR}/$archive_name"
  log "Building $archive_name"
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '  would zip %s -> %s\n' "$build_dir" "$archive_path"
  else
    mkdir -p "$(dirname "$archive_path")"
    ( cd "$build_dir" && zip -qr "$archive_path" . )
    # The archive carries pg_dumpall role password hashes; it is not scratch data.
    chmod 600 "$archive_path"
    log "Archive is $(human_size "$(wc -c < "$archive_path" | tr -d ' ')")"
  fi

  if [ "$NO_UPLOAD" -eq 1 ]; then
    log "--no-upload: archive left at $archive_path"
    return 0
  fi

  if [ "$DRY_RUN" -eq 1 ]; then
    printf '  would upload %s to Drive folder %s and prune to %s\n' \
      "$archive_name" "$DRIVE_FOLDER_NAME" "$RETENTION"
    return 0
  fi

  token="$(drive_access_token)"
  folder_id="$(drive_platform_folder_id "$token")"
  drive_upload_resumable "$token" "$folder_id" "$archive_path" "$archive_name"
  drive_prune "$token" "$folder_id"

  log "Done."
}

main "$@"
