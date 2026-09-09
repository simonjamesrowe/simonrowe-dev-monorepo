# Data Model: Term Time

All three collections are added to `BackupService.BACKUP_COLLECTIONS` and
`RestoreService.IMPORT_ORDER_INDEPENDENT`. Indexes are created by Mongock change unit
`V030CreateSchoolCollections` — `auto-index-creation` is off, so annotations alone create nothing.
`RestoreService` must call `V030.createIndexes()` directly, because a restore drops the collection
with its indexes and Mongock will not re-run a recorded change unit.

## `school_documents`

One ingested item from any source.

| Field | Type | Notes |
|---|---|---|
| `_id` | String | `sha256(sourceType + ':' + sourceRef)` — stable, so re-ingesting updates rather than duplicates |
| `sourceType` | enum | `CALENDAR_FEED`, `WEBSITE_PAGE`, `PDF`, `EMAIL` — also drives `SourcePrecedence` |
| `sourceRef` | String | URL, or the Gmail message id |
| `title` | String | |
| `body` | String | Extracted text. **Retained for restricted items** so reclassification needs no resync |
| `publishedAt` | Instant | Real publication date, not ingest time — feeds recency and citation |
| `ingestedAt` | Instant | |
| `visibility` | enum | `PUBLIC` \| `RESTRICTED`. **Defaults to `RESTRICTED` at construction** |
| `proposedVisibility` | enum, nullable | What the classifier suggested. Never read by access control |
| `proposalReason` | String, nullable | Shown in the approval queue |
| `approvedBy` / `approvedAt` | String / Instant, nullable | Set only by explicit human approval |
| `nameGateBlocked` | boolean | True when `StaffNameGate` forced restricted, overriding a proposal |
| `yearGroups` | List\<String\> | Inferred; a soft retrieval hint only |
| `contentHash` | String | Skips re-embedding unchanged content |

Indexes: `{visibility: 1, publishedAt: -1}`, `{sourceType: 1, sourceRef: 1}` unique,
`{proposedVisibility: 1, approvedAt: 1}` (the approval queue read).

## `school_events`

A dated fact. The unit that answers "when is…" and "what's on…".

| Field | Type | Notes |
|---|---|---|
| `_id` | String | `sha256(academicYear + startDate + normalisedTitle)` — dedupes the same event arriving from two sources |
| `title` | String | |
| `startDate` / `endDate` | LocalDate | `endDate` equals `startDate` for single-day events |
| `allDay` | boolean | |
| `eventType` | enum | `TERM_BOUNDARY`, `HALF_TERM`, `INSET`, `CLUB`, `TRIP`, `OTHER` |
| `yearGroups` | List\<String\> | Empty means whole-school. **Hard filter** for this collection |
| `academicYear` | String | e.g. `2026/27`. Mandatory — without it a prior year's dates read as current |
| `sourceType` | enum | Drives precedence when two sources disagree |
| `sourceDocumentId` | String | For citation |
| `visibility` | enum | Inherited from the source document |

Indexes: `{startDate: 1, visibility: 1}`, `{academicYear: 1, eventType: 1}`.

**Precedence**: `CALENDAR_FEED` > `EMAIL` > `WEBSITE_PAGE` > `PDF`. Applied when two events share an
`_id` prefix but disagree on dates. The website's term-dates page is a year stale while the calendar
feed is current, so this ordering is load-bearing rather than theoretical.

## `school_sync_state`

Per-source ingestion bookkeeping. One document per source.

| Field | Type | Notes |
|---|---|---|
| `_id` | String | The source key |
| `cursor` | String, nullable | Gmail `historyId`; null forces a full sync, which is a **normal state** |
| `lastSuccessAt` | Instant | |
| `lastFailureAt` / `lastFailureReason` | Instant / String, nullable | Credential revocation surfaces here |
| `pageEtags` | Map\<String,String\> | Website `pubDate`/etag per URL, so unchanged pages are not re-fetched against a 10s crawl delay |

## Elasticsearch: `school-embeddings`

Separate index, not a filter on `content-embeddings`. Same 1536-dim `text-embedding-3-small`
configuration. Chunk metadata: `documentId`, `visibility`, `sourceType`, `publishedAt`, `yearGroups`.
`visibility` is the filter clause; `yearGroups` is a soft boost only.
