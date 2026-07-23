# Design: Add Dan Vega blog as a content source

**Date:** 2026-07-23
**Status:** Approved (pending spec review)
**Author:** simonrowe

## Summary

Add [Dan Vega's blog](https://www.danvega.dev/blog/) as a new content source in the
news/events aggregation system, treated identically to the existing HTML-scraped sources
(Claude Blog, Rundown AI). Ingestion uses the existing `HTML_LISTING` scrape strategy — no
new strategy, scraper, enum, controller, or frontend change. A new Mongock change unit seeds
the source and pre-populates roughly the last month of posts at deploy time so the news feed
isn't empty until the next scheduled run.

## Background: how the aggregation system works

- A content source is a MongoDB document in `content_sources` (`aggregation/ContentSource.java`),
  with a `scrapeStrategy` enum: `RSS`, `SITEMAP_HTML`, `HTML`, `HTML_LISTING`, `LUMA`.
- `agents/scrapers/ScraperFactory.java` dispatches on strategy. `HTML_LISTING` →
  `SitemapHtmlScraper.scrapeListingPage(baseUrl)`.
- `agents/ContentAggregationAgent.java` orchestrates: scrape → LLM classify/summarize
  (`gpt-4o-mini`) → image download/generate → save `AggregatedArticle` → publish to Kafka
  topic `content-changes`.
- Downstream is automatic: `events/ContentChangeConsumer` indexes into Elasticsearch
  (`type: "news"`); articles surface at `GET /api/news`, in site search, and in the chat MCP
  `searchNews` tool.
- A `@Scheduled` job (`aggregation/AggregationScheduler`, cron `0 0 */6 * * *`) runs
  `runAggregation()` over all active sources every 6 hours.
- New sources are **data-driven**: seeded via a Mongock change unit
  (e.g. `V005SeedClaudeBlogSource`) and/or `scripts/seed-content-sources.js`. No public
  create-source API.

## Site interrogation findings (danvega.dev)

Interrogated with Playwright. The site is a Nuxt (Vue) app, but pages are **prerendered**, so
Jsoup (which does not run JS) sees real content:

- **Listing page** `https://www.danvega.dev/blog/` — raw HTML contains article anchors
  (`/blog/embabel-1-0-ga`, `/blog/content-creation-workflow`, …). Page 1 exposes ~10 newest
  posts, plus a `/blog/tags` link and a `/blog/2` pagination link.
- **Post page** — has a real `<article>` (~12k chars of body text) and JSON-LD
  `{"@type":"BlogPosting", "datePublished":"2026-07-20T09:00:00.000Z", author, image, …}`.
- A clean RSS feed also exists (`/rss.xml`), but it carries only summaries, and we explicitly
  want the full-body HTML-scrape treatment used by Claude/Rundown, so RSS is not used.
- The sitemap lists all 264 posts in roughly oldest-first order; `SITEMAP_HTML` caps at the
  first 20 and would ingest ancient posts — the same reason Claude Blog was moved off the
  sitemap (`V009RepointClaudeBlogToListing`). Hence `HTML_LISTING`, not `SITEMAP_HTML`.

### Verification against existing code

Traced Dan's content through the real scraper functions in `SitemapHtmlScraper.java`:

- `isArticleLink()` **accepts** `/blog/<slug>` (host matches, under the `/blog/` section,
  ≥2 path segments, not localized/utility).
- `scrapeArticlePage()` finds his `<article>` body, `og:title`, `meta[name=author]`,
  `og:image`.
- `extractPublishedDate()` → `extractDateFromJsonLd()` reads **top-level** `datePublished`
  (line 564) *before* the `@graph` branch. Dan's JSON-LD is a flat `BlogPosting`, so the date
  is picked up directly — **no code change needed for dates.**
- Posts carry `og:image`, so `ExternalImageDownloader.downloadAndStore()` gets a real image
  and DALL·E generation (`BlogImageGenerationService`) is skipped.

**Known gap:** `isArticleLink()` filters `tag` (singular) and `?page=`, but not `tags` or the
path-style pagination `/blog/2`. Left unaddressed, `/blog/tags` and `/blog/2` would be scraped
as two junk "articles."

## Design

### 1. Seed the source

`content_sources` document:

| field | value |
|---|---|
| `name` | `Dan Vega` |
| `baseUrl` | `https://www.danvega.dev/blog` |
| `feedUrl` | `null` |
| `sitemapUrl` | `null` |
| `sourceType` | `BLOG` |
| `scrapeStrategy` | `HTML_LISTING` |
| `active` | `true` |

Also add the equivalent entry to `scripts/seed-content-sources.js` for local/dev seeding.

### 2. Fix `isArticleLink()` (shared filter)

Add two exclusions to `SitemapHtmlScraper.isArticleLink()` so they apply to all
`HTML_LISTING` sources:

- Reject a last path segment of `tags` (alongside the existing `tag`).
- Reject a last path segment that is purely numeric (path-style pagination like `/blog/2`).

Keep the change minimal and generic — it must not regress Claude Blog.

### 3. `backfillSource` method on `ContentAggregationAgent`

Add:

```java
public void backfillSource(ContentSource source, Instant since)
```

Reuses the existing scrape → classify → save pipeline (extract the shared loop so
`processSource` and `backfillSource` share it), but **skips any article whose resolved
`publishedDate` is before `since`.** Dedup on `originalUrl` is unchanged, so it is idempotent.
The ongoing scheduled `runAggregation()` is unaffected (no date filter there — page 1 is
naturally recent).

### 4. Mongock change unit `V011SeedAndBackfillDanVegaBlog`

Order `011` (next after `V010`). `@Execution`:

1. If `contentSourceRepository.findByName("Dan Vega").isEmpty()`, save the source (idempotent
   guard, exactly like `V005`).
2. Call `contentAggregationAgent.backfillSource(source, Instant.now().minus(30 days))` inside a
   `try/catch` that **logs failures but never rethrows** — a flaky LLM/network/Kafka call must
   not fail the migration or block application boot (per prod notes, a downed backend cascades
   through nginx). The 6-hourly scheduler backfills any gaps afterward.

`@RollbackExecution`: delete the `Dan Vega` source (mirrors `V005`). Backfilled articles are
left in place (dedup makes re-runs safe).

**Trade-off accepted:** the backfill runs synchronously on first boot (~1 minute for ~10
posts). Mongock records the change unit as executed, so this cost is one-time. Chosen over an
async trigger because it is deterministic, testable, and honest about when data has landed;
the guard removes the boot-failure risk.

## What does NOT change

- No new `ScrapeStrategy`, scraper class, `ScraperFactory` branch, entity, repository,
  controller, or frontend code.
- No MongoDB/Elasticsearch schema changes.
- Indexing, `/api/news`, site search, chat `searchNews`, and embeddings all pick up the new
  articles automatically via the existing `content-changes` Kafka flow.

## Testing

- `SitemapHtmlScraperTest`: `isArticleLink()` accepts `/blog/embabel-1-0-ga`, rejects
  `/blog/tags` and `/blog/2`; a Dan Vega post fixture yields title, body, author, image, and
  the JSON-LD `datePublished`. Confirm Claude Blog cases still pass.
- `ContentAggregationAgent` test: `backfillSource` saves posts on/after `since` and skips older
  ones (mock scraper + LLM as existing tests do).
- Change-unit test mirroring `V010BackfillArticlePublishedDatesTest`: source seeded once
  (idempotent), and a thrown backfill is swallowed (migration still succeeds).

## Rollout / verification

1. Deploy → Mongock runs `V011` once → `Dan Vega` source seeded + last-30-days posts ingested.
2. Verify `GET /api/news` shows Dan Vega articles; check site search `type:"news"` and chat
   `searchNews`.
3. If backfill was skipped due to a transient error, trigger manually via
   `POST /api/admin/aggregation/trigger` or wait for the 6-hourly cron.
4. `POST /api/admin/search/full-sync` / embedding full-sync available to backfill indices if
   needed.
