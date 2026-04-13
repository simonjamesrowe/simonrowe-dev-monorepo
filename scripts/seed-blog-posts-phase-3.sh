#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=== Seed Blog Posts Phase 3 ==="
echo ""

# 1. Find the MongoDB container
CONTAINER=$(docker ps -q --filter "ancestor=mongo:8" | head -1)
if [ -z "$CONTAINER" ]; then
  echo "ERROR: No MongoDB container running (mongo:8). Start it with: docker compose up -d mongodb"
  exit 1
fi
echo "MongoDB container: $CONTAINER"

# 2. Verify featured images exist in uploads directory
UPLOADS_DIR="${PROJECT_DIR}/backend/uploads"

for img in blog-phase3-9-rag.jpg blog-phase3-10-aggregation.jpg; do
  if [ -f "$UPLOADS_DIR/$img" ]; then
    echo "Found $img in uploads/"
  else
    echo "WARNING: $img not found in uploads/ — blog post will have a broken image"
  fi
done

# 3. Copy migration script into the container and run via mongosh
MIGRATION_SCRIPT="${SCRIPT_DIR}/add-blog-posts-phase-3.js"
if [ ! -f "$MIGRATION_SCRIPT" ]; then
  echo "ERROR: Migration script not found at $MIGRATION_SCRIPT"
  exit 1
fi

echo ""
echo "=== Running blog posts migration script ==="
docker cp "$MIGRATION_SCRIPT" "$CONTAINER:/tmp/add-blog-posts-phase-3.js"
docker exec "$CONTAINER" mongosh --quiet /tmp/add-blog-posts-phase-3.js
docker exec "$CONTAINER" rm -f /tmp/add-blog-posts-phase-3.js

echo ""
echo "=== Blog posts seeding complete ==="
echo "Restart the backend to pick up the new data and trigger search indexing."
