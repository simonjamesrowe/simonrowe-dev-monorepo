# AI4JVM ingest source and news source-pill cleanup

Date: 2026-08-08

## Summary

Two related pieces of work on `/news-events`:

- **Part A** adds `ai4jvm.com` as a content-aggregation source. It needs a sixth
  scrape strategy, because AI4JVM is a curated index of *external* links rather
  than a publisher of its own articles.
- **Part B** cleans up the source filter pills on the news page. Eight of the
  fourteen pills in production come from ten manually imported articles, and
  the row mixes view modes with sources.

Part B is not optional polish: Part A adds a ninth source name, and the manual
import path mints a new pill on every use, so the pill row degrades further
without it.

## Part A — AI4JVM ingest

### What the site actually is

`https://ai4jvm.com` is a single static HTML file served by GitHub Pages.

- No RSS or Atom feed — `/feed.xml`, `/rss.xml`, `/feed`, `/atom.xml` and
  `/index.xml` all return 404.
- `sitemap.xml` contains exactly one `<loc>`: the homepage.
- The news content is a hand-maintained block, `div.news-bar > ul > li`,
  holding 29 headlines at the time of writing.
- Every headline is a single `<a>` pointing at an **external** host — InfoQ,
  foojay.io, javapro.io, quarkus.io, spring.io, inside.java, GitHub release
  tags, Medium — followed by an em-dash and a hand-written editorial summary.
- The block carries no dates and no images.

### Why no existing strategy works

| Strategy | Result on ai4jvm.com |
| --- | --- |
| `RSS` | No feed exists. |
| `SITEMAP_HTML` | The sitemap's single URL is the homepage; `looksLikeArticle` rejects it. |
| `HTML_LISTING` | `SitemapHtmlScraper.isArticleLink` requires the link host to equal the listing host. Every headline is cross-host, so this returns **zero items with no error**. |
| `HTML` | Events heuristics; wrong shape. |
| `LUMA` | Not a lu.ma calendar. |

Relaxing `isArticleLink`'s same-host rule is rejected: that filter is shared by
every `HTML_LISTING` source, and dropping it would let site navigation and
outbound vendor links into Claude Blog and Dan Vega.

### New strategy: `LINK_ROUNDUP`

Add `LINK_ROUNDUP` to `ContentSource.ScrapeStrategy` and a new
`LinkRoundupScraper` in `com.simonrowe.agents.scrapers`. `ScraperFactory`
routes `LINK_ROUNDUP` to it, reading `source.baseUrl()`.

The scraper is a **link follower**, not a page scraper:

1. Fetch `baseUrl` with jsoup, reusing the existing `USER_AGENT` and
   `TIMEOUT_MS` conventions.
2. Select `.news-bar li`, falling back to `#news li` when the primary selector
   matches nothing.
3. For each `li`, require exactly one `a[href]` resolving to an absolute
   `http`/`https` URL. Take the anchor text as the **curated title** and the
   `li`'s remaining text, stripped of its leading em-dash and whitespace, as
   the **curated summary**.
4. Skip any target URL that `AggregatedArticleRepository.existsByOriginalUrl`
   already holds (see "Deliberate deviations" below).
5. For each remaining target, call the existing
   `SitemapHtmlScraper.scrapeArticlePagePublic(targetUrl)` to obtain real
   content, `og:image` and published date. Sleep 1s between fetches, as the
   other HTML strategies do.
6. On a successful detail fetch, emit that `ScrapedContent` unchanged — its
   title is the publisher's real title, which is what the reader sees on
   clicking through. On failure (blocking, 404, timeout), emit a fallback
   `ScrapedContent(curatedTitle, targetUrl, curatedSummary, null, null, null,
   false)`.

Fallback items are viable rather than degraded: the curated summaries run
150–250 characters, clearing the 50-character threshold below which
`classifyAndSummarize` skips the LLM entirely. They carry no image, so
`BlogImageGenerationService` generates one, and no date, so
`processArticle` falls through to its detail-page fetch and finally to the
fetch date. InfoQ and Medium are the likely blockers.

### Deliberate deviations from the other scrapers

**Cap of 40, not `MAX_ARTICLES = 20`.** The news bar holds 29 items ordered
newest-first. With a cap of 20, items 21–29 are unreachable forever: dedup
happens after scraping, so every subsequent run re-takes the same top 20 and
stops. A cap of 40 lets the first run capture the whole curated list and
leaves headroom as the page grows.

**The scraper takes an `AggregatedArticleRepository` dependency.** No other
scraper depends on a repository, so this is a real layering concession. It buys
the difference between 40 third-party page fetches every six hours and roughly
one or two: without a pre-fetch dedup check, the 40-item cap means we would
re-request every linked article on every scheduled run purely to discover we
already hold them. The check duplicates the agent's own dedup, which stays in
place as the authority; the scraper's copy is an optimisation only.

### Seeding

`V018SeedAndBackfillAi4Jvm`, following `V011SeedAndBackfillDanVegaBlog`: a
`findByName("AI4JVM")` guard, save, then `backfillSource` inside a `try/catch`
that logs and swallows so a failed pre-population cannot block application
boot. `@RollbackExecution` deletes the source by name.

```java
new ContentSource(
    null,
    "AI4JVM",
    "https://ai4jvm.com",
    null,
    null,
    ContentSource.SourceType.NEWS,
    ContentSource.ScrapeStrategy.LINK_ROUNDUP,
    true,
    null,
    null);
```

Backfill window is **120 days**, not the 30 used for Dan Vega. The curated list
spans May to August; a 30-day cutoff would discard most of it on first run, and
those items are never re-offered because the page only grows at the top.

`sourceName` is `"AI4JVM"` for every ingested item, even though each links to
InfoQ, foojay and so on. The card's outbound link still goes to the real
article, and the alternative — per-publisher source names — would add roughly
a dozen one-off pills, which is precisely the problem Part B fixes.

### Accepted risk

`.news-bar li` is a class name in a hand-maintained static file with no feed
and no versioning. A restyle takes the scraper to zero items **silently**:
`runAggregation` only writes `lastError` on a thrown exception, and an empty
result is not one. This is the same exposure every HTML strategy already
carries, and it is accepted rather than mitigated here. The symptom is
`Fetched 0 items from AI4JVM` in the backend log.

## Part B — source pill cleanup

### Current state in production

`GET /api/news/sources` returns fourteen names. Six are real content sources;
eight are host names minted by manual imports, covering ten articles in total.

| Source | Articles | Origin |
| --- | --- | --- |
| Rundown AI | 298 | seeded source |
| Spring Blog | 81 | seeded source |
| Tessl Blog | 65 | seeded source |
| Claude Blog | 39 | seeded source |
| AI Native Dev | 20 | seeded source |
| Dan Vega | 16 | seeded source |
| `anthropic.com` | 2 | manual import |
| `blog.cloudflare.com` | 2 | manual import |
| `tessl.io` | 1 | manual import |
| `code.claude.com` | 1 | manual import |
| `aws.amazon.com` | 1 | manual import |
| `eng.wealthfront.com` | 1 | manual import |
| `engineering.atspotify.com` | 1 | manual import |
| `ssntpl.com` | 1 | manual import |

The cause is `ContentAggregationAgent.importFromUrl`, which sets
`sourceName = extractHostName(url)` unconditionally. Every manual import of a
new host creates a new pill. Cleaning the data without changing this only
delays the regrowth.

### 1. Fold the three duplicates

`V019NormaliseManualImportSourceNames` rewrites `sourceName` on
`aggregated_articles` where the article's host resolves to a source we already
track:

- `tessl.io` → `Tessl Blog`
- `anthropic.com` → `Claude Blog`
- `code.claude.com` → `Claude Blog`

Host matching is by the host of each `ContentSource.baseUrl`, with `www.`
stripped. The Anthropic hosts do not match `Claude Blog`'s `claude.com`
baseUrl, so they need an explicit alias entry — the change unit and the
runtime import path share one helper holding that map, so the two cannot drift.

The other five hosts keep their names. They are genuinely separate publishers
with no existing pill, and their host name is accurate attribution on the card
badge. Fourteen pills become eleven.

### 2. Stop the regrowth

`importFromUrl` resolves the source name through the shared helper: look for a
`ContentSource` whose `baseUrl` host (or alias) matches the import URL's host
and reuse that source's `name`; fall back to `extractHostName` only when
nothing matches.

There is deliberately **no** catch-all bucket such as "Around the Web". A
bucket would re-hide exactly what the overflow in step 4 already hides, while
destroying accurate per-card attribution for genuinely new publishers.

### 3. Counts on the sources endpoint

`NewsController.listSources` returns `List<String>`, so the frontend cannot
sort by volume or apply a threshold. It becomes `List<SourceSummary>` with
`name` and `count`, built from a MongoDB group-by over visible articles,
sorted by count descending then name.

This is a response-shape break. The frontend's `fetchNewsSources` in
`services/newsApi.ts` is the only consumer.

### 4. Restructure the filter row

`NewsEventsPage` currently renders one flat row mixing *view modes* (`Events`,
`Show favourites only`) with *sources*, which makes `Events` read as if it
were a publisher. Split into two rows:

```
[ Events ]  [ ♥ Show favourites only ]

[ All ] [ Rundown AI ] [ Spring Blog ] [ Tessl Blog ] [ Claude Blog ]
[ AI4JVM ] [ AI Native Dev ] [ Dan Vega ] [ More (5) ▾ ]
```

- Row one holds the view modes.
- Row two holds `All` followed by source pills sorted by article count
  descending.
- Any source with **fewer than 3 articles** collapses into a `More ▾` popover
  showing the count of hidden sources. Selecting a source from the popover
  filters exactly as a pill does, and the popover label reflects the active
  selection when a hidden source is selected — otherwise the active filter
  would be invisible.
- The existing mobile behaviour (`overflow-x: auto`, `flex-wrap: nowrap`) moves
  to the source row only; the modes row wraps normally.

Against production data the threshold hides all five remaining strays. Twelve
source names (six seeded, `AI4JVM`, and the five strays) leave seven visible
pills, `AI4JVM` among them.

## Testing

Backend:

- `LinkRoundupScraperTest` — parse a checked-in fixture of the news-bar HTML.
  Assert all 29 items are extracted, cross-host URLs are kept, the curated
  summary has its leading em-dash stripped, links outside `.news-bar` are
  ignored, an `li` with zero or multiple anchors is skipped, and the `#news li`
  fallback fires when `.news-bar` is absent. Detail fetching and the repository
  are mocked; a mocked detail failure must yield the curated fallback item.
- `ScraperFactoryTest` — `LINK_ROUNDUP` routes to `LinkRoundupScraper` with
  `baseUrl`.
- `V018SeedAndBackfillAi4JvmTest` — guard, idempotency, rollback, and that a
  thrown backfill is swallowed. Mirrors the V011 tests.
- `V019NormaliseManualImportSourceNamesTest` — the three folds apply, the five
  strays are untouched, and a re-run is a no-op.
- `ContentAggregationAgentTest` — `importFromUrl` reuses a matching source's
  name, uses an alias where configured, and falls back to the host otherwise.
- `NewsControllerTest` — `/api/news/sources` returns names with counts, ordered
  by count descending, counting only visible articles.

Frontend:

- `NewsEventsPage` tests — sources render in count order; sources under 3
  articles are absent from the visible row and present in the `More` popover;
  selecting from the popover applies the filter and surfaces the active
  selection; the modes row renders separately from the source row.

Both gates must pass: `cd backend && ../gradlew test` (with Checkstyle) and
`cd frontend && npm test`.

## Verification

Per the `content-source-add` runbook, after a local boot:

```bash
docker compose exec -T mongodb mongosh simonrowe --quiet --eval '
  db.aggregated_articles.countDocuments({sourceName: "AI4JVM"});
  db.aggregated_articles.countDocuments({sourceName: "AI4JVM", imageUrl: null});
  db.aggregated_articles.countDocuments({sourceName: "AI4JVM", publishedDate: null});'
```

Both trailing counts must be `0`. Then confirm the fold left no orphans:

```bash
curl -sS 'http://localhost:8080/api/news/sources'
```

`tessl.io`, `anthropic.com` and `code.claude.com` must be absent. Finally, open
`http://localhost:5173/news-events` and confirm the two-row filter layout, the
`More` popover, and that AI4JVM cards carry an image, a sensible date and a
working outbound link to the original publisher.

## Out of scope

- Ingesting anything from ai4jvm.com beyond the `.news-bar` block. The rest of
  the page is a directory of frameworks, people and resources — roughly 467
  links — which is reference material, not news.
- Detecting a silent zero-item scrape after a site restyle.
- Any change to how events are aggregated or filtered.
