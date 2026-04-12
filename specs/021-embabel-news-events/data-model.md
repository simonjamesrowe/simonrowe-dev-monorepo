# Data Model: Embabel News & Events Aggregation

**Feature**: 021-embabel-news-events  
**Date**: 2026-04-12

## Entities

### AggregatedArticle

MongoDB collection: `aggregated_articles`

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | String | @Id, auto-generated | MongoDB document ID |
| title | String | required, max 500 | Article title from source |
| sourceName | String | required | Human-readable source name (e.g., "AI Native Dev") |
| sourceUrl | String | required | Base URL of the source site |
| originalUrl | String | required, unique index | URL of the original article (dedup key) |
| summary | String | required, max 1000 | AI-generated 2-3 sentence summary |
| fullContent | String | optional | Full scraped article content (used for embedding) |
| author | String | optional | Article author if available |
| publishedDate | Instant | optional | Original publication date |
| fetchedAt | Instant | required | When the article was scraped |
| visible | boolean | default true | Admin can hide items |
| imageUrl | String | optional | Featured image URL from source |

**Indexes**:
- Unique on `originalUrl` (duplicate detection)
- Compound: `(visible, publishedDate DESC)` (listing queries)
- On `sourceName` (filtering by source)

### AggregatedEvent

MongoDB collection: `aggregated_events`

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | String | @Id, auto-generated | MongoDB document ID |
| title | String | required, max 500 | Event title |
| sourceName | String | required | Human-readable source name |
| originalUrl | String | required, unique index | URL of the original event page (dedup key) |
| summary | String | optional, max 1000 | AI-generated summary |
| description | String | optional | Full event description |
| eventDate | Instant | required | Event start date/time |
| eventEndDate | Instant | optional | Event end date/time |
| venue | String | optional | Venue name |
| location | String | optional | Address or "Online" |
| fetchedAt | Instant | required | When the event was scraped |
| visible | boolean | default true | Admin can hide items |

**Indexes**:
- Unique on `originalUrl` (duplicate detection)
- Compound: `(visible, eventDate DESC)` (listing queries)
- On `eventDate` (upcoming vs past separation)

### ContentSource

MongoDB collection: `content_sources`

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | String | @Id, auto-generated | MongoDB document ID |
| name | String | required, unique | Display name (e.g., "AI Native Dev") |
| baseUrl | String | required | Base URL of the source |
| feedUrl | String | optional | RSS/Atom feed URL if available |
| sitemapUrl | String | optional | Sitemap URL for discovery |
| sourceType | String | required, enum: BLOG, NEWS, EVENTS | Type of content this source provides |
| scrapeStrategy | String | required, enum: RSS, SITEMAP_HTML, HTML | How to fetch content |
| active | boolean | default true | Whether to include in scheduled runs |
| lastFetchedAt | Instant | optional | When last successfully fetched |
| lastError | String | optional | Last error message if fetch failed |

**Indexes**:
- Unique on `name`
- On `active` (query active sources only)

### WeeklyDigest (virtual — stored as Blog entity)

Weekly digests are stored as regular `Blog` documents with a `"Weekly Digest"` tag. No separate collection needed. The digest agent creates a Blog with:
- `title`: "Week in Review: [date range]"
- `tags`: includes a `@DBRef` to a "Weekly Digest" Tag
- `published`: true (auto-publish)
- `content`: AI-generated markdown summary
- `createdAt`: generation timestamp

## Elasticsearch Integration

### site_search index additions

New `type` values in `SiteSearchDocument`:
- `"news"` — for AggregatedArticle items
- `"event"` — for AggregatedEvent items

Converter mappings:
- `articleToSiteDocument()`: maps title→name, summary→shortDescription, sourceName→company, originalUrl→url, publishedDate→sortDate
- `eventToSiteDocument()`: maps title→name, description→shortDescription, sourceName→company, originalUrl→url, eventDate→sortDate

### GroupedSearchResponse extension

Add two new groups to the search response:
- `news: SiteSearchDocument[]`
- `events: SiteSearchDocument[]`

## Vector Store Integration

### Metadata for aggregated content

Articles:
```
sourceId: <articleId>
sourceType: "aggregated_article"
title: <title>
url: <originalUrl>
sourceName: <sourceName>
```

Events:
```
sourceId: <eventId>
sourceType: "aggregated_event"
title: <title>
url: <originalUrl>
sourceName: <sourceName>
```

Embedding content: title + summary + fullContent (articles) or title + summary + description (events), chunked via TokenTextSplitter.

## Kafka Events

Add to `ContentType` enum:
- `AGGREGATED_ARTICLE`
- `AGGREGATED_EVENT`

Events published after each aggregation run completes for new/updated items. Consumed by:
- `ContentChangeConsumer` → Elasticsearch indexing
- `EmbeddingChangeConsumer` → vector embedding

## State Transitions

### AggregatedArticle / AggregatedEvent lifecycle

```
[Fetched] → visible=true (default)
         → visible=false (admin hides)
         → visible=true (admin restores)
```

No deletion lifecycle — content is retained indefinitely per clarification.

### ContentSource lifecycle

```
[Created] → active=true (default)
          → active=false (admin deactivates)
          → active=true (admin reactivates)
```

`lastFetchedAt` and `lastError` updated after each scheduled run.
