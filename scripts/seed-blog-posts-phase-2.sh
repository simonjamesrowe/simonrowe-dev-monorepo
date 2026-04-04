#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=== Seed Blog Posts Phase 2 ==="
echo ""

# 1. Find the MongoDB container
CONTAINER=$(docker ps -q --filter "ancestor=mongo:8" | head -1)
if [ -z "$CONTAINER" ]; then
  echo "ERROR: No MongoDB container running (mongo:8). Start it with: docker compose up -d mongodb"
  exit 1
fi
echo "MongoDB container: $CONTAINER"

# 2. Copy featured images to uploads directory
ATTACHMENTS_DIR="${PROJECT_DIR}/specs/013-blog-posts-phase-2/attachments"
UPLOADS_DIR="${PROJECT_DIR}/backend/uploads"

if [ -d "$ATTACHMENTS_DIR" ]; then
  mkdir -p "$UPLOADS_DIR"
  for img in blog-phase2-6-cms.jpg blog-phase2-7-ai-chat.jpg blog-phase2-8-production.jpg; do
    if [ -f "$ATTACHMENTS_DIR/$img" ]; then
      cp "$ATTACHMENTS_DIR/$img" "$UPLOADS_DIR/$img"
      echo "Copied $img to uploads/"
    else
      echo "WARNING: $img not found in attachments/ — skipping"
    fi
  done
else
  echo "WARNING: Attachments directory not found at $ATTACHMENTS_DIR — skipping image copy"
fi

# 3. Copy migration script into the container and run via mongosh
MIGRATION_SCRIPT="${SCRIPT_DIR}/add-blog-posts-phase-2.js"
if [ ! -f "$MIGRATION_SCRIPT" ]; then
  echo "ERROR: Migration script not found at $MIGRATION_SCRIPT"
  exit 1
fi

echo ""
echo "=== Running blog posts migration script ==="
docker cp "$MIGRATION_SCRIPT" "$CONTAINER:/tmp/add-blog-posts-phase-2.js"
docker exec "$CONTAINER" mongosh --quiet /tmp/add-blog-posts-phase-2.js
docker exec "$CONTAINER" rm -f /tmp/add-blog-posts-phase-2.js

echo ""
echo "=== Blog posts seeding complete ==="
echo "Restart the backend to pick up the new data and trigger search indexing."
