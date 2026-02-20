# Quickstart: Blog System Verification

**Feature**: 003-blog-system | **Date**: 2026-02-21

## Prerequisites

- Docker and Docker Compose installed
- Repository cloned: `git clone <repo-url> && cd simonrowe-dev-monorepo`
- MongoDB backup data restored (18 blogs, 26 tags)

## Start the Development Environment

```bash
docker compose up -d
```

This starts all services: backend (Spring Boot), frontend (React dev server), MongoDB, Elasticsearch, and Kafka.

Wait for all services to be healthy:

```bash
docker compose ps
```

Expected: All containers show `healthy` or `running` status.

## Verification Steps

### Step 1: Verify Backend API (Blog Listing)

```bash
curl -s http://localhost:8080/api/blogs | jq length
```

**Expected**: `18` (all published blog posts from the backup data)

```bash
curl -s http://localhost:8080/api/blogs | jq '.[0] | keys'
```

**Expected**: Each blog object contains `id`, `title`, `shortDescription`, `featuredImageUrl`, `createdDate`, `tags`, `skills`.

### Step 2: Verify Blog Detail Endpoint

```bash
# Get the first blog ID
BLOG_ID=$(curl -s http://localhost:8080/api/blogs | jq -r '.[0].id')

# Fetch the full blog detail
curl -s http://localhost:8080/api/blogs/$BLOG_ID | jq '{title, hasContent: (.content | length > 0), tagCount: (.tags | length)}'
```

**Expected**: Title is present, `hasContent` is `true`, `tagCount` is >= 0.

### Step 3: Verify Unpublished Posts Are Excluded

```bash
# Attempt to access a known unpublished post (if any exist)
# The listing should only contain published posts
curl -s http://localhost:8080/api/blogs | jq '[.[] | select(.published == false)] | length'
```

**Expected**: `0` (no unpublished posts in the response, and the `published` field should not even be in the response since only published posts are returned).

### Step 4: Verify Latest Posts Endpoint (Homepage Preview)

```bash
curl -s "http://localhost:8080/api/blogs/latest?limit=3" | jq length
```

**Expected**: `3`

```bash
# Verify posts are sorted by newest first
curl -s "http://localhost:8080/api/blogs/latest?limit=3" | jq '.[].createdDate'
```

**Expected**: Dates in descending order (newest first).

### Step 5: Verify Blog Search

```bash
# Wait for Elasticsearch indexing to complete (may take a few seconds on first startup)
sleep 5

# Search for a known term
curl -s "http://localhost:8080/api/search/blogs?q=kubernetes" | jq '.[].title'
```

**Expected**: Returns blog posts containing "kubernetes" in their title, description, or content.

```bash
# Verify search result format
curl -s "http://localhost:8080/api/search/blogs?q=spring" | jq '.[0] | keys'
```

**Expected**: Each result contains `id`, `title`, `thumbnailImage`, `createdDate`.

### Step 6: Verify Non-Existent Blog Returns 404

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/blogs/000000000000000000000000
```

**Expected**: `404`

### Step 7: Verify Frontend Blog Listing Page

Open a browser and navigate to:

```
http://localhost:3000/blogs
```

**Verify**:
- [ ] Page displays a grid of blog posts
- [ ] Grid uses mixed layout (vertical 1/3 width cards alternating with horizontal 2/3 width card pairs)
- [ ] Each card shows: featured image (or placeholder), title, short description, date, tags
- [ ] Dates are in human-readable format (e.g., "5 July 2020")
- [ ] Posts with no tags display correctly without layout breaking
- [ ] Blog search typeahead field is visible at the top

### Step 8: Verify Frontend Blog Detail Page

Click on any blog post card from the listing.

**Verify**:
- [ ] URL changes to `/blogs/{id}`
- [ ] Featured image displays at the top
- [ ] Title, author ("Simon Rowe"), date, and tags are visible
- [ ] Markdown content renders correctly: headings, paragraphs, lists, blockquotes
- [ ] Code blocks display with syntax highlighting (colored keywords, line formatting)
- [ ] External links open in new browser tabs
- [ ] Internal links navigate within the same tab

### Step 9: Verify Frontend Blog Search

On the blog listing page, type "docker" into the search field.

**Verify**:
- [ ] Results appear in a dropdown within 500ms
- [ ] Each result shows a thumbnail image, title, and date
- [ ] Clicking a result navigates to that blog post's detail page
- [ ] The dropdown closes after clicking a result
- [ ] Searching for a non-matching term (e.g., "zzzzxxx") shows no results or a "no results" message

### Step 10: Verify Homepage Blog Preview

Navigate to the homepage:

```
http://localhost:3000/
```

Scroll to the blog preview section.

**Verify**:
- [ ] Exactly 3 blog posts are displayed
- [ ] They are the 3 most recently published posts
- [ ] Each preview shows featured image, title, and short description
- [ ] Clicking a preview navigates to the blog detail page
- [ ] A "View All Posts" link is visible and navigates to `/blogs`

## Backend Health Check

```bash
# Management port (separate from application traffic per constitution)
curl -s http://localhost:8081/actuator/health | jq .status
```

**Expected**: `"UP"`

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| `curl` returns empty array `[]` | MongoDB data not seeded | Run the data migration script or restore the backup |
| Search returns no results | Elasticsearch index not populated | Check Kafka consumer logs, or trigger a manual re-index |
| Frontend shows blank page | Backend not running or CORS misconfigured | Check `docker compose logs backend` and verify CORS settings |
| Code blocks render without highlighting | `react-syntax-highlighter` not installed | Run `npm install` in the frontend directory |
| Images show as broken | Image URLs not migrated from Strapi format | Verify `featuredImageUrl` values in MongoDB and static file serving configuration |
