#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== Update Profile & Global Job Content ==="
echo ""

# 1. Find the MongoDB container
CONTAINER=$(docker ps -q --filter "ancestor=mongo:8" | head -1)
if [ -z "$CONTAINER" ]; then
  echo "ERROR: No MongoDB container running (mongo:8). Start it with: docker compose up -d mongodb"
  exit 1
fi
echo "MongoDB container: $CONTAINER"

# 2. Copy migration script into the container and run via mongosh
MIGRATION_SCRIPT="${SCRIPT_DIR}/update-profile-job-content.js"
if [ ! -f "$MIGRATION_SCRIPT" ]; then
  echo "ERROR: Migration script not found at $MIGRATION_SCRIPT"
  exit 1
fi

echo ""
echo "=== Running content update script ==="
docker cp "$MIGRATION_SCRIPT" "$CONTAINER:/tmp/update-profile-job-content.js"
docker exec "$CONTAINER" mongosh --quiet /tmp/update-profile-job-content.js
docker exec "$CONTAINER" rm -f /tmp/update-profile-job-content.js

echo ""
echo "=== Content update complete ==="
echo "Restart the backend to pick up the new data."
