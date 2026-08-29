# Tasks: Share links for blogs and news/events

**Input**: Design documents from `/specs/041-share-short-links/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/short-links.yaml, quickstart.md

**Tests**: INCLUDED. The design document has a dedicated Testing section naming eight
required suites, and Constitution III makes coverage gates non-negotiable.

**Organization**: Grouped by user story so each is independently implementable and
testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story the task belongs to (US1–US4)

## Path Conventions

Web app: `backend/src/main/java/com/simonrowe/`, `backend/src/test/java/com/simonrowe/`,
`frontend/src/`, `frontend/tests/`.

## Story sequencing note

Story phases run **US4 → US1 → US2 → US3**, not in bare priority order. US4 (P2) mints the
links; until links exist there is nothing for the P1 stories to share, so US4 is sequenced
ahead as the plan records. US1 remains the MVP in the sense that matters — it is the first
phase a visitor can see.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: The configuration and package skeleton every later phase writes into.

- [X] T001 Create the package directory `backend/src/main/java/com/simonrowe/shortlink/` and the test package `backend/src/test/java/com/simonrowe/shortlink/`
- [X] T002 Add the `site.base-url` property to `backend/src/main/resources/application.yml` as `site:\n  base-url: ${SITE_BASE_URL:https://simonrowe.dev}`, with a comment recording that it must never be blank because a relative `og:image` is silently dropped by crawlers
- [X] T003 [P] Create `backend/src/main/java/com/simonrowe/shortlink/ShortLinkProperties.java` — a `@ConfigurationProperties("site")` record with `String baseUrl`, normalising a trailing slash away; register it via `@EnableConfigurationProperties` on the new config or on `WebConfig` following the `RateLimitConfig` precedent
- [X] T004 [P] Add a committed OG fallback image at `frontend/public/images/share-card.png` (1200×630, the site's dark theme, title text only — no per-post content). Per research R1 this goes in the frontend public dir, NOT the backend classpath, because production serves `/images/**` from the frontend bundle

**Checkpoint**: `site.base-url` resolves and the fallback image is committed.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The entity, slug algorithm and service that every user story depends on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T005 [P] Create `backend/src/main/java/com/simonrowe/shortlink/ShortLinkContentType.java` — enum `BLOG`, `ARTICLE`, `EVENT`, each carrying a `destinationPath(String contentId)` returning `/blogs/{id}`, `/news-events?article={id}`, `/news-events?event={id}`. Javadoc must record why this is a NEW enum rather than a reuse of `NarrationContentType` or `ContentChangeEvent.ContentType` (neither has the right member set — see data-model.md)
- [X] T006 [P] Create `backend/src/main/java/com/simonrowe/shortlink/ShortLink.java` — a record `@Document(collection = "short_links")` with `@Id String slug`, `ShortLinkContentType contentType`, `String contentId`, `long clickCount`, `Instant lastClickedAt`, `Instant createdAt`. Javadoc must state that the slug IS the `_id` so the redirect is a primary-key lookup and uniqueness is enforced by Mongo, and that `@CompoundIndex` is deliberately absent because `auto-index-creation` is off
- [X] T007 Create `backend/src/main/java/com/simonrowe/shortlink/ShortLinkRepository.java` — `MongoRepository<ShortLink, String>` with `Optional<ShortLink> findByContentTypeAndContentId(...)` and `List<ShortLink> findByContentTypeAndContentIdIn(ShortLinkContentType, Collection<String>)`
- [X] T008 [P] Write `backend/src/test/java/com/simonrowe/shortlink/ShortLinkSluggerTest.java` FIRST — cover: word-boundary truncation (`"Exactly-once semantics in Kafka"` → `exactly-once`, never a mid-word chop); the 20-character ceiling holding through `-2`, `-10` and `-99` suffixes; accent stripping (`"Café déjà vu"` → `cafe-deja-vu`); an empty result from an emoji-only title falling back to a 6-character `[a-z0-9]` code; a non-Latin title doing the same; a single first word longer than 20 characters being hard-cut rather than falling through to the random code; and that every output matches `^[a-z0-9][a-z0-9-]{0,19}$`
- [X] T009 Create `backend/src/main/java/com/simonrowe/shortlink/ShortLinkSlugger.java` — pure static methods, no Mongo, no Spring. `slugify(String title)`; `withSuffix(String base, int attempt)` reserving `("-" + attempt).length()` characters and cutting the base on a word boundary where possible (research R8 — the design's literal "cut to 17" breaks past attempt 9); `randomCode()` using `SecureRandom` over `[a-z0-9]`. Accent stripping via `java.text.Normalizer` NFD + strip `\p{M}`; no new dependency
- [X] T010 Create `backend/src/main/java/com/simonrowe/shortlink/ShortLinkService.java` with: `String ensureFor(ShortLinkContentType, String contentId, String title)` — return the existing link's slug unchanged if one exists, else attempt `insert` and catch `DuplicateKeyException`, retrying with `withSuffix` for attempts 2..99 then `randomCode()`. **Insert-and-catch, never `existsById` then `insert`** — a pre-read is a race and the unique `_id` is the truth (same insert-first guard `ArticleSummaryService` uses)
- [X] T011 Add `Map<String, String> urlsFor(ShortLinkContentType, Collection<String> contentIds)` to `ShortLinkService`, backed by `findByContentTypeAndContentIdIn` — ONE query per listing (SC-008), served by the unique compound index. Also add `Optional<String> urlFor(ShortLinkContentType, String contentId)` for single-item paths. Both return the full absolute `${site.base-url}/s/{slug}` so the frontend never concatenates a base
- [X] T012 Add `void recordClick(String slug)` to `ShortLinkService` — a fire-and-forget `$inc` on `clickCount` plus `$set` on `lastClickedAt` via `MongoTemplate.updateFirst`, wrapped in try/catch that **logs at WARN and swallows**. A datastore failure must never stop the redirect (FR-016), and a silently-dropped increment with no log line would make an under-counting bug undiagnosable (Constitution IV)
- [X] T013 Write `backend/src/test/java/com/simonrowe/shortlink/ShortLinkServiceTest.java` extending `AbstractIntegrationTest` (Testcontainers, per Constitution III) — assert idempotency (two `ensureFor` calls for the same content return one slug and leave one document); collision between two DISTINCT items with the same title producing two distinct slugs both within 20 characters; `urlsFor` returning absolute URLs and issuing one query for a 24-item list; and `recordClick` incrementing and stamping `lastClickedAt`

**Checkpoint**: slugs can be minted and looked up. No user-visible change yet.

---

## Phase 3: User Story 4 - Existing content becomes shareable (Priority: P2, sequenced first)

**Goal**: Every existing and future blog post, article and event has exactly one share
link, created without anyone re-saving anything.

**Independent test**: With existing content in place, run the backend and confirm
`db.short_links.countDocuments({})` matches the total content count. Restart and confirm it
is unchanged.

### Tests for US4

- [X] T014 [P] [US4] Write `backend/src/test/java/com/simonrowe/migration/changeunits/V029CreateShortLinksAndBackfillTest.java` — seed blogs, articles and events, run `execution`, assert one link per item; run it a **second** time and assert no additional links and no changed slug; assert both indexes exist with the compound one `unique: true`; assert two seeded items with the same title get distinct slugs
- [X] T015 [P] [US4] Add assertions to the existing `backend/src/test/java/com/simonrowe/dataops/` tests that `short_links` appears in `BackupService.BACKUP_COLLECTIONS` and `RestoreService.IMPORT_ORDER_INDEPENDENT`

### Implementation for US4

- [X] T016 [US4] Create `backend/src/main/java/com/simonrowe/migration/changeunits/V029CreateShortLinksAndBackfill.java` — `@ChangeUnit(id = "create-short-links-and-backfill", order = "029", author = "simonrowe")`. Expose `public static void createIndexes(MongoTemplate)` creating `idx_short_link_content` on `{contentType: 1, contentId: 1}` **unique**, following the `V020CreateArticleSummaryIndexes.createIndexes` shape. `@Execution` calls it then backfills over `blogs`, `aggregated_articles` and `aggregated_events`. Javadoc must record that unlike V020/V022 this unit also writes data, and why that is right here (a slug is a permanent public identifier with no runtime process that would recreate a lost one — it is not derived self-healing data), and that the backfill is pure Mongo with no external I/O so it is safe against the shared Testcontainers Mongo
- [X] T017 [US4] Mint on blog save: in `backend/src/main/java/com/simonrowe/admin/AdminBlogController.java`, inject `ShortLinkService` and call `ensureFor(BLOG, saved.id(), saved.title())` after `blogRepository.save` in **both** `create` and `update`, inside a try/catch that logs and continues. A mint failure must not fail the save; the post simply renders with no Share control (the nullable-`shortUrl` path)
- [X] T018 [US4] Mint on ingest: in `backend/src/main/java/com/simonrowe/agents/ContentAggregationAgent.java`, call `ensureFor(ARTICLE, saved.id(), saved.title())` after `articleRepository.save(article)` in `processArticle` and `ensureFor(EVENT, ...)` after `eventRepository.save(event)` in `processEvent`, same try/catch. Record in a comment why this is eager rather than hung off the existing `ContentChangePublisher` Kafka event (research R3: an async slug makes the Share button vanish from a listing rendered in the gap)
- [X] T019 [US4] Add `"short_links"` to `BACKUP_COLLECTIONS` in `backend/src/main/java/com/simonrowe/dataops/BackupService.java`, with a comment recording that these slugs are in links already pasted into other people's Slack channels, so dropping them on a restore breaks URLs that exist in the wild
- [X] T020 [US4] Add `"short_links"` to `IMPORT_ORDER_INDEPENDENT` in `backend/src/main/java/com/simonrowe/dataops/RestoreService.java` (no `@DBRef`, so order is free — same slot as `favourites` and `article_summaries`) **and** call `V029CreateShortLinksAndBackfill.createIndexes(mongoTemplate)` after import, because a restore drops indexes with the collection and Mongock will not re-run a recorded unit

**Checkpoint**: US4 complete and independently verifiable via the collection.

---

## Phase 4: User Story 1 - Share a blog post that unfurls (Priority: P1) 🎯 MVP

**Goal**: A readable link from the blog post page and listing cards, that unfurls with
title, description and image, and lands on the post.

**Independent test**: Press Share on a post, `curl` the copied link, confirm OG tags and an
absolute `og:image`, then open it in a browser and land on the post.

### Tests for US1

- [X] T021 [P] [US1] Write `backend/src/test/java/com/simonrowe/shortlink/UnfurlerDetectorTest.java` — table-driven over the **real** UA strings for Slackbot, facebookexternalhit, LinkedInBot, WhatsApp, Twitterbot, Discordbot, TelegramBot and redditbot, plus the generic `bot|crawler|spider|preview` catch-all. Assert both directions: a Safari/Chrome/Firefox UA and a `curl` UA are NOT unfurlers, and null/blank counts as human
- [X] T022 [P] [US1] Write `backend/src/test/java/com/simonrowe/shortlink/ShareDocumentRendererTest.java` — assert HTML escaping of `"`, `<`, `&` and a title containing `</script>` in every interpolated position (title, description, URL), and that the 404 body is self-contained with all CSS inlined and no external asset reference
- [X] T023 [US1] Write `backend/src/test/java/com/simonrowe/shortlink/ShortLinkControllerTest.java` extending `AbstractIntegrationTest` — assert on the actual HTML: all six OG/Twitter tags present; **`og:image` starts with `http` for all three resolution rules including the fallback**; `og:url` and `<link rel="canonical">` point at the destination not at `/s/`; a `<noscript>`-visible `<a>` exists; status is 200 with **no `Location` header** for all three content types; an unknown slug is 404 with the themed body and no `Location`; a browser UA increments `clickCount` by exactly 1 and a Slackbot UA increments it by 0
- [X] T024 [P] [US1] Add a test to `backend/src/test/java/com/simonrowe/auth/SecurityConfigTest.java` asserting `GET /s/{slug}` is reachable anonymously, so a future tightening of `.anyRequest().permitAll()` cannot silently break every shared link
- [X] T025 [P] [US1] Write `frontend/tests/components/ShareButton.test.tsx` — all three paths: `navigator.share` present (called with title and url); `navigator.clipboard.writeText` present (copies, shows "Copied", reverts after 2s with fake timers); neither present (`document.execCommand` fallback). Plus: a rejected `navigator.share` with `err.name === 'AbortError'` surfaces no error, and a click does not navigate the parent anchor

### Implementation for US1

- [X] T026 [P] [US1] Create `backend/src/main/java/com/simonrowe/shortlink/UnfurlerDetector.java` — case-insensitive substring list plus one compiled catch-all `Pattern`. Comment must record that the catch-all makes most specific tokens redundant and they are kept anyway so the test documents the real strings, and that a null/blank UA counts as human because the stated cost of a miss is only an inflated statistic
- [X] T027 [US1] Create `backend/src/main/java/com/simonrowe/shortlink/ShareDocumentRenderer.java` — builds the ~1KB OG document (og:title, og:description, og:image, og:url, og:type, twitter:card=summary_large_image, canonical, `<meta http-equiv="refresh" content="0;url=…">`, `<script>location.replace(…)</script>`, visible `<a>`) and the themed 404 body, with a private `escapeHtml`. String concatenation, not a template engine — the repo has no view technology on the classpath and adding one for one document fails Constitution V
- [X] T028 [US1] Add OG image resolution to `ShareDocumentRenderer` (or a small collaborator): a `/uploads/…` path gets `base-url` prepended; an already-absolute `http(s)` URL passes through; anything else or null falls back to `${base-url}/images/share-card.png`. **Never emit a relative `og:image`** — crawlers drop it silently
- [X] T029 [US1] Create `backend/src/main/java/com/simonrowe/shortlink/ShortLinkController.java` — `@Controller` (not `@RestController`) mapping `GET /s/{slug}`, returning `ResponseEntity<String>` with `Content-Type: text/html; charset=utf-8`. Resolves the link, loads the target's title/description/image from the right repository, calls `recordClick` unless `UnfurlerDetector` matches the `User-Agent`, and returns 200 HTML — or the themed 404 for an unknown slug, **never a redirect**. Comment must record that `/s/**` is deliberately absent from `RateLimitInterceptor`'s allowlist in `WebConfig` (a burst of unfurl fetches from one address range must not be 429'd) and that `SecurityConfig`'s global cache-control disable is correct here because a cached document would stop the counter incrementing
- [X] T030 [US1] Add `location /s/ { proxy_pass http://backend:8080/s/; ... }` to `frontend/nginx.conf` alongside the existing `/api/` and `/uploads/` blocks, with the same four `proxy_set_header` lines. Comment must record that this file is bind-mounted from the deploy directory in production, so it does not ship with the frontend image
- [X] T031 [P] [US1] Add a `'/s'` entry to the `server.proxy` map in `frontend/vite.config.ts` targeting `http://localhost:8080`, or `/s/` 404s in local development
- [X] T032 [US1] Add `shortUrl` to `backend/src/main/java/com/simonrowe/blog/BlogSummaryResponse.java` and `BlogDetailResponse.java` as a nullable trailing component, with a **second overload** of `fromEntity` taking the resolved URL and the existing form delegating with `null` — following the existing `fromEntity(blog)` / `fromEntity(blog, featuredImageUrl)` precedent
- [X] T033 [US1] Populate `shortUrl` in `backend/src/main/java/com/simonrowe/blog/BlogService.java` — one batched `urlsFor(BLOG, ids)` in `listPublished` and `getLatest`, a single `urlFor` in `getPublishedById`
- [X] T034 [P] [US1] Add `shortUrl?: string | null` to `BlogSummary` and `BlogDetail` in `frontend/src/types/blog.ts`
- [X] T035 [US1] Create `frontend/src/components/common/ShareButton.tsx` — props `{ url: string; title: string; label?: string; className?: string }`. Capability detection **at click time, not render time** (jsdom has neither API, so render-time detection would make every test exercise only the fallback): `navigator.share` → `navigator.clipboard.writeText` → `document.execCommand('copy')` over an off-screen textarea. Swallow `AbortError`. Show a `Check` icon and "Copied" for 2000ms via a `setTimeout` cleared on unmount. Copy `FavouriteButton`'s `e.preventDefault(); e.stopPropagation()` — the cards are `<a>` elements
- [X] T036 [P] [US1] Add `.share-button` and `.share-button--copied` styles to `frontend/src/styles.css` using BEM and the existing CSS custom properties, matching `.favourite-button`'s sizing so the card action rows stay aligned
- [X] T037 [US1] Render `ShareButton` in the post header in `frontend/src/components/blog/BlogDetail.tsx`, near the title beside `BlogNarration`, only when `blog.shortUrl` is present
- [X] T038 [P] [US1] Render `ShareButton` in `frontend/src/components/blog/ArticleCard.tsx` in the existing `.article-card__actions` row beside `ListenButton`, only when `blog.shortUrl` is present
- [X] T039 [P] [US1] Render `ShareButton` in `frontend/src/components/blog/FeaturedArticle.tsx` on the same conditional

**Checkpoint**: US1 shippable — blog links are shareable and unfurl. This is the MVP.

---

## Phase 5: User Story 2 - Share a news item and land on the first-party summary (Priority: P1)

**Goal**: Sharing a news item points at simonrowe.dev's own summary and audio, and the
recipient arrives with the summary panel already open.

**Independent test**: Press Share on a news card, open the link in a fresh session, and
confirm the summary panel for that exact article opens and the page scrolls to the card.

### Tests for US2

- [X] T040 [US2] Write `frontend/tests/pages/NewsEventsPage.deeplink.test.tsx` — render at `?article=<id>` with the id present in the mocked first page and assert the drawer opens; render at `?article=<id>` with the id **absent** from the first page and assert `fetchArticleById` is called and the drawer still opens (**this is the case most likely to be missed** — without it the page loads and silently does nothing); render at `?event=<id>` and assert the page focuses that event; render with no query parameter and assert no drawer

### Implementation for US2

- [X] T041 [P] [US2] Add `shortUrl` to `backend/src/main/java/com/simonrowe/aggregation/ArticleResponse.java` and `EventResponse.java` as a nullable trailing component, with a **second overload** of `from(...)` taking the resolved URL and the one-arg form delegating with `null`. The one-arg form has six callers between `AdminAggregationController` and `FavouritesService`; leaving them on it is deliberate and is part of why the field is nullable
- [X] T042 [US2] Populate `shortUrl` in `backend/src/main/java/com/simonrowe/aggregation/NewsController.java` — one batched `urlsFor(ARTICLE, ...)` over `articles.getContent()` before the `.map(ArticleResponse::from)` in `list`, and a single `urlFor` in `getById`
- [X] T043 [US2] Populate `shortUrl` in `backend/src/main/java/com/simonrowe/aggregation/EventsController.java` — the same shape across all three branches of `listEvents`, and a single `urlFor` in `getEventById`
- [X] T044 [P] [US2] Add `shortUrl?: string | null` to `ArticleResponse` in `frontend/src/types/news.ts` and to `EventResponse` in `frontend/src/types/events.ts`
- [X] T045 [P] [US2] ~~Add `fetchArticleById`/`fetchEventById`~~ — **already present** as `fetchNewsById` and `fetchEventsById`; no new service code was needed, only the imports in `NewsEventsPage`
- [X] T046 [US2] Render `ShareButton` in all three card action rows in `frontend/src/pages/NewsEventsPage.tsx` — the hero card, the grid card and the timeline item — as the **fourth** control after Listen, Summarise and Favourite, only when `shortUrl` is present
- [X] T047 [US2] Add `id={article.id}` to the hero and grid cards and `id={event.id}` to the timeline items in `frontend/src/pages/NewsEventsPage.tsx`, so the already-mounted `useScrollToHash(!loading)` can find them (today only the `#news` / `#events` sections carry ids)
- [X] T048 [US2] Add `useSearchParams` deep-linking to `frontend/src/pages/NewsEventsPage.tsx` — read `?article=`, set `summaryArticleId` and drive the existing open path (`handleSummaryOpen`); read `?event=` and scroll to / highlight that event. The drawer is already id-driven so no new drawer state is needed
- [X] T049 [US2] Add the not-in-page fallback to `frontend/src/pages/NewsEventsPage.tsx` — a `deepLinkedArticles` state array filled by `fetchArticleById` when the query id is absent from both `articles` and `favouriteArticles`, included in the `summaryArticle` lookup; the same for events via `fetchEventById`. Without this the page loads and silently does nothing for a shared link to an article that has fallen off page one
- [X] T050 [US2] Check the four-control row at ~375px width and adjust `frontend/src/styles.css` if it wraps badly. **The fix is icon-only, never removal.**

**Checkpoint**: US2 shippable — news and event links point at first-party work.

---

## Phase 6: User Story 3 - See whether shared links are being opened (Priority: P2)

**Goal**: The owner can see click counts per link and per blog post.

**Independent test**: Open a short link three times from a browser, then confirm the admin
table shows 3 and a recent last-opened time.

### Tests for US3

- [X] T051 [P] [US3] Write `backend/src/test/java/com/simonrowe/shortlink/AdminShortLinkControllerTest.java` extending `AbstractIntegrationTest` — assert 401 anonymous, 403 with a non-admin role, 200 with `ROLE_DEV_PORTAL_ADMIN`; that rows carry slug, absolute `shortUrl`, type, joined title, `clickCount` and `lastClickedAt`; and that a link whose content has been deleted returns a null title rather than failing

### Implementation for US3

- [X] T052 [P] [US3] Create `backend/src/main/java/com/simonrowe/shortlink/AdminShortLinkResponse.java` per `contracts/short-links.yaml`
- [X] T053 [US3] Create `backend/src/main/java/com/simonrowe/shortlink/AdminShortLinkController.java` — `GET /api/admin/short-links`, unpaged (one row per piece of content, low hundreds, read-only, sorted in the browser). Joins titles with **three batched `findAllById` calls**, one per source collection, not one per row. Already covered by `hasRole(DEV_PORTAL_ADMIN)` on `/api/admin/**`; no new `SecurityConfig` matcher
- [X] T054 [US3] Add `clickCount` to the list DTO in `backend/src/main/java/com/simonrowe/admin/AdminBlogController.java` — one key in `toDto`, fed by a batched lookup over the page's blog ids
- [X] T055 [P] [US3] Add `fetchShortLinks(getAccessToken)` and an `AdminShortLink` type to `frontend/src/services/adminApi.ts`, and add `clickCount?: number | null` to the existing `AdminBlog` type
- [X] T056 [US3] Create `frontend/src/pages/admin/ShortLinksAdmin.tsx` — an `admin-table` of slug, title, type, clicks and last clicked, with client-side column sorting, following the `BlogsAdmin` markup and loading/error conventions
- [X] T057 [P] [US3] Add the `/admin/short-links` route to `frontend/src/App.tsx` inside the existing `/admin` route block
- [X] T058 [P] [US3] Add a `{ path: '/admin/short-links', label: 'Share Links', icon: <Link2 size={18} /> }` entry to `navItems` in `frontend/src/components/admin/AdminLayout.tsx` (Lucide React only, per Constitution II)
- [X] T059 [P] [US3] Add a Clicks column to the table in `frontend/src/pages/admin/BlogsAdmin.tsx`

**Checkpoint**: all four user stories complete.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T060 Run `cd backend && ../gradlew checkstyleMain checkstyleTest` and fix every Google Java Style violation (Constitution III, non-negotiable)
- [X] T061 Run `cd backend && ../gradlew test` and confirm the JaCoCo 0.78 floor still passes
- [X] T062 Run `cd frontend && npm test && npm run lint && npm run test:coverage` and confirm the four coverage floors (lines 45, statements 45, branches 78, functions 58) still pass — new untested UI can push these under
- [ ] T063 Walk `specs/041-share-short-links/quickstart.md` end to end against a local stack with restored prod data, including the two verifications that only reproduce with real content: an article id that is **not** on page one, and the click counter incrementing by exactly 1 across one human plus two unfurler fetches
- [X] T064 Update `CLAUDE.md`'s Recent Changes with a `041-share-short-links` entry recording the load-bearing facts: the slug is the `_id`; the unique `(contentType, contentId)` index is what makes `ensureFor` correct under a race; `/s/` serves OG HTML to every client with **no** User-Agent branching, and is deliberately absent from `RateLimitInterceptor`; `og:image` must be absolute or crawlers drop it with no error anywhere; `short_links` is in both backup and restore lists and `RestoreService` calls `createIndexes` directly; and that `frontend/nginx.conf` is bind-mounted so the `/s/` route needs a post-deploy `curl` check. **Edit by hand — do NOT run `.specify/scripts/bash/update-agent-context.sh`**, which fails on this file with `grep: repetition-operator operand invalid` and strips the lead line from eight existing entries
- [ ] T065 After deploying, run `curl -i https://simonrowe.dev/s/<known-slug>` and confirm a 200 with OG tags rather than the SPA's HTML — `frontend/nginx.conf` is bind-mounted from the deploy directory, so a new frontend image alone does not apply the route
- [ ] T066 Paste one production link into Slack, LinkedIn, WhatsApp and iMessage and confirm each shows a title, description and image (SC-003 requires four platforms; each has its own image-dimension and caching quirks no local check substitutes for)

---

## Dependencies

```
Phase 1 Setup (T001–T004)
        │
Phase 2 Foundational (T005–T013)     ← BLOCKS everything below
        │
Phase 3 US4 (T014–T020)              ← sequenced first: no links, nothing to share
        │
        ├── Phase 4 US1 (T021–T039)  ← MVP
        │        │
        │        └── Phase 5 US2 (T040–T050)   depends on ShareButton (T035) only
        │
        └── Phase 6 US3 (T051–T059)  ← independent of US1/US2; needs only Phase 3
                 │
Phase 7 Polish (T060–T066)
```

US2 depends on US1 for exactly one artefact, `ShareButton` (T035). Everything else in US2
is independent. US3 depends only on links existing (Phase 3) and can be built in parallel
with US1 and US2 by a second person.

## Parallel execution examples

**Phase 2** — T005, T006 and T008 are three different files with no dependency between
them; T008 (the slugger test) is written before T009 (the slugger).

**Phase 4** — T021, T022, T024, T025 (four test files), then T026, T031, T034, T036, T038,
T039 (six independent files) can all run concurrently once T029 and T035 land.

**Phase 5 / Phase 6** — T041, T044, T045 and T052, T055, T057, T058 touch disjoint files
and can be done together.

## Implementation strategy

**MVP** = Phase 1 + Phase 2 + Phase 3 (US4) + Phase 4 (US1). That is a readable, unfurling,
click-counted share link on every blog post — the whole feature in one journey, and the
first thing a visitor can see.

**Incremental delivery**: each phase leaves the site working. Stopping after US1 ships blog
sharing with news untouched; stopping after US2 ships everything visitor-facing with the
counts visible only in Mongo.

**Highest-risk tasks**, in order: T029 (the controller — three OG rules, three content
types, the 404-not-redirect rule and the counter, all in one place), T049 (the
not-in-page deep link, the failure mode the design flags as most likely to be missed), and
T065 (the bind-mounted nginx route, which fails silently in production and only in
production).
