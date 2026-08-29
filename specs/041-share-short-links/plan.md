# Implementation Plan: Share links for blogs and news/events

**Branch**: `041-share-short-links` | **Date**: 2026-08-28 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/041-share-short-links/spec.md`

**Design of record**: `docs/superpowers/specs/2026-08-28-share-short-links-design.md`

## Summary

Add a Share control to blog posts, blog listing cards, and news/event cards, handing out a
short first-party URL (`https://simonrowe.dev/s/exactly-once`) that redirects to the
content and counts human clicks. `GET /s/{slug}` serves Open Graph HTML to **every**
client — no User-Agent guessing on the correctness-critical path — so a pasted link
unfurls in Slack, LinkedIn, WhatsApp and iMessage. Slugs live in one new collection whose
`_id` **is** the slug, are minted eagerly (blog save, article/event ingest, plus a `V029`
backfill), and reach the frontend as a nullable absolute `shortUrl` on the four existing
public response DTOs via one batched lookup per listing. Clicks are a fire-and-forget
`$inc`, suppressed for known link-preview agents. News and events gain deep-linking
(`?article=` / `?event=`), including a targeted fetch for an id that has fallen off page
one — the failure mode most likely to be missed.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.x / React 19 (frontend)

**Primary Dependencies**: Spring Boot 3.5.16 (`spring-boot-starter-web`, OAuth2 resource
server, `spring-boot-starter-data-mongodb`), Mongock, `java.text.Normalizer` and
`java.security.SecureRandom` (JDK), React Router v7 `useSearchParams`, Lucide React
`Share2`/`Check`. **No new dependency in either module.**

**Storage**: MongoDB — one new collection `short_links`, slug as `_id`, plus a unique
compound index on `(contentType, contentId)`. Indexes created by Mongock change unit
`V029`, never by annotations (`auto-index-creation` is off).

**Testing**: JUnit 5 + Mockito + Testcontainers (`AbstractIntegrationTest`,
`SharedMongoContainer`) for the backend; MockMvc for the controller; Vitest + Testing
Library for the frontend.

**Target Platform**: Linux container (GraalVM native image via `bootBuildImage`) behind
frontend nginx behind the prod nginx proxy behind the pinggy tunnel.

**Project Type**: Web application — `backend/` (Spring Boot) + `frontend/` (React + Vite).

**Performance Goals**: One additional Mongo query per listing regardless of item count
(SC-008). `/s/{slug}` is a primary-key lookup plus a string build — no LLM call, no
external I/O, no rate-limit bucket.

**Constraints**:
- `og:image` must be **absolute** — crawlers drop a relative one silently, which presents
  as "the feature doesn't work" with no error anywhere.
- The click increment must never be able to fail the redirect.
- `frontend/nginx.conf` is bind-mounted from the deploy directory in production, so the
  new `location /s/` does not ship with the image.
- Reachability of `/s/**` without a session must be asserted by a test, since it rests on
  `.anyRequest().permitAll()` rather than an explicit matcher.

**Scale/Scope**: ~43 blog posts, a few hundred aggregated articles, tens of events — so a
few hundred short links total. Two new endpoints, one new collection, one change unit, one
new frontend component, one new admin page.

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 design — see below.*

| Principle | Verdict | Note |
|---|---|---|
| I. Monorepo, separate containers | PASS | Backend and frontend changes stay in their own modules. One `frontend/nginx.conf` addition — the constitution already requires that file to proxy backend paths, and `/s/` is a third alongside `/api/` and `/uploads/`. |
| II. Modern Java & React stack | PASS | Java 21, Spring Boot 3.5.x, MongoDB primary store, React latest, Lucide React icons, plain CSS + BEM in the single `styles.css`. No new dependency. No `ProcessBuilder`. Routing convention respected — `/blogs/{id}` unchanged; `/s/` is a new backend-owned path, not a frontend route. |
| III. Quality gates | PASS | Google Java Style / Checkstyle; unit tests for the pure logic, Testcontainer-backed integration tests for the controller and change unit, Vitest for the frontend. JaCoCo 0.78 floor and the frontend coverage floors both apply. |
| IV. Observability | PASS | Structured logging on mint failures and on the counter's swallowed error — a silently-dropped `$inc` with no log line would make an under-counting bug undiagnosable. |
| V. Simplicity & incremental delivery | PASS | A counter, not an event stream — the design explicitly rejected `short_link_clicks`. HTML built by string concatenation rather than adding a view technology for one ~1KB document. Four user stories, each independently shippable. |
| VI. Admin CMS UX | PASS | New admin page follows the existing `admin-table` markup and Lucide icon conventions; a nav entry is added to `AdminLayout`'s `navItems`. |
| VII. Interactive tour | N/A | No tour step added. |
| VIII. Backup & restore | PASS — and load-bearing | `short_links` added to `BackupService.BACKUP_COLLECTIONS` and `RestoreService.IMPORT_ORDER_INDEPENDENT`, with `createIndexes` called after import. |
| IX. Shell scripting standards | N/A | No new script. |

**Post-Phase-1 re-check**: no violations introduced. The Complexity Tracking table is
empty and stays empty.

Two constitution points worth stating explicitly because a naive implementation would
break them:

- **Testcontainers, not mocked infrastructure**, for the change unit and the controller.
  `ShortLinkSluggerTest` and `UnfurlerDetectorTest` are pure unit tests only because their
  subjects touch no infrastructure at all.
- **Indexes via Mongock.** `@CompoundIndex` on the entity would be decorative and the
  uniqueness guarantee — which is what makes `ensureFor` correct under a race — would
  silently not exist.

## Project Structure

### Documentation (this feature)

```text
specs/041-share-short-links/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 — 12 decisions resolved against the codebase
├── data-model.md        # Phase 1 — the short_links collection
├── quickstart.md        # Phase 1 — how to run and verify it
├── contracts/
│   └── short-links.yaml # Phase 1 — OpenAPI for /s/{slug} and the admin endpoint
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
backend/src/main/java/com/simonrowe/
├── shortlink/                              # NEW package
│   ├── ShortLink.java                      # @Document record, slug as @Id
│   ├── ShortLinkContentType.java           # BLOG | ARTICLE | EVENT + destination path
│   ├── ShortLinkRepository.java            # MongoRepository + findByContentTypeAndContentIdIn
│   ├── ShortLinkSlugger.java               # pure: normalise, word-truncate, suffix, random
│   ├── ShortLinkService.java               # ensureFor, urlsFor (batched), recordClick
│   ├── ShortLinkProperties.java            # @ConfigurationProperties("site") — base-url
│   ├── ShareDocumentRenderer.java          # OG HTML + themed 404, with escapeHtml
│   ├── ShortLinkController.java            # GET /s/{slug}  (@Controller, text/html)
│   ├── UnfurlerDetector.java               # User-Agent match
│   ├── AdminShortLinkController.java       # GET /api/admin/short-links
│   └── AdminShortLinkResponse.java
├── migration/changeunits/
│   └── V029CreateShortLinksAndBackfill.java   # indexes + backfill over three collections
├── blog/                                   # MODIFIED
│   ├── BlogSummaryResponse.java            # + shortUrl (second overload)
│   ├── BlogDetailResponse.java             # + shortUrl (second overload)
│   └── BlogService.java                    # batched lookup
├── aggregation/                            # MODIFIED
│   ├── ArticleResponse.java                # + shortUrl (second overload)
│   ├── EventResponse.java                  # + shortUrl (second overload)
│   ├── NewsController.java                 # batched lookup on list, single on getById
│   └── EventsController.java               # batched lookup on list, single on getById
├── admin/AdminBlogController.java          # MODIFIED — mint on create/update; clickCount in DTO
├── agents/ContentAggregationAgent.java     # MODIFIED — mint on article/event ingest
├── dataops/BackupService.java              # MODIFIED — + "short_links"
├── dataops/RestoreService.java             # MODIFIED — + "short_links" + createIndexes
└── auth/SecurityConfig.java                # UNCHANGED (documented why)

backend/src/main/resources/application.yml  # MODIFIED — site.base-url

backend/src/test/java/com/simonrowe/
├── shortlink/ShortLinkSluggerTest.java
├── shortlink/ShortLinkServiceTest.java             # Testcontainers
├── shortlink/ShortLinkControllerTest.java          # MockMvc, asserts on the HTML
├── shortlink/UnfurlerDetectorTest.java             # table-driven, real UA strings
├── shortlink/ShareDocumentRendererTest.java        # escaping
├── shortlink/AdminShortLinkControllerTest.java
├── migration/changeunits/V029CreateShortLinksAndBackfillTest.java
├── auth/SecurityConfigTest.java                    # MODIFIED — /s/** is public
└── dataops/                                        # MODIFIED — collection-list assertions

frontend/
├── src/components/common/ShareButton.tsx           # NEW
├── src/components/blog/ArticleCard.tsx             # MODIFIED
├── src/components/blog/BlogDetail.tsx              # MODIFIED
├── src/components/blog/FeaturedArticle.tsx         # MODIFIED
├── src/pages/NewsEventsPage.tsx                    # MODIFIED — Share + deep links + card ids
├── src/pages/admin/ShortLinksAdmin.tsx             # NEW
├── src/pages/admin/BlogsAdmin.tsx                  # MODIFIED — clickCount column
├── src/components/admin/AdminLayout.tsx            # MODIFIED — nav entry
├── src/App.tsx                                     # MODIFIED — /admin/short-links route
├── src/services/adminApi.ts                        # MODIFIED — fetchShortLinks, clickCount
├── src/services/newsApi.ts                         # MODIFIED — fetchArticleById
├── src/services/eventsApi.ts                       # MODIFIED — fetchEventById
├── src/types/blog.ts | news.ts | events.ts         # MODIFIED — shortUrl?: string | null
├── src/styles.css                                  # MODIFIED — .share-button (BEM)
├── public/images/share-card.png                    # NEW — OG fallback
├── nginx.conf                                      # MODIFIED — location /s/
├── vite.config.ts                                  # MODIFIED — /s dev proxy
└── tests/
    ├── components/ShareButton.test.tsx             # NEW
    └── pages/NewsEventsPage.deeplink.test.tsx      # NEW
```

**Structure Decision**: the repo's established web-application layout — a Spring Boot
`backend/` and a React `frontend/`, each with its own test tree. The backend gains one new
package, `com.simonrowe.shortlink`, sized like `com.simonrowe.summary`: it owns an entity,
a repository, a service, two controllers and its pure helpers, and other packages depend on
it only through `ShortLinkService`. The frontend follows the existing split —
`components/common/` for the reusable control (beside `FavouriteButton`, whose shape it
copies), `pages/admin/` for the new table, `services/` for the API calls.

## Phase 0 — Research

Complete. See [research.md](./research.md). Twelve items resolved, all against the actual
codebase rather than from the design in the abstract. The five that changed the plan:

- **R1** — the OG fallback image goes in `frontend/public/images/`, not the backend's
  classpath static dir, because production serves `/images/**` from the frontend bundle
  while local dev proxies it to the backend. The emitted URL is always absolute against
  `site.base-url`, so production is the only environment that matters.
- **R4** — `ArticleResponse.from` / `EventResponse.from` are static one-arg factories with
  six callers. They get a second overload rather than a changed signature, following the
  existing `BlogSummaryResponse.fromEntity` precedent. This is also what makes `shortUrl`
  legitimately nullable on the admin and favourites paths.
- **R5** — `/s/**` must **not** be added to `RateLimitInterceptor`'s allowlist; and
  `SecurityConfig`'s global cache-control disable is correct here rather than something to
  work around.
- **R8** — the design's "cut to 17 characters plus `-2`, `-3`" breaks past attempt 9.
  Implemented as "reserve `("-" + n).length()`", and collision detection is an
  insert-and-catch on the unique `_id`, never a read-then-write.
- **R9** — `NewsEventsPage`'s drawer is already id-driven, so `?article=` needs no new
  drawer state; the real work is the third source array for an article that is not in the
  loaded page. Events have no drawer at all, so `?event=` means scroll-and-highlight.

## Phase 1 — Design & Contracts

Complete.

- [data-model.md](./data-model.md) — the `short_links` collection, why the slug is the
  `_id`, why `ShortLinkContentType` is a new enum rather than a reuse of the two existing
  content-type enums, and the backup/restore obligations.
- [contracts/short-links.yaml](./contracts/short-links.yaml) — `GET /s/{slug}` (200 HTML
  to every client, 404 themed page, never a 3xx) and `GET /api/admin/short-links`, plus
  the `shortUrl` field added to the four public DTOs.
- [quickstart.md](./quickstart.md) — running it locally, and the `curl` checks that prove
  the unfurl works without pasting into a real chat client.

**Agent context update deliberately skipped.** `CLAUDE.md` records that
`.specify/scripts/bash/update-agent-context.sh` fails on this repository with
`grep: repetition-operator operand invalid` and silently strips the lead line from eight
existing entries. `CLAUDE.md` will be updated by hand at the end of implementation instead.

## Implementation order

Four increments, matching the spec's user stories. Each leaves the site working.

1. **US4 first (minting + backfill).** Nothing else can be demonstrated until links exist.
   Ships `ShortLink`, `ShortLinkSlugger`, `ShortLinkService`, `V029`, the three mint call
   sites, and backup/restore registration. Verifiable by inspecting the collection.
2. **US1 (blog share + unfurl).** `site.base-url`, `ShareDocumentRenderer`,
   `ShortLinkController`, `UnfurlerDetector`, the nginx and vite routes, `shortUrl` on the
   two blog DTOs, and `ShareButton` on the blog detail page and listing cards.
   `curl`-verifiable end to end.
3. **US2 (news/event share + deep links).** `shortUrl` on `ArticleResponse` /
   `EventResponse`, `ShareButton` on news and event cards, `?article=` / `?event=`
   handling including the targeted fetch, and card `id` attributes.
4. **US3 (visibility).** `GET /api/admin/short-links`, the `ShortLinksAdmin` page and nav
   entry, and the `clickCount` column in `BlogsAdmin`.

## Risks

| Risk | Mitigation |
|---|---|
| The nginx route does not reach production, because `frontend/nginx.conf` is bind-mounted | Post-deploy `curl -i https://simonrowe.dev/s/<known-slug>` is a required step, not an optional one. Recorded in quickstart.md. |
| `og:image` emitted relative — crawlers drop it silently, no error anywhere | `ShortLinkControllerTest` asserts the `og:image` value starts with `http`, for all three resolution rules including the fallback. |
| Slug collision handling wrong past attempt 9 | `ShortLinkSluggerTest` covers `-2`, `-10` and `-99` and asserts the 20-character ceiling holds at each. |
| Mint failure fails a blog save | `ensureFor` is called inside a try/catch that logs and continues. A post with no link renders with no Share control — the nullable-`shortUrl` path, which has its own test. |
| Backfill re-runs and mints duplicates | The unique compound index makes it structurally impossible; `V029...Test` asserts a second run mints nothing new. |
| Restore drops the indexes and Mongock will not re-run | `RestoreService` calls `createIndexes` directly, as it already does for narrations and article summaries. |
| Four controls wrap badly on a mobile news card | Checked at mobile width during US2; the fix is icon-only, never removal. |

## Complexity Tracking

No constitution violations. Table intentionally empty.
