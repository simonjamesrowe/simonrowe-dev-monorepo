# Quickstart: Blog Post Series - Rebuilding simonrowe.dev with AI

**Feature**: 010-blog-posts
**Date**: 2026-02-27

## Prerequisites

- Docker and Docker Compose running
- MongoDB accessible via `mongosh`
- Backend application buildable (`./gradlew bootBuildImage`)
- Existing data restored via `scripts/restore-backup.sh`

## Implementation Steps

### 1. Create the shell wrapper and migration script

Create two files following the `seed-global-job.sh` + `add-global-job-data.js` pattern:

```bash
# Shell wrapper (entry point — never run the .js directly)
scripts/seed-blog-posts.sh

# MongoDB migration script (called by wrapper)
scripts/add-blog-posts.js
```

**Shell wrapper** (`seed-blog-posts.sh`) must:
1. Use `#!/usr/bin/env bash` with `set -euo pipefail`
2. Resolve `SCRIPT_DIR` and `PROJECT_DIR`
3. Copy featured images from `specs/010-blog-posts/attachments/` to `backend/uploads/`
4. Find running MongoDB container (`docker ps -q --filter "ancestor=mongo:8"`)
5. Copy `.js` script into container, run via `mongosh --quiet`, clean up

**Migration script** (`add-blog-posts.js`) must:
1. Connect to the `simonrowe` database
2. Check for existing blog posts with matching titles (idempotent)
3. Insert new tags into the `tags` collection (skip if already exist)
4. Resolve tag and skill `_id` values by name
5. Insert 5 blog post documents with DBRef arrays for tags and skills
6. Set `published: true` and appropriate `createdDate` timestamps

### 2. Create featured images

Place 5 featured images in `backend/uploads/`:

```bash
backend/uploads/blog-rebuild-1-specification.png
backend/uploads/blog-rebuild-2-foundation.png
backend/uploads/blog-rebuild-3-parallel.png
backend/uploads/blog-rebuild-4-migration.png
backend/uploads/blog-rebuild-5-lessons.png
```

### 3. Run the migration

```bash
# Start MongoDB if not running
docker compose up -d mongodb

# Run via the shell wrapper (never run .js directly)
./scripts/seed-blog-posts.sh
```

### 4. Verify the data

```bash
# Check blog count
docker compose exec mongodb mongosh --eval "db.getSiblingDB('simonrowe').blogs.countDocuments({published: true})"

# Check new tags
docker compose exec mongodb mongosh --eval "db.getSiblingDB('simonrowe').tags.find({name: {\\$in: ['SpecKit', 'Conductor']}})"

# Verify a blog post has DBRef tags
docker compose exec mongodb mongosh --eval "db.getSiblingDB('simonrowe').blogs.findOne({title: /From Zero to Specification/})"
```

### 5. Reindex Elasticsearch

Restart the backend application to trigger `fullSyncBlogIndex()` and `fullSyncSiteIndex()`:

```bash
docker compose restart backend
```

Or if running locally:
```bash
./gradlew :backend:bootRun
```

The IndexService automatically syncs all published blog content to Elasticsearch on startup.

### 6. Verify end-to-end

```bash
# Start all services
docker compose up -d

# Check blog listing
curl http://localhost:8080/api/blogs | jq '.[] | .title'

# Check search
curl 'http://localhost:8080/api/search/blogs?q=Claude+Code' | jq '.[].title'

# Check homepage preview
curl 'http://localhost:8080/api/blogs/latest?limit=3' | jq '.[].title'
```

### 7. Frontend verification

Open the frontend at `http://localhost:3000`:
1. Navigate to `/blogs` — verify all 5 new posts appear with images and tags
2. Click a blog post — verify markdown renders with code blocks and syntax highlighting
3. Use site search for "SpecKit" — verify matching posts appear
4. Check homepage — verify latest blog preview includes rebuild series posts

## Key Files

| File | Purpose |
|------|---------|
| `scripts/seed-blog-posts.sh` | Shell wrapper entry point (new) |
| `scripts/add-blog-posts.js` | MongoDB migration script (new, called by wrapper) |
| `backend/uploads/blog-rebuild-*.png` | Featured images (new, 5 files) |
| `backend/src/main/java/com/simonrowe/blog/Blog.java` | Blog entity (existing, no changes) |
| `backend/src/main/java/com/simonrowe/blog/Tag.java` | Tag entity (existing, no changes) |
| `backend/src/main/java/com/simonrowe/search/IndexService.java` | Search indexing (existing, no changes) |

## Troubleshooting

**Tags not showing on blog cards?**
Verify DBRef format: `{ "$ref": "tags", "$id": ObjectId("...") }`. The `$ref` must be `"tags"` (collection name).

**Skills not linking?**
Check the `skills` collection (not `skill_groups`) has entries matching the skill names. Blog DBRefs point to the flat `skills` collection.

**Search not finding new posts?**
Restart the backend to trigger a full Elasticsearch reindex. Check logs for `Full sync of blog_search completed`.

**Images not loading?**
Verify files exist in `backend/uploads/` and the `UPLOADS_PATH` environment variable is configured. Spring serves files from `/uploads/**` via ResourceHandlerRegistry.
