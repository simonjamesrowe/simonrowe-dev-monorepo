#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BACKUP_DIR="${1:-/Users/simonrowe/backups}"
UPLOADS_DIR="${PROJECT_DIR}/backend/uploads"
TIMESTAMP=$(date -u +"%Y%m%d_%H%M%S")
BACKUP_NAME="backup-${TIMESTAMP}"

# Find the MongoDB container
CONTAINER=$(docker ps -q --filter "ancestor=mongo:8" | head -1)
if [ -z "$CONTAINER" ]; then
  echo "ERROR: No MongoDB container running (mongo:8). Start it with: docker compose up -d mongodb"
  exit 1
fi
echo "MongoDB container: $CONTAINER"

# Create temp directory for assembling the backup
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT
BACKUP_FOLDER="${TEMP_DIR}/${BACKUP_NAME}"
mkdir -p "$BACKUP_FOLDER"

# Dump the simonrowe database
echo "=== Dumping MongoDB (simonrowe) ==="
docker exec "$CONTAINER" mongodump --db simonrowe --out /tmp/mongodump 2>&1 | grep -E '(done dumping|writing)'
mkdir -p "$BACKUP_FOLDER/mongodb"
docker cp "$CONTAINER:/tmp/mongodump/simonrowe" "$BACKUP_FOLDER/mongodb/simonrowe"
docker exec "$CONTAINER" rm -rf /tmp/mongodump
echo "Collections dumped: $(ls "$BACKUP_FOLDER/mongodb/simonrowe/"*.bson 2>/dev/null | wc -l | tr -d ' ')"

# Copy uploads
echo ""
echo "=== Copying uploads ==="
if [ -d "$UPLOADS_DIR" ] && [ "$(ls -A "$UPLOADS_DIR" 2>/dev/null)" ]; then
  cp -r "$UPLOADS_DIR" "$BACKUP_FOLDER/uploads"
  echo "Files copied: $(ls "$BACKUP_FOLDER/uploads" | wc -l | tr -d ' ')"
else
  mkdir -p "$BACKUP_FOLDER/uploads"
  echo "WARNING: No uploads found at $UPLOADS_DIR (empty backup)"
fi

# Snapshot Elasticsearch
echo ""
echo "=== Snapshotting Elasticsearch ==="
ES_URL="http://localhost:9200"
ES_REPO="simonrowe_backup"
ES_SNAPSHOT="snapshot_${TIMESTAMP}"

if curl -sf "${ES_URL}/_cluster/health" > /dev/null 2>&1; then
  # Register filesystem repository (idempotent)
  curl -sf -X PUT "${ES_URL}/_snapshot/${ES_REPO}" \
    -H 'Content-Type: application/json' \
    -d '{"type":"fs","settings":{"location":"/usr/share/elasticsearch/backups","compress":true}}' > /dev/null

  # Create snapshot
  echo "Creating snapshot: ${ES_SNAPSHOT}"
  SNAP_RESULT=$(curl -sf -X PUT "${ES_URL}/_snapshot/${ES_REPO}/${ES_SNAPSHOT}?wait_for_completion=true" \
    -H 'Content-Type: application/json' \
    -d '{"ignore_unavailable":true,"include_global_state":false}')
  echo "Snapshot result: $(echo "$SNAP_RESULT" | grep -o '"state":"[^"]*"' || echo 'completed')"

  # Copy snapshot data from Docker volume
  ES_CONTAINER=$(docker ps -q --filter "ancestor=elasticsearch:8.17.0" | head -1)
  if [ -n "$ES_CONTAINER" ]; then
    mkdir -p "$BACKUP_FOLDER/elasticsearch"
    docker cp "$ES_CONTAINER:/usr/share/elasticsearch/backups/." "$BACKUP_FOLDER/elasticsearch/"
    echo "Elasticsearch snapshot copied to backup"
  fi
else
  echo "WARNING: Elasticsearch not available at ${ES_URL} — skipping ES backup"
fi

# Create tarball
echo ""
echo "=== Creating backup archive ==="
mkdir -p "$BACKUP_DIR"
tar -czf "${BACKUP_DIR}/${BACKUP_NAME}.tar.gz" -C "$TEMP_DIR" "$BACKUP_NAME"
FILESIZE=$(ls -lh "${BACKUP_DIR}/${BACKUP_NAME}.tar.gz" | awk '{print $5}')
echo "Backup created: ${BACKUP_DIR}/${BACKUP_NAME}.tar.gz ($FILESIZE)"

echo ""
echo "=== Backup complete ==="
