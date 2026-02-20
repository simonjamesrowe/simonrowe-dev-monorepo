# Research: Site-Wide Search

**Feature**: 005-site-search | **Date**: 2026-02-21

## 1. Elasticsearch Index Design

### Two-Index Strategy

The feature requires two distinct search experiences with different field requirements and query patterns:

1. **`site_search` index** -- Powers the global homepage search across blogs, jobs, and skills. Contains a normalized subset of fields common to all content types: `name`, `type`, `shortDescription`, `longDescription`, `image`, and `url`. This index favors breadth (all content types in one index) over depth (limited fields per document).

2. **`blog_search` index** -- Powers the dedicated blog search on the blog listing page. Contains blog-specific fields: `title`, `shortDescription`, `content` (full Markdown text), `tags`, `skills`, `image`, `publishedDate`, and `url`. This index favors depth (rich searchable fields) over breadth (single content type).

**Why two indices instead of one**: A single index with optional fields would require complex conditional logic in queries and result mapping. Two purpose-built indices keep queries simple, allow index-specific analyzer configurations, and align with the Elasticsearch principle of designing indices around query patterns rather than data models.

### Field Mapping Decisions

**Text vs Keyword types**:
- Searchable text fields (`name`, `shortDescription`, `longDescription`, `content`) use the `text` type with the standard analyzer for full-text search. These support partial matching, stemming, and relevance scoring.
- Filter/aggregation fields (`type`, `tags`, `skills`) use the `keyword` type for exact matching and terms aggregations.
- Display-only fields (`image`, `url`) use the `keyword` type with `index: false` since they are never searched -- only returned in results.

**Multi-field mapping for `name`/`title`**:
- The `name` and `title` fields use a multi-field mapping with both `text` (for full-text search) and `keyword` (for exact match boosting). This allows queries like "java" to match "Java Development" via full-text while also boosting exact title matches.

**Analyzer configuration**:
- The `standard` analyzer is sufficient for this scale. It provides tokenization, lowercasing, and stop-word handling out of the box.
- No custom analyzers are needed at this stage. If autocomplete/prefix matching becomes a requirement, an `edge_ngram` tokenizer can be added later without reindexing (using multi-field mappings).

### Query Strategy

**Site-wide search** uses a `multi_match` query across `name`, `shortDescription`, and `longDescription` with `type: best_fields`. Results are post-filtered by `type` field and grouped in the application layer (not via Elasticsearch aggregations) to maintain simplicity.

**Blog search** uses a `multi_match` query across `title`, `shortDescription`, `content`, `tags`, and `skills` with `type: best_fields` and field boosting: `title^3`, `tags^2`, `shortDescription^2`, `content^1`, `skills^1`.

**Result limiting**: Both queries return a maximum of 5 results per content type for the dropdown (configurable). The blog search returns up to 20 results for the listing page view.

### Index Settings

- **Number of shards**: 1 (dataset is small, single-shard indices avoid overhead)
- **Number of replicas**: 0 for local development, 1 for production
- **Refresh interval**: 1 second (default) -- sufficient for near-real-time search after indexing

## 2. Kafka Event-Driven Indexing

### Event Schema

Content management operations (create, update, delete) on blogs, jobs, and skills publish events to a Kafka topic. The search feature consumes these events to update the Elasticsearch indices.

**Topic**: `content-changes`

**Event structure**:
```json
{
  "eventType": "CREATED | UPDATED | DELETED",
  "contentType": "BLOG | JOB | SKILL",
  "contentId": "string (MongoDB document ID)",
  "timestamp": "ISO-8601 datetime"
}
```

**Design decisions**:
- The event carries only the content ID, not the full document payload. The consumer fetches the current state from MongoDB when processing CREATED/UPDATED events. This avoids stale data issues from event ordering and keeps events small.
- DELETED events only need the `contentId` and `contentType` to remove the document from the correct index.
- A single topic handles all content types. The consumer routes processing based on `contentType`.

### Consumer Design

**Consumer group**: `search-indexer`

**Processing logic**:
1. Receive `ContentChangeEvent` from `content-changes` topic.
2. Based on `eventType`:
   - **CREATED/UPDATED**: Fetch full document from MongoDB by `contentId`. Transform to Elasticsearch document. Upsert into the appropriate index (both `site_search` and `blog_search` for blogs, only `site_search` for jobs/skills).
   - **DELETED**: Delete document by `contentId` from the appropriate index(es).
3. Acknowledge the message after successful index operation.

**Error handling**:
- Failed index operations are logged with structured error details and the message is retried up to 3 times with exponential backoff.
- After max retries, the message is sent to a dead-letter topic (`content-changes.DLT`) for manual investigation.
- The periodic full sync (see below) acts as a safety net for any missed events.

**Idempotency**: Elasticsearch upserts (index with document ID) are naturally idempotent. Processing the same event twice produces the same result.

### Ordering Guarantees

Events for the same content item should be processed in order. Using `contentId` as the Kafka message key ensures all events for a given document go to the same partition, maintaining per-document ordering.

## 3. Periodic Full Synchronization

### Schedule

A `@Scheduled` job runs every 4 hours to fully re-synchronize all content from MongoDB to Elasticsearch. This ensures eventual consistency regardless of missed or failed Kafka events.

### Sync Strategy

**Bulk indexing approach**:
1. Fetch all published blogs, active jobs, and active skills from MongoDB.
2. Transform each document to the appropriate Elasticsearch document format.
3. Use Elasticsearch Bulk API to index all documents in batches of 100.
4. After successful bulk indexing, remove any documents from the indices that no longer exist in MongoDB (orphan cleanup).

**Orphan detection**:
1. After bulk indexing, query Elasticsearch for all document IDs in each index.
2. Compare against the set of IDs just indexed.
3. Delete any IDs present in Elasticsearch but not in the current MongoDB dataset.

**Why not delete-and-recreate indices**: Deleting and recreating indices causes a brief window where search returns no results. The upsert + orphan cleanup approach maintains search availability throughout the sync.

### Observability

- Log sync start/completion with document counts and duration.
- Emit Micrometer metrics: `search.sync.duration`, `search.sync.documents.indexed`, `search.sync.documents.deleted`.
- Log individual document failures without aborting the entire sync.

## 4. Typeahead / Debounce UX

### Frontend Debounce Strategy

**Debounce interval**: 300ms from last keystroke before sending the API request. This balances responsiveness (user doesn't wait too long) with efficiency (avoids flooding the backend with per-keystroke requests).

**Minimum query length**: 2 characters. Queries shorter than 2 characters produce too many irrelevant results and add unnecessary load.

**Implementation pattern**:
- Use a `useRef` + `setTimeout` pattern or a debounce utility (e.g., lodash `debounce` or a custom hook `useDebounce`) to delay the API call.
- Cancel any in-flight `fetch` request (via `AbortController`) when a new keystroke arrives before the previous request completes. This prevents stale results from overwriting newer results.

### Dropdown Behavior

- **Show**: When query length >= 2 and results are available (or loading).
- **Hide**: When the search input is cleared, loses focus (with a small delay to allow click events on results), or the user presses Escape.
- **Loading state**: Show a subtle loading indicator (spinner or skeleton) while the API request is in flight.
- **Empty state**: Display "No results found" when the API returns zero results.
- **Grouping (site search)**: Results grouped under "Blogs", "Jobs", "Skills" headers. Groups with zero results are hidden entirely (FR-015).
- **Navigation**: Clicking a result navigates to the content detail page. Keyboard navigation (arrow keys + Enter) is a nice-to-have for accessibility.

### Request Cancellation

Using the browser's `AbortController` API:
```typescript
const controller = new AbortController();
fetch(`/api/search?q=${query}`, { signal: controller.signal });
// On next keystroke:
controller.abort();
```

This prevents race conditions where a slow response for "jav" arrives after the response for "java".

## 5. Blog-Specific Search Index

### Why a Separate Index

The blog search (User Story 2) searches across fields not present in the site-wide index: full article `content`, `tags`, and `skills`. Including these fields in the `site_search` index would bloat it with data irrelevant to job and skill searches, and would require different query boosting logic.

The `blog_search` index provides:
- Full-text search across the complete Markdown content body.
- Faceted search by tags and skills (for potential future filtering).
- Publication date for sorting and display.
- Optimized field boosting for blog-specific relevance (title > tags > description > content).

### Content Indexing

When a blog is created or updated:
1. Index into `site_search` with: `name` = blog title, `type` = "blog", `shortDescription`, `longDescription` = null (blogs use content instead), `image`, `url`.
2. Index into `blog_search` with: `title`, `shortDescription`, `content` (full Markdown), `tags` (array of tag names), `skills` (array of skill names), `image`, `publishedDate`, `url`.

The Markdown content is indexed as-is (with Markdown syntax). Elasticsearch's standard analyzer handles this acceptably -- Markdown syntax tokens are tokenized and largely ignored in scoring. Stripping Markdown before indexing is a premature optimization that can be added later if search quality suffers.

### Blog Search Results

Blog search results include additional fields not present in site-wide results:
- `title` (same as name in site_search)
- `shortDescription`
- `image`
- `publishedDate` (formatted for display)
- `url`

The frontend `BlogSearch` component renders these with a different layout than the global search dropdown, showing the publication date prominently.

## 6. Performance Considerations

### 500ms Response Time Target

The end-to-end target of 500ms includes:
- Frontend debounce: 300ms (intentional delay, not counted toward response time)
- Network round-trip: ~50ms (local/CDN)
- Backend processing: ~20ms (Spring controller + service layer)
- Elasticsearch query: ~10-50ms (for dataset of ~100 documents)
- Response serialization + transfer: ~10ms

The Elasticsearch query is the primary variable. At the current dataset scale (~100 documents), query times will be well under 50ms. No caching layer is needed.

### Connection Pooling

The Elasticsearch Java client uses Apache HttpAsyncClient with a configurable connection pool. Default settings (max 10 connections per route) are sufficient for the expected load.

## 7. Edge Case Handling

| Edge Case | Approach |
|-----------|----------|
| Special characters in query | Elasticsearch handles special chars in `multi_match` queries. No manual escaping needed for standard analyzer. |
| Very long queries (1000+ chars) | Truncate query to 200 characters on the backend before sending to Elasticsearch. |
| Rapid typing (many keystrokes) | Frontend debounce (300ms) + AbortController cancellation prevents request floods. |
| Missing thumbnail image | Return `null` for `image` field. Frontend renders a placeholder/default image. |
| Elasticsearch unavailable | SearchService catches connection exceptions, logs error, returns empty results with a 200 status (degraded but not broken). Frontend shows "Search unavailable" message. |
| Results exceed 100 in a category | Limit results to 5 per category (site search dropdown) or 20 (blog search). Elasticsearch `size` parameter handles this. |
| Partial word matching | Standard analyzer tokenizes on word boundaries. "java" matches documents containing "java" but not "javascript". If prefix matching is needed, an `edge_ngram` analyzer can be added later. |
| Blog index out of sync | The 4-hour full sync corrects drift. Individual discrepancies resolve within one sync cycle. |
