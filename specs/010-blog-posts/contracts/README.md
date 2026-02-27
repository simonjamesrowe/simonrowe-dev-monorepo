# Contracts: 010-blog-posts

No new API contracts are required for this feature.

This feature uses the existing blog system APIs defined in `specs/003-blog-system/contracts/blog-api.yaml`:

- `GET /api/blogs` — List all published blogs (includes new posts automatically)
- `GET /api/blogs/{id}` — Get full blog detail with markdown content
- `GET /api/blogs/latest?limit=3` — Latest blogs for homepage preview
- `GET /api/search/blogs?q={query}` — Full-text blog search via Elasticsearch

The new blog posts are inserted directly into MongoDB via a migration script, not through a new API endpoint. The existing `IndexService.fullSyncBlogIndex()` handles Elasticsearch indexing.
