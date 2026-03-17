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

echo ""
echo "=== Restore complete ==="
echo "Restart the backend to pick up the new data."
