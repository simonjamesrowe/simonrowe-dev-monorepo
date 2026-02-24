#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BACKUP_DIR="${1:-/Users/simonrowe/backups}"
UPLOADS_DIR="${PROJECT_DIR}/backend/uploads"
TIMESTAMP=$(date -u +"%Y%m%d_%H%M%S")
BACKUP_NAME="strapi-backup-${TIMESTAMP}"

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
docker cp "$CONTAINER:/tmp/mongodump/simonrowe" "$BACKUP_FOLDER/mongodb/strapi"
docker exec "$CONTAINER" rm -rf /tmp/mongodump
echo "Collections dumped: $(ls "$BACKUP_FOLDER/mongodb/strapi/"*.bson 2>/dev/null | wc -l | tr -d ' ')"

# Copy uploads
echo ""
echo "=== Copying uploads ==="
if [ -d "$UPLOADS_DIR" ] && [ "$(ls -A "$UPLOADS_DIR" 2>/dev/null)" ]; then
  cp -r "$UPLOADS_DIR" "$BACKUP_FOLDER/strapi-uploads"
  echo "Files copied: $(ls "$BACKUP_FOLDER/strapi-uploads" | wc -l | tr -d ' ')"
else
  mkdir -p "$BACKUP_FOLDER/strapi-uploads"
  echo "WARNING: No uploads found at $UPLOADS_DIR (empty backup)"
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
