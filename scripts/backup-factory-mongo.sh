#!/usr/bin/env bash
set -euo pipefail

# Nightly dump of the software factory's own MongoDB (database software_factory) on
# the factory host. Installed by scripts/install-factory-host.sh as a cron job at
# 02:30; see docs/runbooks/factory-host.md.
#
# SCOPE, HONESTLY: this protects against a bad change or a corrupted database - a
# migration that went wrong, a collection dropped by hand, a volume the disk-full
# path damaged. It does NOT protect against losing the NUC: the archives sit on the
# same SSD as the database. There is no off-box copy yet. Most of what the factory
# keeps is re-derivable (run records, review learnings can be re-harvested, Linear is
# the record for tickets), which is why that gap is tolerable for now, not why it
# does not exist.
#
# What it does:
#   1. finds the factory's mongodb container by compose labels (project
#      simonrowe-factory, service mongodb), the way backup-platform.sh does;
#   2. `mongodump --archive --gzip --db software_factory` through `docker exec`, to a
#      partial file in BACKUP_DIR;
#   3. refuses an empty or corrupt archive (gzip -t), then renames it into place;
#   4. keeps the newest RETENTION archives (default 7) and deletes the rest.
# Any failure exits non-zero, and a failed dump never counts towards retention, so a
# string of failures cannot rotate the last good archives away.
#
# DRY_RUN=1 resolves the container and prints the dump and deletions it would make,
# touching nothing.

COMPOSE_PROJECT=${COMPOSE_PROJECT:-simonrowe-factory}
MONGO_SERVICE=${MONGO_SERVICE:-mongodb}
MONGO_DB=${MONGO_DB:-software_factory}
BACKUP_DIR=${BACKUP_DIR:-/var/backups/software-factory}
RETENTION=${RETENTION:-7}
DRY_RUN=${DRY_RUN:-0}

# Archives are named <db>-<UTC stamp>.archive.gz, so a name sort is a time sort.
PREFIX="${MONGO_DB}-"
SUFFIX=".archive.gz"

log() { echo "$(date -Iseconds) [backup-factory-mongo] $*"; }
die() { log "ERROR: $*" >&2; exit 1; }

PARTIAL=""
cleanup() {
  if [[ -n "$PARTIAL" && -e "$PARTIAL" ]]; then
    rm -f "$PARTIAL"
  fi
}
trap cleanup EXIT

case "$RETENTION" in
  '' | *[!0-9]* | 0) die "RETENTION must be a positive integer (got '$RETENTION')" ;;
esac

# `awk NR==1` rather than `head -n1`: head closes the pipe early, which under
# pipefail turns docker's SIGPIPE into a failure (same as backup-platform.sh).
compose_container() {
  docker ps --format '{{.Names}}' \
    --filter "label=com.docker.compose.project=${COMPOSE_PROJECT}" \
    --filter "label=com.docker.compose.service=${MONGO_SERVICE}" \
    2>/dev/null | awk 'NR==1{print}'
}

container="$(compose_container)" || true
if [[ -z "$container" ]]; then
  if [[ "$DRY_RUN" != "0" ]]; then
    container="${COMPOSE_PROJECT}-${MONGO_SERVICE}-1"
    log "DRY RUN: no running container found by label; assuming $container"
  else
    die "no running container for compose service '$MONGO_SERVICE' in project '$COMPOSE_PROJECT'"
  fi
fi
log "Using container $container"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
final="$BACKUP_DIR/${PREFIX}${stamp}${SUFFIX}"

# Lists this script's archives, oldest first. Only exact matches: nothing else in
# the directory is ever a candidate for deletion.
list_archives() {
  local f
  for f in "$BACKUP_DIR/${PREFIX}"*"$SUFFIX"; do
    [[ -f "$f" ]] || continue
    [[ "$(basename "$f")" =~ ^${MONGO_DB}-[0-9]{8}T[0-9]{6}Z\.archive\.gz$ ]] || continue
    echo "$f"
  done | sort
}

if [[ "$DRY_RUN" != "0" ]]; then
  log "DRY RUN: would run: docker exec $container mongodump --archive --gzip --db $MONGO_DB > $final"
  existing="$(list_archives || true)"
  count=$(( $(printf '%s' "$existing" | grep -c . || true) + 1 ))
  if (( count > RETENTION )); then
    printf '%s\n' "$existing" | head -n $(( count - RETENTION )) | while IFS= read -r old; do
      log "DRY RUN: would delete $old"
    done
  fi
  exit 0
fi

mkdir -p "$BACKUP_DIR" || die "cannot create $BACKUP_DIR"
PARTIAL="$BACKUP_DIR/.${PREFIX}${stamp}${SUFFIX}.partial"

log "Dumping database $MONGO_DB to $final"
rc=0
docker exec "$container" mongodump --archive --gzip --db "$MONGO_DB" --quiet > "$PARTIAL" || rc=$?
if (( rc == 126 || rc == 127 )); then
  die "mongodump is not runnable in $container (exit $rc) - the image has no database tools"
elif (( rc != 0 )); then
  die "mongodump failed (exit $rc)"
fi

[[ -s "$PARTIAL" ]] || die "mongodump produced an empty archive"
gzip -t "$PARTIAL" 2>/dev/null || die "the archive is not valid gzip - refusing to keep it"

mv "$PARTIAL" "$final" || die "cannot move the archive into place"
PARTIAL=""
log "Wrote $final ($(wc -c < "$final" | tr -d ' ') bytes)"

# Retention runs only after a good archive is in place.
archives="$(list_archives)"
count="$(printf '%s\n' "$archives" | grep -c . || true)"
if (( count > RETENTION )); then
  printf '%s\n' "$archives" | head -n $(( count - RETENTION )) | while IFS= read -r old; do
    rm -f -- "$old" || die "cannot delete $old"
    log "Deleted $old (keeping the newest $RETENTION)"
  done
fi
log "Done: $(list_archives | grep -c . || true) archive(s) in $BACKUP_DIR"
