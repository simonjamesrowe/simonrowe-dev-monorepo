# Quickstart: Site-Wide Search

**Feature**: 005-site-search | **Date**: 2026-02-21

## Prerequisites

The local development environment from 001-project-infrastructure must be running. This provides:

- **Elasticsearch** on `localhost:9200`
- **Kafka** on `localhost:9092`
- **MongoDB** on `localhost:27017`
- **Backend API** on `localhost:8080`
- **Frontend** on `localhost:5173`

Start the environment if not already running:

```bash
docker compose up -d
```

Verify Elasticsearch is healthy:

```bash
curl -s http://localhost:9200/_cluster/health | jq .status
# Expected: "green" or "yellow"
```

## Backend Development

### Running the Backend

The backend starts as part of Docker Compose. For local development with hot reload:

```bash
cd backend
./gradlew bootRun
```

### Elasticsearch Index Setup

Indices are created automatically on application startup by the `ElasticsearchConfig` class. To manually verify indices exist:

```bash
# Check site_search index
curl -s http://localhost:9200/site_search | jq .

# Check blog_search index
curl -s http://localhost:9200/blog_search | jq .
```

### Triggering a Full Sync

The sync scheduler runs every 4 hours automatically. To trigger a manual sync during development, restart the backend application -- the sync runs on startup as well.

Alternatively, check the logs for sync activity:

```bash
docker compose logs backend | grep "SearchIndexSyncScheduler"
```

### Testing Search Endpoints

After content is indexed, test the search API:

```bash
# Site-wide search
curl -s "http://localhost:8080/api/search?q=java" | jq .

# Blog search
curl -s "http://localhost:8080/api/search/blogs?q=microservices" | jq .
```

### Publishing a Test Kafka Event

To test the Kafka consumer, publish a content change event:

```bash
echo '{"eventType":"UPDATED","contentType":"BLOG","contentId":"<mongo-id>","timestamp":"2026-02-21T10:00:00Z"}' | \
  docker compose exec -T kafka kafka-console-producer \
    --broker-list localhost:9092 \
    --topic content-changes
```

Then verify the document was re-indexed:

```bash
curl -s "http://localhost:9200/site_search/_doc/<mongo-id>" | jq .
```

## Frontend Development

### Running the Frontend

The frontend starts as part of Docker Compose. For local development with hot reload:

```bash
cd frontend
npm run dev
```

### Search Components

The search UI consists of four components:

| Component | Location | Used In |
|-----------|----------|---------|
| `SiteSearch` | `src/components/search/SiteSearch.tsx` | Homepage banner |
| `SearchDropdown` | `src/components/search/SearchDropdown.tsx` | Rendered by SiteSearch |
| `SearchResultGroup` | `src/components/search/SearchResultGroup.tsx` | Rendered by SearchDropdown |
| `BlogSearch` | `src/components/search/BlogSearch.tsx` | Blog listing page |

### Testing the Search UI

1. Navigate to `http://localhost:5173` (homepage).
2. Type at least 2 characters in the search box.
3. After a 300ms debounce, results should appear in a dropdown grouped by content type.
4. Click a result to navigate to the content detail page.

For blog search:
1. Navigate to `http://localhost:5173/blogs` (blog listing page).
2. Type at least 2 characters in the blog search field.
3. Results should appear with thumbnail, title, and publication date.

## Running Tests

### Backend Tests

```bash
cd backend

# Unit tests only
./gradlew test

# Integration tests (requires Docker for Testcontainers)
./gradlew integrationTest
```

Integration tests use Testcontainers to spin up Elasticsearch and Kafka instances. Docker must be running.

### Frontend Tests

```bash
cd frontend

# Run all tests
npm test

# Run search-specific tests
npm test -- --grep "search"

# Watch mode for development
npm test -- --watch
```

## Elasticsearch Troubleshooting

### View Index Contents

```bash
# All documents in site_search
curl -s "http://localhost:9200/site_search/_search?pretty&size=100" | jq '.hits.hits[]._source'

# All documents in blog_search
curl -s "http://localhost:9200/blog_search/_search?pretty&size=100" | jq '.hits.hits[]._source'
```

### Check Document Count

```bash
curl -s "http://localhost:9200/site_search/_count" | jq .count
curl -s "http://localhost:9200/blog_search/_count" | jq .count
```

### Delete and Recreate Indices

If indices get corrupted during development:

```bash
# Delete indices
curl -X DELETE "http://localhost:9200/site_search"
curl -X DELETE "http://localhost:9200/blog_search"

# Restart backend to recreate indices and trigger full sync
docker compose restart backend
```

### Check Elasticsearch Logs

```bash
docker compose logs elasticsearch
```

## Key Configuration

### Application Properties

The following properties are relevant to the search feature (configured in `application.yml`):

| Property | Default | Description |
|----------|---------|-------------|
| `spring.elasticsearch.uris` | `http://localhost:9200` | Elasticsearch connection URL |
| `spring.kafka.bootstrap-servers` | `localhost:9092` | Kafka broker address |
| `search.sync.cron` | `0 0 */4 * * *` | Full sync schedule (every 4 hours) |
| `search.site.max-results-per-group` | `5` | Max results per content type in site search |
| `search.blog.max-results` | `20` | Max results for blog search |
| `search.query.max-length` | `200` | Maximum query string length before truncation |
