#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Seed Global Head of Engineering Job & Skills ==="
echo ""

# 1. Ensure Global logo is in uploads
LOGO_SRC="${PROJECT_DIR}/specs/009-global-job/attachments/global-logo.jpg"
LOGO_DST="${PROJECT_DIR}/backend/uploads/global-logo.jpg"
if [ -f "$LOGO_DST" ]; then
  echo "Global logo already exists at $LOGO_DST"
else
  if [ -f "$LOGO_SRC" ]; then
    cp "$LOGO_SRC" "$LOGO_DST"
    echo "Copied Global logo to $LOGO_DST"
  else
    echo "WARNING: Global logo not found at $LOGO_SRC"
  fi
fi

# 2. Find the MongoDB container
CONTAINER=$(docker ps -q --filter "ancestor=mongo:8" | head -1)
if [ -z "$CONTAINER" ]; then
  echo "ERROR: No MongoDB container running (mongo:8). Start it with: docker compose up -d mongodb"
  exit 1
fi
echo "MongoDB container: $CONTAINER"

# 3. Copy migration script into the container and run via mongosh
MIGRATION_SCRIPT="${SCRIPT_DIR}/add-global-job-data.js"
if [ ! -f "$MIGRATION_SCRIPT" ]; then
  echo "ERROR: Migration script not found at $MIGRATION_SCRIPT"
  exit 1
fi

echo ""
echo "=== Running migration script ==="
docker cp "$MIGRATION_SCRIPT" "$CONTAINER:/tmp/add-global-job-data.js"
docker exec "$CONTAINER" mongosh --quiet /tmp/add-global-job-data.js
docker exec "$CONTAINER" rm -f /tmp/add-global-job-data.js

echo ""
echo "=== Seed complete ==="
echo "Restart the backend to pick up the new data."
