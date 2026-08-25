# Phase 1 Data Model: On-demand article summaries with audio

**Feature**: `034-article-summary-audio`
**Date**: 2026-08-24

## New collection: `article_summaries`

`backend/src/main/java/com/simonrowe/summary/ArticleSummary.java`

A mutable `@Document` class (not a record) because the generation flow transitions it
in place — the same reason `Narration` is a class.

| Field | Type | Notes |
|---|---|---|
| `id` | `String` `@Id` | `sha256(SUMMARY_FORMAT_VERSION + articleId)`, hex. Deterministic — this is the dedup key. |
| `articleId` | `String` `@Indexed` | The `aggregated_articles` `_id`. Plain id, no `@DBRef`. |
| `status` | `SummaryStatus` | `GENERATING` \| `READY` \| `FAILED`. |
| `version` | `long` | Starts at 1, `++` on every mutation. Drives the long-poll `afterVersion`. |
| `body` | `String` | Markdown prose. Null until `READY`. |
| `model` | `String` | The model name used, for traceability. |
| `sourceCharacterCount` | `int` | Length of the resolved source text actually sent. |
| `requestedAt` | `Instant` | Set on insert. |
| `completedAt` | `Instant` | Set on `READY`. |
| `updatedAt` | `Instant` | Bumped with `version`. **Load-bearing**: the stale-reclaim guard reads it. |
| `failureCode` | `String` | See table below. Null unless `FAILED`. |
| `retryable` | `boolean` | Whether a repeat `POST` should try again. |

### `SummaryStatus` (persisted)

```
GENERATING → READY
GENERATING → FAILED
GENERATING → GENERATING   (stale reclaim: version++, updatedAt=now)
FAILED      → GENERATING   (retryable retry only)
READY       → (terminal; only a SUMMARY_FORMAT_VERSION bump produces a new document)
```

### `failureCode` values

| Code | `retryable` | Cause |
|---|---|---|
| `INSUFFICIENT_SOURCE_TEXT` | `false` | Best available source text under `HARD_MIN_SOURCE_CHARS` (200). No model call was made. |
| `MODEL_ERROR` | `true` | The model call threw, or returned null/blank. |
| `ARTICLE_NOT_FOUND` | `false` | Article missing, or `visible == false`. |

### Wire state (`ArticleSummaryResponse.PublicState`)

Persisted statuses plus one that is never stored, exactly as
`NarrationResponse.PublicState` adds states over `NarrationStatus`:

| State | When |
|---|---|
| `NOT_REQUESTED` | No document exists for this article. |
| `GENERATING` | Persisted `GENERATING`. |
| `READY` | Persisted `READY`; `body` populated. |
| `FAILED` | Persisted `FAILED`; carries `retryable` and a human message. |

There is deliberately **no** `UNAVAILABLE`: narration has one because the TTS provider can
be unconfigured, whereas the chat model is a hard dependency of the running application.

### Indexes

Created by Mongock change unit `V020CreateArticleSummaryIndexes` (order `020`).
`auto-index-creation` is off in this project, so `@Indexed` alone is decorative.

| Name | Definition | Why |
|---|---|---|
| `idx_article_summary_article` | `{articleId: 1}` | `GET /api/news/{id}/summary` and the reclaim lookup. |
| `idx_article_summary_status_article` | `{status: 1, articleId: 1}` | `GET /api/news/summaries/ids` — a covered scan of `READY` ids. |

The `_id` uniqueness that the insert-first dedup relies on is Mongo's own `_id` index and
needs no declaration.

## Changed collection: `narrations`

`backend/src/main/java/com/simonrowe/narration/Narration.java`

| Change | Before | After |
|---|---|---|
| Field | `String blogId` `@Indexed` | `NarrationContentType contentType` + `String contentId` |
| Constructor | `(id, blogId, scriptCharacterCount, …)` | `(id, contentType, contentId, scriptCharacterCount, …)` |
| Compound index | `idx_narration_blog_updated` on `{blogId: 1, updatedAt: -1}` | `idx_narration_content_updated` on `{contentType: 1, contentId: 1, updatedAt: -1}` |
| Repository | `findByBlogId(String)` | `findByContentTypeAndContentId(NarrationContentType, String)` |

Unchanged and deliberately so:

- `fingerprint` — still content-addressed, still `@Indexed(unique = true)`, still equal to
  `id`. This is what makes a regenerated summary yield a new narration id and mark the
  previous audio `STALE` for free.
- `NarrationScriptBuilder.FORMAT_VERSION` — stays the literal `blog-narration-v1` even
  after the class rename, because it feeds the fingerprint. Changing it would change every
  existing blog narration's `_id` and orphan the stored MP3s.
- `audioPath` — still `/uploads/narrations/{id}/narration.mp3`, derived from `id` alone in
  `NarrationStorage.store`.
- `idx_narration_status_lease`, and the whole lease/claim/budget/recovery state machine.

### `NarrationContentType`

```java
public enum NarrationContentType { BLOG, ARTICLE_SUMMARY }
```

Room is deliberately left for a future `ARTICLE_FULL` (see the design's deferred scope);
`NarrationSource` is the interface it would plug into.

### Migration: `V021GeneraliseNarrationContentType` (order `021`)

Raw-`Document`-level, idempotent, no external I/O — so the standard change-unit test
pattern applies rather than the isolated-boot pattern.

1. For every `narrations` document that has a `blogId`: `$set contentType: "BLOG"`,
   `$set contentId: <blogId>`, `$unset blogId`.
2. Drop index `idx_narration_blog_updated` if present; drop the single-field `blogId`
   index if present.
3. Create `idx_narration_content_updated` on `{contentType: 1, contentId: 1, updatedAt: -1}`.

Idempotent because step 1's filter (`blogId` exists) matches nothing on a re-run, and index
drop/create are both tolerant of the already-done state.

`NarrationRestoreValidator.ensureIndexes()` must be updated to the same index set — a
restore drops collections (and therefore indexes), and that method, not Mongock, is what
puts them back.

## Existing entities: unchanged

- **`AggregatedArticle`** — read-only here. Supplies `title`, `sourceName`,
  `publishedDate`, `originalUrl`, `fullContent`, `summary`, `visible`.
- **`Favourite`** — untouched; the drawer reuses the existing heart.
- **`Blog`** — untouched.

## Data-ops registration

Three enumerated lists in `com.simonrowe.dataops` name collections explicitly and must
gain `article_summaries`:

| Location | Change | Why |
|---|---|---|
| `BackupService.BACKUP_COLLECTIONS` | add `"article_summaries"` | A paid-for artefact that no backup captures is lost on any restore. |
| `RestoreService.IMPORT_ORDER_INDEPENDENT` | add `"article_summaries"` | Holds no `@DBRef`; points at `aggregated_articles` by plain id, exactly like `favourites`. |
| `RestoreService` post-restore index recreation | recreate `idx_article_summary_article` and `idx_article_summary_status_article` | Restore drops the collection, taking its indexes with it — the same reason `ensureFavouriteIndexes()` exists. |

`ClearService.COLLECTIONS` is deliberately **not** changed: `aggregated_articles`, the
parent content, is not cleared either, so clearing derived summaries would strand the
feature against content that is still present.
