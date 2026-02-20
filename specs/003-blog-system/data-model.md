# Data Model: Blog System

**Feature**: 003-blog-system | **Date**: 2026-02-21

## MongoDB Collections

### Blog Collection (`blogs`)

Stores all blog post content and metadata. This is the primary persistence store for the blog system.

**Document schema**:

```json
{
  "_id": "ObjectId",
  "title": "string (required)",
  "shortDescription": "string (required)",
  "content": "string (required, Markdown format)",
  "published": "boolean (required, default: false)",
  "featuredImageUrl": "string (nullable, URL to featured image)",
  "createdDate": "ISODate (required)",
  "updatedDate": "ISODate (required)",
  "tags": [
    { "$ref": "tags", "$id": "ObjectId" }
  ],
  "skills": [
    { "$ref": "skills", "$id": "ObjectId" }
  ]
}
```

**Field descriptions**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `_id` | ObjectId | Yes (auto) | MongoDB-generated unique identifier |
| `title` | String | Yes | Blog post title, displayed in listing cards and detail page header |
| `shortDescription` | String | Yes | Brief summary displayed in blog listing cards, search results, and homepage preview |
| `content` | String | Yes | Full article content in Markdown format. Supports headings, paragraphs, lists, blockquotes, inline code, fenced code blocks with language hints, images, and hyperlinks. Rendered to HTML on the frontend. |
| `published` | Boolean | Yes | Controls visibility. Only `true` posts appear in listing, search, and homepage preview (FR-003). Defaults to `false`. |
| `featuredImageUrl` | String | No | URL to the blog post's featured/hero image. Displayed in listing cards and detail page header. Posts without this field display a placeholder (FR-019). |
| `createdDate` | ISODate | Yes | Publication timestamp. Used for sorting (newest first) and display (FR-017). |
| `updatedDate` | ISODate | Yes | Last modification timestamp. Used for cache invalidation and audit purposes. |
| `tags` | Array of DBRef | No | References to Tag documents for categorization. A post may have zero or more tags (FR-018). Displayed in listing cards and detail pages. |
| `skills` | Array of DBRef | No | References to Skill documents (defined in Spec 004). Enables cross-referencing between blog posts and technical skills (FR-014). |

**Indexes**:

| Index | Fields | Type | Purpose |
|-------|--------|------|---------|
| `idx_published_created` | `{ published: 1, createdDate: -1 }` | Compound | Optimizes the primary query: all published posts sorted by newest first. Used by listing page and homepage preview. |
| `idx_published` | `{ published: 1 }` | Single | Supports filtered queries on publish status. |

**Example document** (migrated from backup):

```json
{
  "_id": ObjectId("5f0215c69d8081001fd38fa1"),
  "title": "Creating a rich web app that can be hosted from home",
  "shortDescription": "A quick introduction into the various components of my personal website including jenkinsx, kubernetes, letsencrypt and more.",
  "content": "I'm the kind of person that learns by doing...",
  "published": true,
  "featuredImageUrl": "/images/blogs/web-app-from-home.jpg",
  "createdDate": ISODate("2020-07-05T17:29:26.731Z"),
  "updatedDate": ISODate("2021-03-01T12:29:14.969Z"),
  "tags": [
    DBRef("tags", ObjectId("5e495da7bc8d7d001ddbd7c5")),
    DBRef("tags", ObjectId("5f02da709d8081001fd38fa4")),
    DBRef("tags", ObjectId("5f03901b9d8081001fd38fa7")),
    DBRef("tags", ObjectId("5f0390249d8081001fd38fa8")),
    DBRef("tags", ObjectId("5f0390329d8081001fd38fa9")),
    DBRef("tags", ObjectId("5f0390499d8081001fd38faa"))
  ],
  "skills": [
    DBRef("skills", ObjectId("5f635c2e5ee4c9001d2b963c")),
    DBRef("skills", ObjectId("5f635f8f5ee4c9001d2b966c"))
  ]
}
```

---

### Tag Collection (`tags`)

Stores topic tags used to categorize blog posts.

**Document schema**:

```json
{
  "_id": "ObjectId",
  "name": "string (required, unique)"
}
```

**Field descriptions**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `_id` | ObjectId | Yes (auto) | MongoDB-generated unique identifier |
| `name` | String | Yes | Unique tag name (e.g., "Kubernetes", "Spring", "Docker"). Displayed as labels on blog listing cards and detail pages. |

**Indexes**:

| Index | Fields | Type | Purpose |
|-------|--------|------|---------|
| `idx_name_unique` | `{ name: 1 }` | Unique | Enforces tag name uniqueness and supports lookup by name. |

**Example documents** (from backup, 26 total):

```json
{ "_id": ObjectId("5e495da7bc8d7d001ddbd7c5"), "name": "Kubernetes" }
{ "_id": ObjectId("5f02da709d8081001fd38fa4"), "name": "Jenkins" }
{ "_id": ObjectId("5f03901b9d8081001fd38fa7"), "name": "Strapi" }
{ "_id": ObjectId("5f0390249d8081001fd38fa8"), "name": "TLS" }
{ "_id": ObjectId("5f0390329d8081001fd38fa9"), "name": "MongoDB" }
{ "_id": ObjectId("5f0390499d8081001fd38faa"), "name": "React" }
{ "_id": ObjectId("5f0407a99d8081001fd38fae"), "name": "AWS" }
{ "_id": ObjectId("5f04154f9d8081001fd38fbb"), "name": "Docker" }
{ "_id": ObjectId("5feed20367bdd6001e9c12cc"), "name": "Spring" }
{ "_id": ObjectId("5feed20e67bdd6001e9c12cd"), "name": "Testing" }
{ "_id": ObjectId("603d2a51e66d1b001e41b3e5"), "name": "Logging" }
{ "_id": ObjectId("603d2a59e66d1b001e41b3e6"), "name": "ElasticSearch" }
{ "_id": ObjectId("603d2a60e66d1b001e41b3e7"), "name": "Security" }
{ "_id": ObjectId("603d2a68e66d1b001e41b3e8"), "name": "OIDC" }
{ "_id": ObjectId("603d4931e66d1b001e41b3ec"), "name": "Kafka" }
{ "_id": ObjectId("60454682bbc16c001eeb604d"), "name": "APM" }
{ "_id": ObjectId("60454694bbc16c001eeb604e"), "name": "Kibana" }
{ "_id": ObjectId("6056359c7ca283001e0478e5"), "name": "Sonarqube" }
{ "_id": ObjectId("605635a17ca283001e0478e6"), "name": "Gradle" }
{ "_id": ObjectId("605635a77ca283001e0478e7"), "name": "Maven" }
{ "_id": ObjectId("606bff048aa315001e071317"), "name": "GraalVM" }
```

---

## Elasticsearch Index

### Blog Search Index (`blogs`)

Denormalized search index for typeahead blog search. Populated from MongoDB via Kafka events and periodic synchronization.

**Index mapping**:

```json
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0,
    "analysis": {
      "analyzer": {
        "blog_analyzer": {
          "type": "custom",
          "tokenizer": "standard",
          "filter": ["lowercase", "asciifolding"]
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "id": { "type": "keyword" },
      "title": {
        "type": "text",
        "analyzer": "blog_analyzer",
        "boost": 3.0
      },
      "shortDescription": {
        "type": "text",
        "analyzer": "blog_analyzer",
        "boost": 2.0
      },
      "content": {
        "type": "text",
        "analyzer": "blog_analyzer"
      },
      "tags": { "type": "keyword" },
      "skills": { "type": "keyword" },
      "thumbnailImage": {
        "type": "keyword",
        "index": false
      },
      "createdDate": { "type": "date" }
    }
  }
}
```

**Field descriptions**:

| Field | Type | Indexed | Description |
|-------|------|---------|-------------|
| `id` | keyword | Yes | MongoDB document ObjectId as string. Used for linking search results to blog detail pages. |
| `title` | text | Yes (boosted 3x) | Blog post title. Highest search relevance. |
| `shortDescription` | text | Yes (boosted 2x) | Brief summary. Second-highest search relevance. |
| `content` | text | Yes | Full content with Markdown syntax stripped. Lowest search relevance but enables full-text content search (FR-009). |
| `tags` | keyword | Yes | Tag names (denormalized from Tag collection). Supports filtering and exact-match search. |
| `skills` | keyword | Yes | Skill names (denormalized from Skill documents). Supports cross-reference search. |
| `thumbnailImage` | keyword | No | Image URL for search result display (FR-011). Not searchable. |
| `createdDate` | date | Yes | Publication date for search result display and sort ordering. |

---

## Data Migration Notes

### From Strapi/MongoDB Backup

The existing backup at `/Users/simonrowe/backups` contains 18 blog documents and 26 tag documents in Strapi's schema format. Key migration transformations:

| Backup Field | New Field | Transformation |
|-------------|-----------|----------------|
| `image` (ObjectId ref to `upload_file`) | `featuredImageUrl` (String) | Resolve ObjectId to `upload_file` document, extract URL path. Image files from `strapi-uploads/` directory should be migrated to the new static file serving location. |
| `createdAt` | `createdDate` | Rename only. ISODate format preserved. |
| `updatedAt` | `updatedDate` | Rename only. ISODate format preserved. |
| `tags` (Array of ObjectId) | `tags` (Array of DBRef) | Convert raw ObjectId references to DBRef format, or use embedded tag names depending on final Spring Data mapping strategy. |
| `skills` (Array of ObjectId) | `skills` (Array of DBRef) | Same as tags. Skills are defined in Spec 004 and referenced by ObjectId. |
| `__v` | (removed) | Strapi version field, not needed in new schema. |
| `created_by`, `updated_by` | (removed) | Strapi admin user references, not needed. Single-author blog. |

### Spring Data MongoDB Mapping

The Java entity classes will use Spring Data MongoDB annotations:

```java
@Document(collection = "blogs")
public record Blog(
    @Id String id,
    String title,
    String shortDescription,
    String content,
    boolean published,
    String featuredImageUrl,
    @Field("createdDate") Instant createdDate,
    @Field("updatedDate") Instant updatedDate,
    @DBRef List<Tag> tags,
    @DBRef List<Skill> skills
) {}

@Document(collection = "tags")
public record Tag(
    @Id String id,
    @Indexed(unique = true) String name
) {}
```

**Note on `@DBRef`**: Spring Data MongoDB will resolve `@DBRef` references automatically when loading blog documents. For the listing page where tags must be displayed, the tags will be eagerly loaded. For the search index, tag and skill names are denormalized to avoid cross-collection lookups.

---

## Relationship Diagram

```
┌─────────────┐       ┌─────────────┐       ┌─────────────┐
│   Blog      │       │   Tag       │       │   Skill     │
│             │  N:M  │             │       │ (Spec 004)  │
│ title       ├──────►│ name        │       │             │
│ content     │       │             │       │ name        │
│ tags[]      │       └─────────────┘       │ rating      │
│ skills[]    ├────────────────────────────►│             │
│ published   │  N:M                        └─────────────┘
│ createdDate │
└──────┬──────┘
       │ indexed to
       ▼
┌─────────────────┐
│ BlogSearch (ES) │
│                 │
│ title (boost 3) │
│ shortDesc (2)   │
│ content         │
│ tags[]          │
│ skills[]        │
│ thumbnailImage  │
│ createdDate     │
└─────────────────┘
```
