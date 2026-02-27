#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Seed Blog Posts: Rebuilding simonrowe.dev with AI ==="
echo ""

# 1. Copy featured images to uploads (skip if already present)
ATTACHMENTS_DIR="${PROJECT_DIR}/specs/010-blog-posts/attachments"
UPLOADS_DIR="${PROJECT_DIR}/backend/uploads"

IMAGES=(
  "blog-rebuild-1-specification.png"
  "blog-rebuild-2-foundation.png"
  "blog-rebuild-3-parallel.png"
  "blog-rebuild-4-migration.png"
  "blog-rebuild-5-lessons.png"
)

for img in "${IMAGES[@]}"; do
  DST="${UPLOADS_DIR}/${img}"
  if [ -f "$DST" ]; then
    echo "Image already exists: ${img}"
  else
    SRC="${ATTACHMENTS_DIR}/${img}"
    if [ -f "$SRC" ]; then
      cp "$SRC" "$DST"
      echo "Copied ${img} to uploads/"
    else
      echo "WARNING: Image not found at ${SRC}"
    fi
  fi
done

# 2. Find the MongoDB container
CONTAINER=$(docker ps -q --filter "ancestor=mongo:8" | head -1)
if [ -z "$CONTAINER" ]; then
  echo "ERROR: No MongoDB container running (mongo:8). Start it with: docker compose up -d mongodb"
  exit 1
fi
echo ""
echo "MongoDB container: $CONTAINER"

# 3. Copy migration script into the container and run via mongosh
MIGRATION_SCRIPT="${SCRIPT_DIR}/add-blog-posts.js"
if [ ! -f "$MIGRATION_SCRIPT" ]; then
  echo "ERROR: Migration script not found at $MIGRATION_SCRIPT"
  exit 1
fi

echo ""
echo "=== Running migration script ==="
docker cp "$MIGRATION_SCRIPT" "$CONTAINER:/tmp/add-blog-posts.js"
docker exec "$CONTAINER" mongosh --quiet /tmp/add-blog-posts.js
docker exec "$CONTAINER" rm -f /tmp/add-blog-posts.js

echo ""
echo "=== Seed complete ==="
echo "Restart the backend to trigger Elasticsearch reindexing."
