# Admin News & Events: paging and clearer header actions

Date: 2026-08-09

## Problem

The admin News & Events screen (`/admin/aggregated-content`) has two usability
problems.

1. **No paging.** `GET /api/admin/news` and `GET /api/admin/events` return the
   entire collection as a bare JSON array, and `AggregatedContentAdmin` renders
   every row. The lists grow without bound as aggregation runs nightly.
2. **Opaque header actions.** Four buttons — "Sync Search", "Sync Embeddings",
   "Trigger Aggregation", "Trigger Digest" — give no clue which one scrapes the
   content sources and which one writes the automated blog post. Two of them are
   not news-specific at all.

What the buttons actually do:

| Button | Backend | Effect |
|---|---|---|
| Trigger Aggregation | `ContentAggregationAgent.runAggregation()` | Visits every active content source and scrapes new articles/events. Also on a nightly cron. |
| Trigger Digest | `WeeklyDigestAgent.generateDigest()` | AI-writes a blog post summarising recent blogs and articles, generates a featured image, and publishes it **live** (`published=true`). Also on a 3-day cron. |
| Sync Search | `IndexService.fullSyncSiteIndex()` | Full Elasticsearch reindex of the whole site. |
| Sync Embeddings | `EmbeddingService.fullVectorSync()` | Full vector re-embed of the whole site. |

## Design

### 1. Backend — paged admin endpoints

`AdminAggregationController.listAllArticles()` and `listAllEvents()` return
Spring `Page` instead of `List`, matching the existing `AdminBlogController`
pattern:

```java
@GetMapping("/news")
public Page<ArticleResponse> listAllArticles(
    @RequestParam(defaultValue = "0") final int page,
    @RequestParam(defaultValue = "20") final int size) {
  return articleRepository
      .findAll(PageRequest.of(page, size,
          Sort.by(Sort.Direction.DESC, "publishedDate")))
      .map(ArticleResponse::from);
}
```

`/events` is the same, sorted `eventDate DESC`.

- No new repository methods: `MongoRepository.findAll(Pageable)` is inherited.
- The lists were previously unsorted (`findAll()`), which paging would have made
  visibly wrong — rows shuffling between pages. Sorting is part of this change,
  not an extra.
- The response shape changes from `[...]` to
  `{content, totalElements, totalPages, size, number}`. The only consumers are
  the admin UI and `AdminAggregationControllerTest`, both updated here. Public
  `/api/news` and `/api/events` are untouched.

### 2. Frontend — paging

- `fetchAdminNews(token, page, size)` and `fetchAdminEvents(token, page, size)`
  return the existing generic `PageResponse<T>`.
- `AggregatedContentAdmin` holds separate `newsPage` / `eventsPage` state, so
  switching tabs preserves each list's position.
- A `.pagination` block under each table, reusing the exact markup from
  `BlogsAdmin` (Previous / "Page X of Y" / Next). The CSS already exists.
- Tab counts read `totalElements`, not `content.length`.
- Visibility toggle patches the row in place. Delete reloads the current page so
  it refills, clamping back one page if the last page was emptied.

### 3. Header actions

| Before | After | Tooltip |
|---|---|---|
| Trigger Aggregation | **Fetch New Articles** | "Visit every active content source and scrape new articles and events. Also runs automatically each night." |
| Trigger Digest | **Generate Digest Blog Post** | "Use AI to write and publish a blog post summarising recent activity. Also runs automatically every 3 days." |
| Sync Search | Maintenance ▾ → **Rebuild Search Index** | "Rebuild the site-wide Elasticsearch index." |
| Sync Embeddings | Maintenance ▾ → **Rebuild Embeddings** | "Rebuild the site-wide vector embeddings." |

The two rebuild actions are site-wide maintenance, not news operations, so they
move behind a `Maintenance` dropdown: a small new `AdminMenu` component (trigger
button + popover, closing on outside click and Escape) with new BEM CSS. The
Import URL field is unchanged.

### 4. Digest confirmation

"Generate Digest Blog Post" publishes a blog live immediately, so it is gated by
a second `ConfirmDialog` instance:

> This will use AI to write a new blog post summarising recent blogs and
> articles, and publish it live on the site immediately. Continue?

Confirm label "Generate". "Fetch New Articles" and the maintenance actions fire
directly, keeping today's inline "triggered" message.

## Out of scope

Progress reporting for the fire-and-forget triggers (the backend returns `202`
and never reports completion) and list filtering by source/visibility. Both were
considered and deliberately left out.

## Testing

- `AdminAggregationControllerTest`: existing list assertions move to the paged
  shape; new cases for `page`/`size` honoured, sort order, and an out-of-range
  page returning empty content.
- New `frontend/tests/admin/AggregatedContentAdmin.test.tsx`: pagination renders
  and Next/Previous refetch; tab counts come from `totalElements`; the digest
  action is gated behind the confirm dialog and cancelling fires no request.
