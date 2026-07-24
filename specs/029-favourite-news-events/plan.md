# Implementation Plan: Favourite News & Events

**Branch**: `029-favourite-news-events` | **Date**: 2026-07-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/029-favourite-news-events/spec.md` and approved design doc `docs/superpowers/specs/2026-07-23-favourite-news-events-design.md`

## Summary

Let the authenticated owner save news articles and events as favourites (heart icon on each card) and filter the feed to favourites only. Favourites are stored server-side in a new MongoDB `favourites` collection keyed to the Auth0 `sub`, exposed via four authenticated REST endpoints under `/api/favourites/{type}` (`news`|`events`). A logged-out click triggers `loginWithPopup` (no page navigation, reuses the registered `/admin` callback) and completes the pending action on success.

**Reality adjustment vs design doc**: the design doc names `NewsPage` / `EventsPage`, but the routed page is the combined `frontend/src/pages/NewsEventsPage.tsx` at `/news-events` (the standalone pages are unrouted legacy). The UI work lands there: hearts on `.feed__hero-card`, `.feed__card`, and `.feed__timeline-item` cards, plus a single "Show favourites only" toggle in the feed filter bar. Two `useFavourites` hook instances (one per type) live on that page.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.x / React 19 (frontend)

**Primary Dependencies**: Spring Boot 3.5.9 (web, security OAuth2 resource server, data-mongodb), `@auth0/auth0-react` (adds `loginWithPopup` usage), Lucide React `Heart` icon. No new dependencies.

**Storage**: MongoDB — new `favourites` collection (record + `@Document`, unique compound index on `userId,type,contentId`). Existing `aggregated_articles` / `aggregated_events` unchanged.

**Testing**: Backend — Testcontainers integration tests extending `AbstractIntegrationTest` (shared Mongo container, `jwt()` post-processor for auth). Frontend — Vitest (`frontend/tests/**`), fetch stubbed via `vi.stubGlobal`, Auth0 mocked via `vi.mock('@auth0/auth0-react')`.

**Target Platform**: Existing web stack (Docker Compose deployment); no infra changes.

**Project Type**: Web application (backend + frontend monorepo).

**Performance Goals**: Heart toggle reflects instantly (optimistic update); id-set fetch is one lightweight call per type on page load when authenticated.

**Constraints**: Checkstyle Google style (100-char lines, 2-space indent, `final` params/locals); JaCoCo ≥ 0.78 on new backend code; single `styles.css` with BEM; no new icon/CSS libraries.

**Scale/Scope**: Single-owner feature; favourites volume is tiny (tens of items). 4 REST endpoints, 1 collection, 1 hook, 1 component, edits to 1 page + `useAuth` + `SecurityConfig`.

## Constitution Check

*GATE: evaluated against constitution v1.11.0 — PASS (pre-design and post-design).*

| Principle | Check |
| --- | --- |
| I. Monorepo / containers | No container or compose changes. PASS |
| II. Stack | MongoDB for persistence; Auth0 sole auth (no new provider, no tenant change — popup reuses `/admin` callback); Lucide `Heart` icon; plain CSS BEM in `styles.css`. PASS |
| III. Quality gates | New `FavouritesController`/service covered by Testcontainers integration tests extending `AbstractIntegrationTest`; JwtDecoder already mocked there, auth supplied via spring-security-test `jwt()` post-processor; JaCoCo 0.78 applies (no exclusions added). Frontend Vitest coverage for service, hook, component. PASS |
| IV. Observability | Standard Spring MVC endpoints — existing structured logging/metrics apply. PASS |
| V. Simplicity | Persistence justified by concrete read requirement (favourites-only view, cross-device). No global state on frontend (per-page hook instances). No new abstractions beyond the `adminApi`-style service module. PASS |
| VI–IX | Not applicable (no admin CMS pages, tour, backup, or scripts touched). |

## Project Structure

### Documentation (this feature)

```text
specs/029-favourite-news-events/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── favourites-api.yaml
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/simonrowe/
│   ├── favourites/                    # NEW package
│   │   ├── Favourite.java             # @Document record, unique compound index
│   │   ├── FavouriteType.java         # enum NEWS | EVENT (+ path-segment mapping)
│   │   ├── FavouriteRepository.java   # MongoRepository
│   │   ├── FavouritesService.java     # add/remove/ids/paged listing
│   │   └── FavouritesController.java  # /api/favourites/{type}[...] endpoints
│   └── auth/SecurityConfig.java       # EDIT: .requestMatchers("/api/favourites/**").authenticated()
└── src/test/java/com/simonrowe/
    └── favourites/FavouritesControllerTest.java   # NEW integration test (AbstractIntegrationTest)

frontend/
├── src/
│   ├── services/favouritesApi.ts      # NEW: adminApi-pattern authFetch service
│   ├── hooks/useFavourites.ts         # NEW: id-set state, optimistic toggle, login gate
│   ├── components/common/FavouriteButton.tsx   # NEW: presentational Heart button
│   ├── pages/NewsEventsPage.tsx       # EDIT: hearts on cards + favourites-only toggle
│   ├── auth/useAuth.ts                # EDIT: expose loginWithPopup
│   ├── types/favourites.ts            # NEW: FavouriteContentType, re-used page types
│   └── styles.css                     # EDIT: .feed__favourite, .feed__favourites-toggle BEM blocks
└── tests/
    ├── services/favouritesApi.test.ts # NEW
    ├── hooks/useFavourites.test.ts    # NEW
    └── components/FavouriteButton.test.tsx  # NEW
```

**Structure Decision**: Follows the repo's flat feature-package convention (`com.simonrowe.favourites` holding document, repo, service, controller together — same as `aggregation`, `blog`). Frontend mirrors the existing `services`/`hooks`/`components/common` split; UI edits go to the routed `NewsEventsPage.tsx`, not the legacy unrouted `NewsPage`/`EventsPage`.

## Key Design Decisions (from research)

1. **Endpoints** (all `.authenticated()`, user = `jwt.getSubject()`):
   - `PUT /api/favourites/{type}/{id}` → 204; 404 if referenced content missing; idempotent (upsert semantics via unique index + duplicate-key tolerance).
   - `DELETE /api/favourites/{type}/{id}` → 204 always (idempotent).
   - `GET /api/favourites/{type}/ids` → `Set<String>` of contentIds.
   - `GET /api/favourites/{type}?page&size` → `Page<ArticleResponse|EventResponse>` ordered by `createdAt` desc, mapped back to favourite order, missing content skipped, **no `visible` filter**.
   - `{type}` other than `news`/`events` → 400 (enum converter in controller).
2. **Page serialization**: return `Page<T>` directly (PageImpl), matching `NewsController`/`EventsController` and the frontend's existing `{content,totalElements,totalPages,number,size}` consumption.
3. **Login popup**: extend `useAuth` with `loginWithPopup` from `useAuth0()`; pending action stored in the hook and executed after popup resolves; popup cancel (`PopupCancelledError`/any throw) leaves state untouched.
4. **Combined page**: one favourites toggle governs the whole feed; news section fetches `GET /api/favourites/news`, events sections fetch `GET /api/favourites/events` when active. Hearts stop propagation/prevent default because cards are `<a>` elements.
5. **No native-image hints needed** — GraalVM native build is currently disabled; plain `@Document` record suffices.

## Complexity Tracking

No constitution violations — table not required.
