# Implementation Plan: Listen from the listing

**Branch**: `simonrowe/listen-from-listing` (feature dir `035-listen-from-listing`, pinned in `.specify/feature.json`) | **Date**: 2026-08-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/035-listen-from-listing/spec.md`, and the settled design at
`docs/superpowers/specs/2026-08-26-listen-from-listing-design.md`

## Summary

Put a listen control on every blog and news-article card, and move listing-initiated playback into
one persistent mini-player docked above `<Routes>` so it survives navigation, filtering and
"Load more".

Three pieces:

1. **One new public backend endpoint** — `GET /api/narrations/ready?contentType=BLOG|ARTICLE_SUMMARY`
   returning `[{contentId, audioUrl, durationSeconds}]`, one row per content id (the newest `READY`
   narration), via a Mongo aggregation ordered by the existing
   `idx_narration_content_updated` index. This is not an optimisation: `/api/blogs/*/narration` is
   rate-limited 10/min per IP on `GET` as well as `POST`, so per-card status polling would 429 on
   first render. The new path deliberately does not match the interceptor's
   `/api/blogs/{id}/narration` pattern, so a page load spends nothing from the narration bucket.
2. **One backend authorisation tightening** — `POST /api/blogs/{blogId}/narration` becomes
   `.authenticated()`, matching summary narration, because both spend the same monthly TTS
   character budget. Three documentation artefacts currently record the asymmetry as deliberate
   and must be rewritten, and `SecurityConfigTest.blogNarrationPostRemainsPublic` currently asserts
   it and must be inverted.
3. **One new frontend module** — `NarrationAudioProvider` (state + a detached `new Audio()` in a
   ref, mounted above `<Routes>` and inside `AuthProvider`), `ListenButton` (a view over provider
   state, keyed on `contentId`) and `NarrationPlayerBar` (rendered inside `PublicLayout` so it never
   appears under `/admin`). `useNarration`, `NarrationPanel`, `BlogNarration` and `SummaryNarration`
   keep their current structure.

`PublicLayout` wraps each route individually (`App.tsx:155-166`), so anything inside it remounts on
navigation. That is why the provider goes above `<Routes>` and why the audio element must be a
detached `new Audio()` rather than JSX `<audio>`: a JSX element inside the remounting tree dies on
the first route change, which is precisely the thing this feature exists to prevent.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.x / React 19 (frontend)

**Primary Dependencies**: Spring Boot 3.5.16 (web, security OAuth2 resource server,
data-mongodb), Spring Data MongoDB `MongoTemplate` aggregation, Lucide React
(`Play`/`Pause`/`Headphones`/`Loader2`/`X`), `@auth0/auth0-react` via the existing
`useAuth`/`useEnsureAuthenticated`. **No new dependencies in either module.**

**Storage**: MongoDB, read-only for the new endpoint. No new collection, no new field, **no new
index and therefore no Mongock change unit** — `idx_narration_content_updated`
(`{contentType: 1, contentId: 1, updatedAt: -1}`) already exists on `Narration`
(`Narration.java:22-27`) and already orders the aggregation. Nothing else about the `narrations`
document changes.

**Testing**: JUnit 5 + MockMvc standalone (controller), `AbstractIntegrationTest` +
`SharedMongoContainer` (security + aggregation), Checkstyle (Google Java Style), JaCoCo ≥0.78 on
`backend`; Vitest + Testing Library (frontend), ESLint 9.

**Target Platform**: Web — Spring Boot backend on the prod Pi, Vite/React frontend behind nginx.

**Project Type**: Web application (existing `backend/` + `frontend/` monorepo modules).

**Performance Goals**: One request per listing page load to learn what is listenable, regardless of
card count (SC-005). Playback start from a ready card with zero network round trips (SC-001).
`GET /api/narrations/ready` is a single indexed aggregation over a collection with tens of rows
today; no pagination.

**Constraints**:
- Loading a listing page must leave the narration rate-limit bucket untouched (10/min per IP on
  `/api/blogs/*/narration`; 5/min per IP on the summary bucket).
- `FORMAT_VERSION` in `NarrationScriptBuilder` stays the literal `blog-narration-v1` — it feeds
  the fingerprint that *is* the narration `_id`. This feature must not touch it.
- The narration `contentId` for `ARTICLE_SUMMARY` **is the aggregated article id**
  (`ArticleSummaryNarrationSource.java` states this explicitly), so the news page keys straight
  off ids it already holds — no join, no summary-id lookup.
- Plain CSS + BEM in the single `frontend/src/styles.css`; no CSS framework, no CSS-in-JS.
- Frontend must stay lint-clean (`npm run lint` is a blocking CI step).

**Scale/Scope**: 2 new backend files + 3 modified + 3 doc/test updates; 4 new frontend source files
+ 6 modified + CSS; ~9 new test files.

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 — see the bottom of this section.*

| Principle | Verdict | Notes |
|---|---|---|
| I. Monorepo with separate containers | ✅ | No build, image or compose change. |
| II. Modern Java & React stack | ✅ | Java 21 / Spring Boot 3.5.x / MongoDB / React latest; Lucide React for icons; plain CSS + BEM in the single `styles.css`; no new dependency in either module. Auth0 remains the sole auth provider — the tightening reuses the existing OAuth2 resource server and `useEnsureAuthenticated`. |
| III. Quality gates (non-negotiable) | ✅ | Google Java Style via Checkstyle; JaCoCo ≥0.78 held by the new controller/aggregation/security tests; SonarQube advisory job unaffected (no new `sonar.coverage.exclusions` entry needed — nothing here is generated or excluded); no new CDX surface. Testcontainers used for the security and aggregation integration tests via `AbstractIntegrationTest`; the pure-delegation controller test uses MockMvc standalone, matching `SummaryNarrationControllerTest`. Frontend tests cover the critical journeys. |
| IV. Observability & operability | ✅ | No new metric or trace is warranted: the new endpoint is a read served by an existing indexed query, and `NarrationService` already meters generation. Failures surface to the reader in the bar. |
| V. Simplicity & incremental delivery | ✅ | The three user stories are independently shippable in priority order (Story 1 needs no auth and no chain). No new polling policy — the chain reuses `useNarration`'s `LONG_POLL_SECONDS`/`MAX_LONG_POLLS`. No new persistence. Ready-state and summarised-state each keep their single existing owner and are *published to*, not duplicated. |
| VI. Admin CMS UX standards | ➖ | Not applicable — nothing admin-facing changes. FR-019 explicitly keeps the bar out of `/admin`. |
| VII. Interactive site tour | ➖ | Not applicable. No tour step targets the new controls. |
| VIII. Backup & restore | ➖ | Not applicable — no new collection, so no `BackupService.BACKUP_COLLECTIONS` or `RestoreService.IMPORT_ORDER_INDEPENDENT` entry. |
| IX. Shell scripting standards | ➖ | No script changes. |

**Complexity Tracking**: not required — no violations.

**Post-Phase-1 re-check**: unchanged. The Phase 1 design added no dependency, no collection, no
index, no Mongock change unit and no build change; the one deliberate deviation from a *documented
decision* (the blog narration POST asymmetry) is a documentation correction the spec requires
(FR-036) rather than a constitution violation.

## Project Structure

### Documentation (this feature)

```text
specs/035-listen-from-listing/
├── spec.md              # Phase -1 (/speckit.specify)
├── plan.md              # This file
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   └── narrations-ready.yaml   # Phase 1 — OpenAPI for the one new endpoint
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/simonrowe/
│   ├── narration/
│   │   ├── NarrationReadyController.java        # NEW  GET /api/narrations/ready
│   │   ├── ReadyNarration.java                  # NEW  record(contentId, audioUrl, durationSeconds)
│   │   ├── NarrationService.java                # MOD  readyNarrations(contentType) aggregation
│   │   └── BlogNarrationController.java         # MOD  javadoc: POST is now authenticated
│   └── auth/SecurityConfig.java                 # MOD  POST /api/blogs/*/narration authenticated
└── src/test/java/com/simonrowe/
    ├── narration/
    │   ├── NarrationReadyControllerTest.java    # NEW  MockMvc standalone, both content types
    │   └── NarrationReadyAggregationTest.java   # NEW  AbstractIntegrationTest, newest-per-id
    └── auth/SecurityConfigTest.java             # MOD  invert blogNarrationPostRemainsPublic

frontend/
├── src/
│   ├── components/narration/
│   │   ├── NarrationAudioProvider.tsx           # NEW  provider + detached Audio + listen() chain
│   │   ├── useNarrationAudio.ts                 # NEW  context hook (separate file: fast-refresh)
│   │   ├── ListenButton.tsx                     # NEW  per-card control, 3 states
│   │   ├── NarrationPlayerBar.tsx               # NEW  the docked player
│   │   ├── formatDuration.ts                    # NEW  extracted formatApproximateDuration
│   │   ├── NarrationPanel.tsx                   # MOD  import the extracted formatter
│   │   └── useNarration.ts                      # MOD  export LONG_POLL_SECONDS/MAX_LONG_POLLS
│   ├── components/blog/
│   │   ├── ArticleCard.tsx                      # MOD  new actions row + ListenButton
│   │   ├── FeaturedArticle.tsx                  # MOD  new actions row + ListenButton
│   │   └── BlogNarration.tsx                    # MOD  useEnsureAuthenticated before POST
│   ├── pages/NewsEventsPage.tsx                 # MOD  ListenButton in feed__card-actions (x2),
│   │                                            #      noteSummarised wiring
│   ├── hooks/useArticleSummaries.ts             # MOD  noteSummarised(articleId)
│   ├── services/narrationApi.ts                 # NEW  fetchReadyNarrations()
│   ├── services/blogApi.ts                      # MOD  requestBlogNarration takes a token
│   ├── App.tsx                                  # MOD  provider above <Routes>; bar in PublicLayout
│   └── styles.css                               # MOD  .listen-button, .narration-bar BEM blocks
└── tests/
    ├── components/narration/
    │   ├── NarrationAudioProvider.test.tsx      # NEW  every chain branch + abort + 429 + dismiss
    │   ├── ListenButton.test.tsx                # NEW  ready / cold / in-flight
    │   ├── NarrationPlayerBar.test.tsx          # NEW  ready + in-flight + a11y + dismiss
    │   └── formatDuration.test.ts               # NEW
    ├── hooks/useArticleSummaries.test.ts        # MOD or NEW  noteSummarised
    ├── pages/NewsEventsPage.test.tsx            # MOD  duration vs cold; Listen-chain summary flip
    ├── pages/BlogListingPage.test.tsx           # MOD  cards render the control
    └── services/narrationApi.test.ts            # NEW

docs/
├── runbooks/article-summaries.md                # MOD  the "still public" paragraph
└── (CLAUDE.md at repo root)                     # MOD  the public-POST note
```

**Structure Decision**: Option 2 (web application) — the repo's existing `backend/` + `frontend/`
split. Everything new lands in the existing `com.simonrowe.narration` package and the existing
`frontend/src/components/narration/` directory; frontend tests go under `frontend/tests/` mirroring
the source tree, which is where the bulk of the suite lives (58 tests there vs 9 co-located).

## Phase 0: Research

See [research.md](./research.md). Ten decisions were resolved against the current code; no
`NEEDS CLARIFICATION` remains. The load-bearing ones:

- **R1** — the bulk endpoint is a necessity, not an optimisation: `RateLimitInterceptor`'s
  POST-only exemption exists only in the summary branch, so per-card `GET` polling 429s.
- **R2** — a `MongoTemplate` aggregation (`match` → `sort` → `group first`), not
  `findByContentTypeAndContentIdIn`, because the newest-per-`contentId` reduction must happen
  server-side.
- **R4** — the provider must hold a detached `new Audio()`, because `PublicLayout` remounts.
- **R7** — `requestBlogNarration` gains a token parameter, so `BlogNarration.test.tsx` needs the
  transport mock updated; its other seven assertions stay untouched.
- **R9** — three docs plus one inverted test are part of the auth change, not follow-up work.

## Phase 1: Design & Contracts

- [data-model.md](./data-model.md) — the read projection, the provider's state shape, and the
  explicit "no schema change" statement.
- [contracts/narrations-ready.yaml](./contracts/narrations-ready.yaml) — OpenAPI for
  `GET /api/narrations/ready`, plus the changed security requirement on
  `POST /api/blogs/{blogId}/narration`.
- [quickstart.md](./quickstart.md) — how to run, seed and verify the feature locally.

### Agent context

`.specify/scripts/bash/update-agent-context.sh claude` is run at the end of Phase 1 to add the
feature's active technologies to `CLAUDE.md` between the managed markers. The two *manual* CLAUDE.md
edits this feature requires (the public-POST note) are separate tasks in Phase 2, not something the
script does.
