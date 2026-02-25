#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BACKUP_DIR="${1:-/Users/simonrowe/backups}"
UPLOADS_DIR="${PROJECT_DIR}/backend/uploads"

# Find the latest backup tarball
LATEST_BACKUP=$(ls -t "$BACKUP_DIR"/strapi-backup-*.tar.gz 2>/dev/null | head -1)
if [ -z "$LATEST_BACKUP" ]; then
  echo "ERROR: No strapi-backup-*.tar.gz found in $BACKUP_DIR"
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

# Find the dump directory inside mongodb/
DUMP_DIR=$(find "$BACKUP_FOLDER/mongodb" -maxdepth 1 -type d ! -name mongodb | head -1)
echo "Dump directory: $DUMP_DIR"

# Detect backup format: Strapi backups have an upload_file collection, native backups don't
if [ -f "$DUMP_DIR/upload_file.bson" ]; then
  echo "Detected: Strapi backup (requires transformation)"

  # Clean previous files in container, copy dump, and restore into temp database
  echo ""
  echo "=== Restoring Strapi dump to temp database ==="
  docker exec "$CONTAINER" rm -rf /tmp/strapi-dump
  docker cp "$DUMP_DIR" "$CONTAINER:/tmp/strapi-dump"
  docker exec "$CONTAINER" mongorestore --drop --db strapi_backup /tmp/strapi-dump 2>&1 | grep -E '(restored|failed)'

  # Transform Strapi data to Spring Boot schema
  echo ""
  echo "=== Transforming data to Spring Boot schema ==="
  docker cp "$SCRIPT_DIR/migrate-strapi-data.js" "$CONTAINER:/tmp/migrate.js"
  docker exec "$CONTAINER" mongosh --quiet /tmp/migrate.js

  # Drop temp database
  echo ""
  echo "=== Cleaning up temp database ==="
  docker exec "$CONTAINER" mongosh --quiet --eval "db.getSiblingDB('strapi_backup').dropDatabase()"
  docker exec "$CONTAINER" rm -rf /tmp/strapi-dump
else
  echo "Detected: Native backup (direct restore)"

  # Direct restore into simonrowe database
  echo ""
  echo "=== Restoring to simonrowe database ==="
  docker exec "$CONTAINER" rm -rf /tmp/native-dump
  docker cp "$DUMP_DIR" "$CONTAINER:/tmp/native-dump"
  docker exec "$CONTAINER" mongorestore --drop --db simonrowe /tmp/native-dump 2>&1 | grep -E '(restored|failed)'
  docker exec "$CONTAINER" rm -rf /tmp/native-dump
fi

# Copy images
echo ""
echo "=== Copying images to $UPLOADS_DIR ==="
rm -rf "$UPLOADS_DIR"
mkdir -p "$UPLOADS_DIR"
if [ -d "$BACKUP_FOLDER/strapi-uploads" ] && [ "$(ls -A "$BACKUP_FOLDER/strapi-uploads" 2>/dev/null)" ]; then
  cp "$BACKUP_FOLDER/strapi-uploads/"* "$UPLOADS_DIR/"
  echo "Copied $(ls "$UPLOADS_DIR" | wc -l | tr -d ' ') files"
else
  echo "WARNING: No images found in backup"
fi

echo ""
echo "=== Restore complete ==="
echo "Restart the backend to pick up the new data."
