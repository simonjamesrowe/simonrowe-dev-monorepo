# Data Model: Site-Wide Search

**Feature**: 005-site-search | **Date**: 2026-02-21

## Elasticsearch Indices

This feature introduces two Elasticsearch indices. No MongoDB schema changes are required -- MongoDB remains the source of truth, and Elasticsearch indices are derived projections.

---

### Index: `site_search`

**Purpose**: Powers the global homepage typeahead search across all content types (blogs, jobs, skills).

**Mapping**:

```json
{
  "mappings": {
    "properties": {
      "name": {
        "type": "text",
        "analyzer": "standard",
        "fields": {
          "keyword": {
            "type": "keyword"
          }
        }
      },
      "type": {
        "type": "keyword"
      },
      "shortDescription": {
        "type": "text",
        "analyzer": "standard"
      },
      "longDescription": {
        "type": "text",
        "analyzer": "standard"
      },
      "image": {
        "type": "keyword",
        "index": false
      },
      "url": {
        "type": "keyword",
        "index": false
      }
    }
  },
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0
  }
}
```

**Field descriptions**:

| Field | Type | Searchable | Description |
|-------|------|-----------|-------------|
| `name` | text + keyword | Yes | Content name/title. Text for full-text search, keyword sub-field for exact match boosting. |
| `type` | keyword | Yes (filter) | Content type discriminator: `"blog"`, `"job"`, or `"skill"`. Used for grouping results by category. |
| `shortDescription` | text | Yes | Brief summary of the content. Displayed in some contexts but primarily used for search matching. |
| `longDescription` | text | Yes | Extended description. Provides additional search surface. Nullable (not all content types have this). |
| `image` | keyword (not indexed) | No | URL to the thumbnail/featured image. Returned in results for display only. |
| `url` | keyword (not indexed) | No | Relative URL path to the content detail page (e.g., `/blogs/my-post`, `/skills`). Returned in results for navigation. |

**Document ID strategy**: Use the MongoDB `_id` value as the Elasticsearch document `_id`. This enables direct upserts and deletes by ID without maintaining a separate mapping.

**Content type mapping**:

| Source Entity | `name` | `type` | `shortDescription` | `longDescription` | `image` | `url` |
|--------------|--------|--------|--------------------|--------------------|---------|-------|
| Blog Post | title | `"blog"` | shortDescription | null | featured image URL | `/blogs/{slug}` |
| Job | job title | `"job"` | short description | long description (markdown) | company image URL | `/employment` |
| Skill | skill name | `"skill"` | description | null | skill image URL | `/skills` |

---

### Index: `blog_search`

**Purpose**: Powers the dedicated blog search on the blog listing page with rich field matching.

**Mapping**:

```json
{
  "mappings": {
    "properties": {
      "title": {
        "type": "text",
        "analyzer": "standard",
        "fields": {
          "keyword": {
            "type": "keyword"
          }
        }
      },
      "shortDescription": {
        "type": "text",
        "analyzer": "standard"
      },
      "content": {
        "type": "text",
        "analyzer": "standard"
      },
      "tags": {
        "type": "keyword"
      },
      "skills": {
        "type": "keyword"
      },
      "image": {
        "type": "keyword",
        "index": false
      },
      "publishedDate": {
        "type": "date",
        "format": "strict_date_optional_time"
      },
      "url": {
        "type": "keyword",
        "index": false
      }
    }
  },
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0
  }
}
```

**Field descriptions**:

| Field | Type | Searchable | Description |
|-------|------|-----------|-------------|
| `title` | text + keyword | Yes | Blog post title. Boosted 3x in search queries. Keyword sub-field for exact match boosting. |
| `shortDescription` | text | Yes | Brief summary shown in search results. Boosted 2x in search queries. |
| `content` | text | Yes | Full blog content in Markdown format. Primary body text for deep content matching. Standard boost (1x). |
| `tags` | keyword (array) | Yes | Array of tag names (e.g., `["microservices", "docker"]`). Boosted 2x. Exact match on individual tags. |
| `skills` | keyword (array) | Yes | Array of skill names (e.g., `["Java", "Spring Boot"]`). Enables skill-based blog discovery. |
| `image` | keyword (not indexed) | No | URL to the blog featured image. Returned for display only. |
| `publishedDate` | date | No (display only) | Publication date in ISO-8601 format. Returned in results for display. Could be used for sort/filter in future. |
| `url` | keyword (not indexed) | No | Relative URL path to the blog detail page (e.g., `/blogs/my-post`). Returned for navigation. |

**Document ID strategy**: Same as `site_search` -- uses MongoDB `_id` as Elasticsearch `_id`.

---

## Kafka Event Schema

### Topic: `content-changes`

Events published when content is created, updated, or deleted in the content management system.

```json
{
  "eventType": "CREATED | UPDATED | DELETED",
  "contentType": "BLOG | JOB | SKILL",
  "contentId": "string",
  "timestamp": "2026-02-21T14:30:00Z"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `eventType` | enum string | The operation that occurred. One of: `CREATED`, `UPDATED`, `DELETED`. |
| `contentType` | enum string | The type of content affected. One of: `BLOG`, `JOB`, `SKILL`. |
| `contentId` | string | The MongoDB document ID of the affected content. |
| `timestamp` | ISO-8601 string | When the event occurred. Used for logging and debugging, not for processing logic. |

**Key**: `contentId` (ensures per-document ordering within a partition).

**Consumer group**: `search-indexer`

---

## API Response Models

### GroupedSearchResponse (site-wide search)

```json
{
  "blogs": [
    {
      "name": "Building Microservices with Spring Boot",
      "image": "/images/blogs/microservices.jpg",
      "url": "/blogs/building-microservices"
    }
  ],
  "jobs": [
    {
      "name": "Senior Software Engineer at Acme Corp",
      "image": "/images/jobs/acme.png",
      "url": "/employment"
    }
  ],
  "skills": [
    {
      "name": "Java",
      "image": "/images/skills/java.png",
      "url": "/skills"
    }
  ]
}
```

### BlogSearchResult (blog search)

```json
[
  {
    "title": "Building Microservices with Spring Boot",
    "shortDescription": "A guide to building production-ready microservices...",
    "image": "/images/blogs/microservices.jpg",
    "publishedDate": "2025-11-15T00:00:00Z",
    "url": "/blogs/building-microservices"
  }
]
```

---

## Relationship to Existing Data Models

This feature does not modify existing MongoDB collections. It creates derived Elasticsearch indices that are populated from existing entities:

- **Blog Post** (from 003-blog-system): title, shortDescription, content (Markdown), tags, skills, featured image, publishedDate
- **Job** (from 004-skills-employment): job title, short description, long description (Markdown), company image
- **Skill** (from 004-skills-employment): skill name, description, skill image

The `IndexService` reads from MongoDB repositories for these entities and transforms them into Elasticsearch documents. The transformation is one-directional: MongoDB -> Elasticsearch. Search indices are disposable and can be fully rebuilt from MongoDB at any time via the sync scheduler.
