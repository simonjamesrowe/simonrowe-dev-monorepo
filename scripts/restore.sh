#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BACKUP_DIR="${1:-/Users/simonrowe/backups}"
UPLOADS_DIR="${PROJECT_DIR}/backend/uploads"

# Find the latest backup tarball
LATEST_BACKUP=$(ls -t "$BACKUP_DIR"/backup-*.tar.gz 2>/dev/null | head -1)
if [ -z "$LATEST_BACKUP" ]; then
  echo "ERROR: No backup-*.tar.gz found in $BACKUP_DIR"
  exit 1
fi
echo "Using backup: $LATEST_BACKUP"

# Extract to temp directory
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT
echo "Extracting to $TEMP_DIR..."
tar -xzf "$LATEST_BACKUP" -C "$TEMP_DIR"

# Find the extracted backup folder
BACKUP_FOLDER=$(find "$TEMP_DIR" -maxdepth 2 -type d -name "mongodb" -exec dirname {} \;)
if [ -z "$BACKUP_FOLDER" ]; then
  echo "ERROR: Could not find mongodb directory in backup"
  exit 1
fi
echo "Backup folder: $BACKUP_FOLDER"

# Find the MongoDB container
CONTAINER=$(docker ps -q --filter "ancestor=mongo:8" | head -1)
if [ -z "$CONTAINER" ]; then
  echo "ERROR: No MongoDB container running (mongo:8). Start it with: docker compose up -d mongodb"
  exit 1
fi
echo "MongoDB container: $CONTAINER"

# Restore MongoDB
echo ""
echo "=== Restoring MongoDB (simonrowe) ==="
DUMP_DIR="$BACKUP_FOLDER/mongodb/simonrowe"
if [ ! -d "$DUMP_DIR" ]; then
  echo "ERROR: Could not find simonrowe dump directory at $DUMP_DIR"
  exit 1
fi
docker exec "$CONTAINER" rm -rf /tmp/native-dump
docker cp "$DUMP_DIR" "$CONTAINER:/tmp/native-dump"
docker exec "$CONTAINER" mongorestore --drop --db simonrowe /tmp/native-dump 2>&1 | grep -E '(restored|failed)'
docker exec "$CONTAINER" rm -rf /tmp/native-dump

# Copy uploads
echo ""
echo "=== Copying uploads to $UPLOADS_DIR ==="
rm -rf "$UPLOADS_DIR"
mkdir -p "$UPLOADS_DIR"
if [ -d "$BACKUP_FOLDER/uploads" ] && [ "$(ls -A "$BACKUP_FOLDER/uploads" 2>/dev/null)" ]; then
  cp "$BACKUP_FOLDER/uploads/"* "$UPLOADS_DIR/"
  echo "Copied $(ls "$UPLOADS_DIR" | wc -l | tr -d ' ') files"
else
  echo "WARNING: No uploads found in backup"
fi

# Restore Elasticsearch
echo ""
echo "=== Restoring Elasticsearch ==="
ES_URL="http://localhost:9200"
ES_REPO="simonrowe_backup"

if curl -sf "${ES_URL}/_cluster/health" > /dev/null 2>&1; then
  if [ -d "$BACKUP_FOLDER/elasticsearch" ] && [ "$(ls -A "$BACKUP_FOLDER/elasticsearch" 2>/dev/null)" ]; then
    # Copy snapshot data into Docker volume
    ES_CONTAINER=$(docker ps -q --filter "ancestor=elasticsearch:8.17.0" | head -1)
    if [ -n "$ES_CONTAINER" ]; then
      docker exec "$ES_CONTAINER" rm -rf /usr/share/elasticsearch/backups/*
      docker cp "$BACKUP_FOLDER/elasticsearch/." "$ES_CONTAINER:/usr/share/elasticsearch/backups/"

      # Register filesystem repository
      curl -sf -X PUT "${ES_URL}/_snapshot/${ES_REPO}" \
        -H 'Content-Type: application/json' \
        -d '{"type":"fs","settings":{"location":"/usr/share/elasticsearch/backups","compress":true}}' > /dev/null

      # Find the latest snapshot in the repository
      SNAP_NAME=$(curl -sf "${ES_URL}/_snapshot/${ES_REPO}/_all" \
        | grep -o '"snapshot":"[^"]*"' | tail -1 | sed 's/"snapshot":"//;s/"//')

      if [ -n "$SNAP_NAME" ]; then
        echo "Restoring snapshot: ${SNAP_NAME}"
        # Close indices that exist before restore
        curl -sf -X POST "${ES_URL}/_all/_close?ignore_unavailable=true" > /dev/null 2>&1 || true
        # Restore
        curl -sf -X POST "${ES_URL}/_snapshot/${ES_REPO}/${SNAP_NAME}/_restore?wait_for_completion=true" \
          -H 'Content-Type: application/json' \
          -d '{"ignore_unavailable":true,"include_global_state":false}' > /dev/null
        # Reopen indices
        curl -sf -X POST "${ES_URL}/_all/_open" > /dev/null 2>&1 || true
        echo "Elasticsearch snapshot restored"
      else
        echo "WARNING: No snapshots found in repository"
      fi
    fi
  else
    echo "WARNING: No Elasticsearch data found in backup"
  fi
else
  echo "WARNING: Elasticsearch not available — skipping ES restore"
fi

echo ""
echo "=== Restore complete ==="
echo "Restart the backend to pick up the new data."
