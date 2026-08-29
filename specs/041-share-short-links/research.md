# Phase 0 Research: Share links for blogs and news/events

**Feature**: 041-share-short-links | **Date**: 2026-08-28

The design document (`docs/superpowers/specs/2026-08-28-share-short-links-design.md`)
already settled the major decisions and recorded rejected alternatives. This file records
only what had to be resolved *against the current codebase* before the plan could be
written — the things the design left as "check during implementation", plus every place a
naive reading of the design would have hit an existing constraint.

## R1. Where the OG fallback image lives

**Decision**: `frontend/public/images/share-card.png`, referenced by the backend as
`${site.base-url}/images/share-card.png`.

**Rationale**: There are two `/images` roots in this repo and they disagree.

- `backend/src/main/resources/static/images/` holds `global-logo.png` (classpath static).
- `frontend/public/images/` holds `blogs/placeholder.svg` (baked into the frontend image).
- `frontend/vite.config.ts` proxies `/images` to `localhost:8080`, i.e. to the backend.
- Production `frontend/nginx.conf` has **no** `/images/` proxy, so `location /` serves it
  from the frontend bundle.

So in production `/images/**` is the frontend's; in local dev it is the backend's. The
fallback image only ever matters to a crawler fetching an absolute production URL, and
`site.base-url` defaults to `https://simonrowe.dev` even locally — so the emitted URL is
always production's, and production serves it from the frontend bundle. Putting the file
there needs no nginx change.

**Alternatives considered**: adding a `/images/` proxy to `frontend/nginx.conf` to mirror
the vite dev proxy and putting the card in the backend classpath. Rejected: `nginx.conf`
is bind-mounted in production (a deploy consideration the design already flags once), the
change would take `/images/blogs/placeholder.svg` away from the frontend bundle that
currently serves it, and it fixes a latent inconsistency this feature did not cause.
Duplicating the file into both roots was also rejected — two copies drift.

## R2. Change-unit number and the backfill's safety

**Decision**: `V029CreateShortLinksAndBackfill`, order `029`.

**Rationale**: The highest existing change unit is
`V028RefineUniversalMusicRole` (`backend/src/main/java/com/simonrowe/migration/changeunits/`),
so `029` is next and matches the design.

The design's claim that the backfill is safe to run against the shared Testcontainers Mongo
holds: it reads `blogs`, `aggregated_articles`, `aggregated_events` and writes
`short_links`, with no external I/O. This is the distinction that bit the repo before —
`V011`/`V018`-style seeding units that make live network calls have to be disabled in
tests; a pure-Mongo one does not.

The unit does **two** things (indexes + backfill), unlike `V020`/`V022` which create
indexes only. That is deliberate and safe here because, unlike release records or
narrations, short links are *not* derived self-healing data — a slug is a permanent public
identifier and there is no runtime process that would recreate a lost one with the same
value. It must therefore be a change unit and a backup collection, not a startup recorder.
Index creation is still exposed as a `public static createIndexes(MongoTemplate)` so
`RestoreService` can call it directly (Mongock will not re-run a recorded unit) — the same
shape as `V020CreateArticleSummaryIndexes.createIndexes`.

## R3. Where minting hooks in

**Decision**: three call sites, all calling the same idempotent
`ShortLinkService.ensureFor`.

| Site | File | Why here |
|---|---|---|
| Blog create | `AdminBlogController.create` | after `blogRepository.save`, where the id first exists |
| Blog update | `AdminBlogController.update` | title may have changed; `ensureFor` returns the existing link unchanged, so this is a no-op by design and exists only so a post that predates the link gets one |
| Article ingest | `ContentAggregationAgent.processArticle` | after `articleRepository.save(article)` |
| Event ingest | `ContentAggregationAgent.processEvent` | after `eventRepository.save(event)` |
| Backfill | `V029` | everything that already exists |

**Rejected**: hooking into the existing `ContentChangePublisher` Kafka event
(`ContentChangeEvent.ContentType.BLOG` / `AGGREGATED_ARTICLE` / `AGGREGATED_EVENT`), which
all four save sites already publish. It would give one hook point instead of four — but it
makes the slug's existence asynchronous and dependent on a working broker, and a listing
rendered in the gap silently drops the Share button. The design explicitly chose eager
minting to keep the frontend dumb. `ensureFor` throwing must not fail a blog save, so the
call is wrapped and logged.

## R4. The batched `shortUrl` lookup

**Decision**: `ShortLinkService.urlsFor(contentType, Collection<String> contentIds)`
returning `Map<String, String>`, backed by
`findByContentTypeAndContentIdIn(...)` — one query per listing, served by the unique
`(contentType, contentId)` index.

Applied at:

- `BlogService.listPublished` / `getLatest` — one query over the ~43 published posts.
- `BlogService.getPublishedById` — a single-item lookup, so a plain `findOne`.
- `NewsController.list` — the `Page<AggregatedArticle>` is mapped in one place, so the
  batch is taken from `articles.getContent()` before mapping.
- `NewsController.getById`, `EventsController.getEventById` — single lookups.
- `EventsController.listEvents` — same shape as news.

**Constraint discovered**: `ArticleResponse.from` and `EventResponse.from` are static
one-arg factories called from six places between them (`NewsController`,
`EventsController`, `AdminAggregationController`, `FavouritesService`). Adding a required
`shortUrl` parameter would touch all of them. Following the existing
`BlogSummaryResponse.fromEntity(blog)` / `fromEntity(blog, featuredImageUrl)` precedent,
each gets a second overload taking the resolved URL, and the one-arg form delegates with
`null`. Admin and favourites paths keep the one-arg form; only the public listing and
detail paths pass a URL. This is also what makes the field legitimately nullable.

## R5. Serving `/s/{slug}` — routing, security and rate limiting

**Decision**: `@Controller` (not `@RestController`) at `/s/{slug}`, returning
`ResponseEntity<String>` with `Content-Type: text/html; charset=utf-8`.

Three existing mechanisms had to be checked:

1. **Security** — `SecurityConfig` ends `.anyRequest().permitAll()`, so `/s/**` is already
   public and needs no matcher. `SecurityConfigTest` gains an assertion so a future
   tightening is caught. Confirmed by reading `com.simonrowe.auth.SecurityConfig`.
2. **Rate limiting** — `WebConfig.addInterceptors` registers `RateLimitInterceptor` on an
   explicit four-path allowlist (`/mcp/**`, `/api/blogs/*/narration`,
   `/api/news/*/summary`, `/api/news/*/summary/narration`). `/s/**` is not in it and must
   not be added: a link pasted into a busy Slack workspace can produce a burst of unfurl
   fetches from one IP range, and 429-ing them breaks the preview.
3. **Caching** — `SecurityConfig` sets `.headers(headers -> headers.cacheControl(disable))`,
   which emits `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` on every
   response. For `/s/` that is actually what we want: a cached redirect page would stop the
   counter incrementing. No change needed, but worth not "fixing".

**Rendering**: hand-built string with a small `escapeHtml` helper, not a template engine.
The repo has no view technology on the classpath (no Thymeleaf, no Mustache) and adding one
for a single ~1KB document fails Constitution V (Simplicity). The escaping is the risk, so
it gets its own unit test with `"`, `<`, `&` and a title containing `</script>`.

## R6. Not-found presentation

**Decision**: `404` with a self-contained themed HTML body rendered by the same builder,
all CSS inlined.

**Rationale**: the design says "the themed not-found body". The SPA's `NotFoundPage`
cannot serve it — reaching it would mean a redirect to the SPA, which is exactly what the
design forbids for an unknown slug. This mirrors the precedent already in the repo:
`config/nginx/maintenance/*.html` are standalone, all-CSS-inlined pages specifically
because the frontend that would serve shared assets may be the thing that is down. Same
constraint here for a different reason: the response must be self-contained because it is
being read by whatever followed a bad link.

## R7. Unfurler detection

**Decision**: `UnfurlerDetector` — a static list of case-insensitive substrings plus one
compiled catch-all regex, checked against `User-Agent`.

Specific tokens from the design: `Slackbot`, `facebookexternalhit`, `LinkedInBot`,
`WhatsApp`, `Twitterbot`, `Discordbot`, `TelegramBot`, `redditbot`. Catch-all:
`bot|crawler|spider|preview`.

**Note on the catch-all**: it makes every specific token above redundant except
`facebookexternalhit` and `WhatsApp`. They are kept anyway — the table-driven test
documents the real UA strings, which is the artefact that has value when a platform
changes its agent. A null or blank `User-Agent` counts as a human (an unidentified client
is more likely a stripped-down browser than a robot, and the design's stated cost of a miss
is only an inflated statistic).

## R8. Slug generation edge cases

**Decision**: `ShortLinkSlugger` — pure static methods, no Mongo, no Spring.

Resolved details the design left implicit:

- **Accent stripping** uses `java.text.Normalizer.normalize(s, NFD)` then strips
  `\p{M}`. No new dependency.
- **"Whole words up to 20 characters"**: split the normalised slug on `-`, accumulate
  while `length + 1 + word.length() <= 20`. If the *first* word alone exceeds 20, it is
  hard-cut to 20 — otherwise a title like "Internationalisation" would produce an empty
  slug and fall through to the random code, which is worse than a truncation.
- **Collision suffixes**: the design says "cut to 17 characters plus `-2`, `-3`, …".
  Taken literally that breaks at attempt 10 (`-10` is three characters, giving 20 exactly —
  fine — but `-100` gives 21). The rule implemented is: reserve
  `("-" + n).length()` characters, cut the base to `20 - reserved` **on a word boundary
  where possible**, and append. Attempts run 2..99, then fall back to the random code.
- **Random fallback**: 6 characters from `[a-z0-9]`, `java.security.SecureRandom`,
  retried on collision. 36^6 ≈ 2.2 billion, so a collision is a formality, but the retry
  loop exists because the insert is what proves it.
- **Collision detection is the unique `_id`, not a pre-read.** `ensureFor` attempts
  `insert` and catches `DuplicateKeyException`, rather than `existsById` then `insert`.
  A pre-read is a race; the index is the truth. This is the same insert-first dedup guard
  `ArticleSummaryService` already uses.

## R9. Deep-linking `NewsEventsPage`

**Decision**: `useSearchParams` for `?article=` / `?event=`, plus a targeted fetch when the
id is not in the loaded page.

Facts established by reading `frontend/src/pages/NewsEventsPage.tsx`:

- `useScrollToHash(!loading)` is already mounted (line ~93) but the cards carry no `id`
  attributes — only the `#news` / `#events` *sections* do. Adding `id={article.id}` to the
  hero card, the grid card and the timeline item is what makes it useful.
- The drawer is already driven by an id, not an object: `summaryArticleId` state plus a
  `summaryArticle` lookup across `[...articles, ...favouriteArticles]`. Opening from a
  query parameter therefore needs no new drawer state — only a way to get the article into
  one of those two arrays.
- **The not-in-page case is the real work.** `summaryArticle` resolves to `null` when the
  id is absent, and the render is `{summaryArticle && <NewsSummaryDrawer .../>}` — so the
  page loads and silently does nothing, exactly as the design predicts. Resolved by a third
  source array, `deepLinkedArticles`, filled by `fetchArticleById` (`GET /api/news/{id}`,
  which already exists and already filters on `visible`) and included in the lookup.
- Events have no drawer at all — the timeline items are plain external anchors. `?event=`
  therefore means "scroll to and highlight", not "open a panel", which is what FR-024's
  wording ("focused on the event") allows. A targeted `GET /api/events/{id}` fills the same
  gap when the event is not in the loaded set.

## R10. Frontend `ShareButton` capability detection

**Decision**: three-tier, checked at click time not render time.

1. `navigator.share` present → `await navigator.share({ title, url })`. Catch and swallow
   `AbortError` (`err.name === 'AbortError'`) — a cancelled sheet is not a failure.
2. `navigator.clipboard?.writeText` → copy, set a `copied` flag, clear after 2000ms via a
   `setTimeout` cleared on unmount.
3. `document.execCommand('copy')` over a temporary off-screen `<textarea>` — needed only
   for a non-secure context (plain-HTTP local dev), where `navigator.clipboard` is
   `undefined`.

Checked at click time because jsdom has neither API, so render-time detection would make
every test exercise only tier 3. The tests stub `navigator.share` / `navigator.clipboard`
per case.

`FavouriteButton` is the shape model, including the
`e.preventDefault(); e.stopPropagation()` in the click handler — news and blog cards are
`<a>` elements, so without it pressing Share navigates to the article.

## R11. Admin surface

**Decision**: `GET /api/admin/short-links` returning an unpaged list; a new
`/admin/short-links` route and `ShortLinksAdmin` page with client-side sorting; a
`clickCount` column added to `BlogsAdmin`.

- The endpoint is under `/api/admin/**`, already `hasRole(DEV_PORTAL_ADMIN)` in
  `SecurityConfig` — no new matcher.
- Unpaged is right: one row per piece of content, so ~43 blogs + articles + events, in the
  low hundreds. `AdminBlogController` pages because blogs are edited; this table is read
  only. Client-side sorting for the same reason.
- The title is **not** stored on the link (the design's data model has no title field), so
  the endpoint joins to the three source collections. Three batched `findAllById` calls,
  not one per row.
- The blog list's `clickCount` comes from the same `urlsFor`-shaped batch lookup, extended
  to return counts. `AdminBlogController.toDto` gains one key.

## R12. What must not be forgotten

Recorded here because each is invisible until production and has bitten this repo before:

- `short_links` must be added to **both** `BackupService.BACKUP_COLLECTIONS` and
  `RestoreService.IMPORT_ORDER_INDEPENDENT` (it holds no `@DBRef`, so ordering is free),
  and `RestoreService` must call `V029...createIndexes(mongoTemplate)` after import —
  a restore drops collections and their indexes with them, and Mongock will not re-run.
- `frontend/nginx.conf` is **bind-mounted from the deploy directory** in production, not
  baked into the image, so the new `location /s/` only lands via `sync-config`
  fast-forwarding the deploy checkout. Verify with
  `curl -i https://simonrowe.dev/s/<known-slug>` after deploy.
- `frontend/vite.config.ts` needs the matching `/s` proxy or the endpoint 404s locally.
- Mongo indexes come from the change unit, never from `@CompoundIndex`:
  `auto-index-creation` is off.
- Do **not** run `.specify/scripts/bash/update-agent-context.sh` — `CLAUDE.md` records that
  it fails with `grep: repetition-operator operand invalid` and silently strips the lead
  line from eight existing entries.
