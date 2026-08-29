# Phase 1 Data Model: Share links for blogs and news/events

**Feature**: 041-share-short-links | **Date**: 2026-08-28

One new collection. No change to `blogs`, `aggregated_articles` or `aggregated_events`.

## Collection `short_links`

`com.simonrowe.shortlink.ShortLink` — a Java record, `@Document(collection = "short_links")`.

| Field | Type | Nullable | Notes |
|---|---|---|---|
| `_id` | `String` | no | **The slug itself.** `[a-z0-9-]`, 1–20 characters. |
| `contentType` | `ShortLinkContentType` | no | `BLOG` \| `ARTICLE` \| `EVENT` |
| `contentId` | `String` | no | Mongo id in `blogs` / `aggregated_articles` / `aggregated_events` |
| `clickCount` | `long` | no | Human opens only; unfurler fetches excluded |
| `lastClickedAt` | `Instant` | yes | `null` until first human open |
| `createdAt` | `Instant` | no | |

### Why the slug is the `_id`

Two consequences, both load-bearing:

1. The redirect is a primary-key lookup — the hottest read on the endpoint costs nothing.
2. Slug uniqueness is enforced by Mongo, not by application code that hopes. `ensureFor`
   inserts and catches `DuplicateKeyException`; there is no read-then-write race to lose.

### Indexes

Created by `V029CreateShortLinksAndBackfill`, **not** by annotations —
`auto-index-creation` is off in this repo, so `@CompoundIndex` alone is decorative.

| Name | Definition | Unique | Serves |
|---|---|---|---|
| (implicit `_id`) | `{_id: 1}` | yes | the redirect lookup; slug-collision detection |
| `idx_short_link_content` | `{contentType: 1, contentId: 1}` | **yes** | `ensureFor` idempotency; the batched listing lookup; guarantees exactly one link per item |

The unique compound index is what makes FR-006 structural rather than aspirational: a
re-save cannot mint a second slug for content that already has one, even under a race.

### No index on `clickCount`

The admin table is a few hundred rows sorted in the browser. An index for a sort that never
runs in Mongo is dead weight.

## Enum `ShortLinkContentType`

```
BLOG    -> destination /blogs/{contentId}
ARTICLE -> destination /news-events?article={contentId}
EVENT   -> destination /news-events?event={contentId}
```

Deliberately a **new** enum, not a reuse of
`com.simonrowe.narration.NarrationContentType` (`BLOG` | `ARTICLE_SUMMARY`) or
`com.simonrowe.events.ContentChangeEvent.ContentType`
(`BLOG` | `AGGREGATED_ARTICLE` | `AGGREGATED_EVENT` | …). Neither has the right member set —
narration has no events and points at summaries rather than articles; the change-event enum
carries members with no shareable page. Sharing a third meaning through either would couple
this collection's stored values to an unrelated concern's evolution.

Note the stored value is `ARTICLE`, distinct from narration's `ARTICLE_SUMMARY`, even
though the destination opens the summary panel: the link points at the *article*, and
`contentId` is an `aggregated_articles` id.

## State

There is no state machine. A link is created once and only ever accumulates clicks. There
is no delete path in this feature — deleting a blog post leaves an orphaned link, which
resolves to `/blogs/{deletedId}` and shows the SPA's not-found. That is deliberate and
recorded as accepted: a slug is a public identifier already pasted elsewhere, and reclaiming
it for different content would be worse than a dead link.

## Validation

| Rule | Enforced by |
|---|---|
| Slug matches `^[a-z0-9][a-z0-9-]{0,19}$` | `ShortLinkSlugger` construction + a unit test |
| Slug ≤ 20 characters, always | `ShortLinkSlugger`, including through `-2`…`-99` suffixes |
| Slug unique | Mongo `_id` |
| Exactly one link per `(contentType, contentId)` | unique compound index + `ensureFor` |
| `clickCount` never decreases | only ever written by `$inc` |

## Durability

`short_links` is added to:

- `BackupService.BACKUP_COLLECTIONS`
- `RestoreService.IMPORT_ORDER_INDEPENDENT` — it holds no `@DBRef` and points at other
  collections only by plain id, so import order is free (same slot as `favourites` and
  `article_summaries`).

`RestoreService` must also call
`V029CreateShortLinksAndBackfill.createIndexes(mongoTemplate)` after import: a restore
drops collections and their indexes with them, and Mongock will not re-run a recorded
change unit. This is the pattern `NarrationRestoreValidator.ensureIndexes()` and
`V020CreateArticleSummaryIndexes.createIndexes` already establish.

This is not optional housekeeping. These slugs are in links already pasted into other
people's Slack channels; dropping them on a restore breaks URLs that exist in the wild.

## Derived read field

`shortUrl` on the four public response DTOs is **not stored**. It is
`${site.base-url}/s/{slug}`, assembled at read time from the batched lookup, so the
frontend never concatenates a base and the base can change without a data migration. It is
nullable: an item minted-but-not-yet-visible, or one created in the window before minting
ran, has no link and renders with no Share control rather than a broken URL.
