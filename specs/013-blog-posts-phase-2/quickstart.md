# Quickstart: Blog Post Series Phase 2

## Prerequisites

1. MongoDB container running (`docker compose up -d mongodb`)
2. Featured images generated and placed in `specs/013-blog-posts-phase-2/attachments/`:
   - `blog-phase2-6-cms.jpg`
   - `blog-phase2-7-ai-chat.jpg`
   - `blog-phase2-8-production.jpg`

## Run the Migration

```bash
# From project root
./scripts/seed-blog-posts-phase-2.sh
```

This will:
1. Copy featured images to `backend/uploads/`
2. Run the MongoDB migration script to create 9 new tags and 3 blog posts
3. Clean up temporary files from the container

## Verify

1. **Start the backend**: `./scripts/start-backend.sh`
2. **Check blog listing**: Visit `/blogs` — should show 3 new posts
3. **Check homepage**: Latest blogs section should include the newest post
4. **Check search**: Search for "Auth0", "Spring AI", or "Docker Compose"

## Re-run Safety

The migration script is idempotent. Running it again will:
- Skip tags that already exist (matched by name)
- Skip blog posts that already exist (matched by title)
- Not create duplicates

## Manual Alternative

If you prefer to use the admin CMS instead of the migration script:
1. Start the backend and frontend
2. Log in to `/admin` with Auth0 credentials
3. Create 9 new tags via the Tags admin page
4. Create 3 blog posts via the Blog editor, uploading featured images through the Media Library
