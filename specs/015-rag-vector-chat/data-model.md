# Data Model: RAG-Enhanced AI Chat with Vector Embeddings

**Feature**: 015-rag-vector-chat | **Date**: 2026-04-05

## New Entities

### CodeExample (MongoDB: `code_examples` collection)

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | String | @Id, auto-generated | MongoDB ObjectId |
| title | String | Required, max 200 chars, unique | Display title |
| description | String | Required, max 2000 chars | What the code demonstrates |
| language | String | Required, max 50 chars | Programming language (e.g., "java", "typescript") |
| code | String | Required | The actual code content |
| skills | List\<Skill\> | @DBRef, optional | Associated skills (many-to-many) |
| createdAt | Instant | Auto-set on creation | Audit timestamp |
| updatedAt | Instant | Auto-set on update | Audit timestamp |

**Indexes**:
- `title` — unique index
- `language` — standard index (for filtering)

**Admin DTO pattern**: Frontend sends/receives `skills` as `List<String>` (IDs). Controller resolves to `@DBRef` entities on save, converts back to IDs on read. Follows existing `AdminBlogController` pattern.

### Embedding Chunk (Elasticsearch: `content-embeddings` index)

Managed by Spring AI's `ElasticsearchVectorStore`. The store creates and manages this index automatically.

| Field | Type | Notes |
|-------|------|-------|
| id | String | Spring AI document ID (UUID) |
| content | String | The chunk text |
| embedding | dense_vector (1536 dims) | Vector from text-embedding-3-small |
| metadata.sourceId | String | ID of the source entity |
| metadata.sourceType | String | "blog", "job", "skill", "code_example" |
| metadata.title | String | Source entity title for display |
| metadata.tags | String | Comma-separated tags (blogs only) |
| metadata.skills | String | Comma-separated skill names |
| metadata.language | String | Programming language (code examples only) |
| metadata.url | String | Frontend URL for linking |

**Index settings**: cosine similarity, 1536 dimensions, kNN enabled.

## Modified Entities

### ContentChangeEvent (Kafka)

Add `CODE_EXAMPLE` to `ContentType` enum:
```
ContentType: BLOG, JOB, SKILL, CODE_EXAMPLE
```

### OperationType (DataOps)

Add `REEMBED_CONTENT` to enum:
```
OperationType: BACKUP, RESTORE, CLEAR, REBUILD_INDEX, REDEPLOY, REEMBED_CONTENT
```

### Docker Compose — Elasticsearch

Add to both dev and prod elasticsearch service:
```yaml
environment:
  path.repo: /usr/share/elasticsearch/backups
volumes:
  - elasticsearch-backups:/usr/share/elasticsearch/backups
```

New named volume: `elasticsearch-backups`

## Entity Relationships

```
CodeExample --@DBRef--> Skill (many-to-many)
Blog --@DBRef--> Skill (many-to-many, existing)
Blog --@DBRef--> Tag (many-to-many, existing)
Job --> Skill (ID list, existing)

All content entities --> Embedding Chunks (1:many, via metadata.sourceId)
```

## State Transitions

### Embedding Lifecycle

```
Content Created/Updated → Chunk & Embed → Store in ES Vector Index
Content Deleted/Unpublished → Remove chunks by sourceId from ES Vector Index
```

### Re-embedding Operation

```
IDLE → REEMBED_CONTENT (IN_PROGRESS) → iterate content types → batch embed → COMPLETED/FAILED
```

Uses existing `DataOperation` state machine (start → progress updates → complete/fail).
