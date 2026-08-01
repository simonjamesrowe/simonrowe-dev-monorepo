# Implementation Plan: UX Top-10 Improvements

**Branch**: `simonrowe/ux-review-simonrowe-dev` (feature dir `031-ux-top10`) | **Date**: 2026-07-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/031-ux-top10/spec.md`

**Source design**: `docs/superpowers/specs/2026-07-30-ux-top10-design.md` — the design
document carries the file-level decisions; this plan reconciles them with the
actual current state of the code (see [research.md](./research.md)).

## Summary

Ten independent UX fixes to simonrowe.dev, shipped as one PR spanning the React
frontend, the Spring Boot backend, and four new Mongock change units. The work
splits into three kinds:

1. **Frontend-only** (7 of 10): route redirects + a 404 page, a shared
   `usePageTitle` hook, four new home-page sections below the hero, a site-wide
   footer, a shared `fetchWithRetry` + a `title`-aware `ErrorMessage`, mobile
   hero content, skill level words, primary-button and copy fixes, and an
   auth-gated admin nav link.
2. **Backend + frontend** (2 of 10): a `contentType` enum on `Blog` exposed
   through the public API and admin editor, driving blog tabs and featured-post
   selection; and a `GET /api/news/sources` endpoint driving backend-paged news
   with real source chips.
3. **Data, via Mongock** (3 change units): backfill `contentType` from the
   existing "Weekly Digest" tag; install bundled SVG icon/logo assets into the
   uploads volume and repoint `skill_groups.image` and job company logos; and
   name the two GitHub social links distinctly.

No new dependencies in either module. All ten items are independently testable
and independently revertable.

## Technical Context

**Language/Version**: Java 21 (backend, GraalVM native image target);
TypeScript 5.7 / React 19 (frontend)

**Primary Dependencies**: Spring Boot 3.5.16, Spring Data MongoDB, Mongock
(`mongock-springboot-v3` + `mongock-mongodb-springdata-v4`), Spring Security
OAuth2 resource server; React Router 7, Vite 6, Lucide React, React Markdown.
**No new dependencies are introduced by this feature.**

**Storage**: MongoDB 8 (`blogs`, `tags`, `skill_groups`, `jobs`, `profile`,
`media_assets`, `aggregated_articles`, `aggregated_events`); uploads volume on
disk served at `/uploads/**`; Elasticsearch for search (untouched here beyond
re-indexing implications of the new `Blog` field)

**Testing**: Vitest + Testing Library + jsdom (frontend unit); Playwright
(`frontend/e2e`, `local` project against a running stack); JUnit 5 +
Testcontainers via `AbstractIntegrationTest` / `SharedMongoContainer` (backend);
Checkstyle (Google style) + JaCoCo thresholds gate the backend build

**Target Platform**: Containerised web app — backend and frontend as separate
containers behind nginx, running on an ARM64 Raspberry Pi in production

**Project Type**: Web application (monorepo: `backend/` + `frontend/`)

**Performance Goals**: News & Events first paint must not wait on a 100-article
payload — first request drops to 24 articles. No regression to the current
205 KB-wire main bundle: the 404 page is lazy-loaded, and the new home sections
reuse existing components and already-fetched data wherever possible.

**Constraints**:

- Plain CSS with BEM in the single `frontend/src/styles.css` (9,141 lines) — no
  CSS framework, no CSS-in-JS, no new stylesheet file.
- Lucide React is the only permitted icon *library*; the skill/employer marks in
  this feature are backend **media assets**, not a library, so they do not
  conflict.
- Data changes must be Mongock change units — no ad-hoc scripts, no manual
  admin-CMS edits (project non-negotiable).
- Backend integration tests must extend `AbstractIntegrationTest`; change units
  must be disabled in the shared context and tested with the isolated-boot
  pattern, or they pollute the shared Testcontainer.
- GraalVM native image: no runtime classpath scanning tricks for the bundled SVG
  resources — they must be declared reachable.

**Scale/Scope**: ~43 blog posts (28 engineering / 15 digest), ~10 skill groups,
~8 employers, a few hundred aggregated articles. 7 public pages. Roughly 30
frontend files touched, 12 backend files touched, 3 new change units, 4 new SVG
resource sets.

## Constitution Check

*GATE: evaluated against `.specify/memory/constitution.md` v1.11.0.*

| Principle | Relevance | Verdict |
|---|---|---|
| I. Monorepo, separate containers | Frontend and backend both change; no runtime sharing introduced. nginx SPA fallback already serves unknown paths to React, which is what makes the client-side 404 route work. | **PASS** |
| II. Modern Java & React stack | No new dependencies. CSS stays plain BEM in the single `styles.css`. Icons: Lucide React remains the only icon *library*; the new marks are `media_assets` served via `/uploads/**` per the media-serving rule. Routing keeps the mandated `/blogs`, `/blogs/{slug}` conventions — `/blog*` becomes a redirect *into* them, not a new convention. | **PASS** |
| III. Quality gates (non-negotiable) | Checkstyle + JaCoCo + Sonar + CycloneDX unchanged. New backend tests use Testcontainers via `AbstractIntegrationTest`; change units are tested with the isolated-boot pattern. Frontend tests added for every story. | **PASS** |
| IV. Observability & operability | No telemetry surface changes. | **PASS (n/a)** |
| V. Simplicity & incremental delivery | `fetchWithRetry` replaces three byte-identical `handleResponse` helpers and two `parseErrorMessage` copies — it removes duplication rather than adding abstraction. Ten items remain independently deliverable. The one judgement call is `contentType` as an explicit field rather than deriving from tags at read time — justified below. | **PASS** |
| VI. Admin CMS UX standards | The blog editor gains one `<select>` in the existing two-column top section; the established DTO pattern (`@DBRef` ↔ string IDs) is preserved. | **PASS** |
| VII. Interactive site tour | Incidental fix: `CTASection` carries the `tour-contact` anchor class and is currently unrendered, so the tour step targeting `.tour-contact` has no target on the home page. Reviving it for the contact CTA band restores that step. No tour data changes. | **PASS** |
| VIII. Backup & restore | Untouched. The asset change unit writes into the uploads volume that `backup.sh` already captures. | **PASS** |
| IX. Shell scripting standards | No new scripts. | **PASS (n/a)** |

**No violations. Complexity Tracking table omitted.**

Two decisions worth recording explicitly, both consistent with Principle V:

- **`contentType` as a stored enum, not a derived value.** Deriving "is this a
  digest?" from the presence of a `@DBRef` tag at read time would mean loading
  tags on every list query and would leave the classification implicit and
  un-overridable. One indexed enum field is simpler at every read site, lets the
  digest generator declare intent at creation, and lets an author reclassify a
  post. The tag remains for display.
- **Reviving vs deleting the orphaned home components.** `CTASection` and
  `ConnectStrip` are reused (CTA band and footer social/CV block respectively);
  `StatsGrid` and `AIChatModule` are deleted along with their dead CSS. That is
  the YAGNI-consistent split: keep what the new sections actually need, delete
  the rest rather than finding a home for it.

## Project Structure

### Documentation (this feature)

```text
specs/031-ux-top10/
├── plan.md              # This file
├── research.md          # Phase 0: current-state findings + decisions
├── data-model.md        # Phase 1: Blog.contentType, MediaAsset, SocialLink, migrations
├── quickstart.md        # Phase 1: how to run, verify and demo the change
├── contracts/
│   ├── blogs-api.md     # GET /api/blogs, /api/blogs/latest (contentType)
│   ├── news-api.md      # GET /api/news/sources
│   └── admin-blogs-api.md  # admin blog DTO contentType field
├── checklists/
│   └── requirements.md  # Spec quality checklist (complete)
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
backend/src/main/java/com/simonrowe/
├── blog/
│   ├── BlogContentType.java            # NEW enum: ENGINEERING | DIGEST
│   ├── Blog.java                       # record + contentType (public/read model)
│   ├── BlogController.java             # /api/blogs/latest gains ?contentType=
│   ├── BlogService.java                # getLatest(limit, contentType)
│   ├── BlogSummaryResponse.java        # + contentType
│   └── BlogDetailResponse.java         # + contentType
├── admin/
│   ├── Blog.java                       # record + contentType (write model, different arg order)
│   └── AdminBlogController.java        # toDto()/validate()/write path, defaults ENGINEERING
├── aggregation/
│   └── NewsController.java             # NEW GET /api/news/sources
├── agents/
│   └── WeeklyDigestAgent.java          # sets contentType = DIGEST at creation (L108-113)
└── migration/changeunits/
    ├── V015BackfillBlogContentType.java     # NEW
    ├── V016InstallSkillAndCompanyIcons.java # NEW (assets → uploads + media_assets + repoint)
    └── V017NameGithubSocialLinks.java       # NEW (social_medias.name)

backend/src/main/resources/
└── media/icons/                        # NEW bundled SVG assets (skill groups + employers)

backend/src/test/java/com/simonrowe/
├── blog/            # BlogControllerTest: contentType in payloads, latest filter
├── aggregation/     # NewsControllerTest: /api/news/sources
├── admin/           # AdminBlogControllerTest: default + round-trip
└── migration/changeunits/  # V015/V016/V017 tests (real-Mongo direct-drive style)

frontend/src/
├── App.tsx                             # /blog redirects, catch-all 404, Footer in PublicLayout
├── index.html                          # default title
├── hooks/usePageTitle.ts               # NEW shared title hook
├── services/
│   ├── fetchWithRetry.ts               # NEW shared retrying fetch + error shaping
│   └── {blogApi,newsApi,eventsApi,jobsApi,skillsApi,profileApi,tourApi}.ts  # route through it
├── pages/
│   ├── NotFoundPage.tsx                # NEW (lazy)
│   ├── HomePage.tsx                    # + 4 sections below hero
│   ├── BlogListingPage.tsx             # tabs + contentType-based featured pick
│   └── NewsEventsPage.tsx              # backend paging + Load more + source chips
├── components/
│   ├── layout/Footer.tsx               # NEW, uses the orphaned .footer CSS
│   ├── home/
│   │   ├── HeroSection.tsx             # mobile badge/tagline/2 chips
│   │   ├── CurrentlyStrip.tsx          # NEW
│   │   ├── EmployerLogoStrip.tsx       # NEW
│   │   ├── FeaturedWriting.tsx         # NEW
│   │   ├── CTASection.tsx              # revived as the contact band
│   │   ├── StatsGrid.tsx               # DELETED
│   │   └── AIChatModule.tsx            # DELETED
│   ├── common/ErrorMessage.tsx         # + title prop
│   ├── blog/{ArticleCard,FeaturedArticle}.tsx  # "Read post"
│   ├── blog/BlogContentTabs.tsx        # NEW
│   ├── skills/SkillRatingBar.tsx       # + level word
│   ├── profile/SocialLinks.tsx         # prefer link.name
│   ├── contact/ContactForm.tsx         # "Send message"
│   └── layout/{TopNav,MobileMenu}.tsx  # auth-gate admin link
├── types/blog.ts                       # + contentType
└── styles.css                          # .button--primary solid; .footer refresh; new sections; delete dead blocks

frontend/tests/                         # vitest, mirrors src/
frontend/e2e/routing.local.spec.ts      # NEW: /blog redirect, 404, blog tab default
```

**Structure Decision**: The existing monorepo layout is used as-is — `backend/`
(Spring Boot, package-by-feature under `com.simonrowe`) and `frontend/`
(React, `pages/` + `components/<domain>/` + `services/` + `hooks/`). No new
top-level directories. Two structural notes specific to this feature:

- `PublicLayout` is currently defined inline in `App.tsx:111-132`, not as its own
  file. The `Footer` is added there rather than extracting the layout, keeping the
  diff proportionate.
- Bundled SVG assets live under `backend/src/main/resources/media/icons/` so the
  change unit can read them as classpath resources and copy them into the
  `UPLOADS_PATH` volume — matching the existing media-serving contract instead of
  inventing a second static-asset path.

## Post-Design Constitution Re-Check

Re-evaluated after Phase 1 (`data-model.md`, `contracts/`, `quickstart.md`).
Nothing in the design changed the verdicts above. Three points were *strengthened*
by the design work:

- **Principle II (media serving)** — `data-model.md` §2 confirms the bundled SVGs
  follow the existing `/uploads/{assetId}/original.{ext}` contract exactly, reusing
  `MediaAsset.legacyId` as the idempotency key the way `MediaSyncService` already
  does. No second static-asset path is introduced.
- **Principle III (Testcontainers, no mocked infrastructure)** — change-unit tests
  use the real-Mongo direct-drive style of `V014MakeFavouritesGlobalTest` rather
  than the more expensive isolated-boot context, because V015–V017 inject only
  `MongoTemplate`. Every one cleans up in `@AfterEach` so the shared container
  stays clean.
- **Principle VI (admin CMS)** — `contracts/admin-blogs-api.md` keeps the existing
  hand-built `LinkedHashMap` DTO shape and the `@DBRef` ↔ string-id pattern rather
  than introducing a record for a single field.

One design decision is worth flagging to a reviewer because it is a deliberate
asymmetry, not an oversight: on `POST` an absent `contentType` defaults to
`ENGINEERING`, but on `PUT` an absent `contentType` **preserves** the stored value.
Defaulting on update would let any admin edit silently reclassify a generated
digest as an engineering post.

**Verdict: PASS. No violations, no justifications required.**

## Complexity Tracking

> No constitution violations — table intentionally empty.

## Phase 0/1 Artifacts

| Artifact | Status |
|---|---|
| `research.md` | Complete — 11 corrections to the design doc, 15 decisions, risk register |
| `data-model.md` | Complete — 3 collections, additive only |
| `contracts/blogs-api.md` | Complete |
| `contracts/news-api.md` | Complete |
| `contracts/admin-blogs-api.md` | Complete |
| `contracts/asset-manifest.md` | Complete — **subject to the FR-032 human approval gate** |
| `quickstart.md` | Complete |
| Agent context (`CLAUDE.md`) | Deferred to end of implementation — `update-agent-context.sh` corrupted the manual-additions block and dropped prior entries when run against this repo, so the entry is added by hand once the work has shipped |

**No `NEEDS CLARIFICATION` markers remain.** The single open item is the human
approval gate on the icon/logo set (FR-032), which is a process gate, not an
unresolved design question.
