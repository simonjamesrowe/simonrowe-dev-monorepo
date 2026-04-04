# API Contracts: Blog Post Series Phase 2

No new API contracts needed. This feature uses existing blog system APIs:

- `GET /api/blogs` — List all published blogs
- `GET /api/blogs/latest?limit=3` — Latest blogs for homepage
- `GET /api/blogs/{id}` — Blog detail page
- `GET /api/search/blogs?q=query` — Blog search

All endpoints already support the blog data model with tags, skills, featured images, and markdown content. No modifications required.
