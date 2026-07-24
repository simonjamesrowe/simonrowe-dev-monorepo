# Research: Favourite News & Events

**Date**: 2026-07-24 | **Feature**: 029-favourite-news-events

No NEEDS CLARIFICATION markers remained in the Technical Context; research focused on
verifying the approved design doc against the actual codebase. Findings below.

## R1. Which page hosts the feature

- **Decision**: Implement hearts + favourites-only toggle in `frontend/src/pages/NewsEventsPage.tsx` (routed at `/news-events`).
- **Rationale**: `NewsPage.tsx` / `EventsPage.tsx` named in the design doc exist but are not routed and their BEM classes have no CSS; the live feed page is `NewsEventsPage.tsx` with `.feed__*` BEM blocks in `styles.css` (~lines 8290–8660).
- **Alternatives considered**: Adding routes for the legacy pages — rejected (out of scope, duplicates the feed).

## R2. Idempotent add under a unique index

- **Decision**: `FavouritesService.add` checks existence of the referenced article/event (404 if absent), then inserts and swallows `DuplicateKeyException` (treat as success).
- **Rationale**: The unique compound index `(userId, type, contentId)` makes the database the arbiter of idempotency; catching the duplicate-key race is simpler and safer than check-then-insert alone (double-click race).
- **Alternatives considered**: `findAndModify` upsert — more Mongo-template code for no benefit at this scale.

## R3. Favourites-listing query shape

- **Decision**: Page `favourites` by `(userId, type)` ordered `createdAt` desc via a derived repository method (`findByUserIdAndTypeOrderByCreatedAtDesc(Pageable)`), then bulk-load referenced content with `repository.findAllById(...)`, re-order to match favourite order, skip missing ids, and wrap in a `PageImpl` carrying the favourites page's total.
- **Rationale**: Two indexed queries; keeps `visible` out of the query (owner's private list per FR-010). Deleted content simply doesn't come back from `findAllById` and is skipped.
- **Alternatives considered**: `$lookup` aggregation — premature for tens of rows; harder to keep type-generic across two collections.
- **Note**: When referenced content was deleted, the page's `totalElements` may slightly overcount actual rendered items. Accepted (spec only requires silent skipping; volume is tiny).

## R4. `{type}` path-segment validation

- **Decision**: `FavouriteType` enum with `NEWS("news")` / `EVENT("events")` and a `fromPathSegment` factory; controller resolves it and throws `ResponseStatusException(BAD_REQUEST)` for anything else.
- **Rationale**: Explicit 400 per FR-007; avoids relying on implicit Spring enum conversion (which would produce a less controlled error shape).

## R5. Security wiring

- **Decision**: Add `.requestMatchers("/api/favourites/**").authenticated()` in `SecurityConfig` **before** `.anyRequest().permitAll()`; controller methods take `@AuthenticationPrincipal final Jwt jwt` and scope everything to `jwt.getSubject()`.
- **Rationale**: Matcher order is first-match-wins; without the new matcher the endpoints would be public and `jwt` could be null. Pattern copied from `AdminBlogController` (`jwt.getSubject()` usage) but with `.authenticated()` rather than the admin role, per the design (any valid identity gets private favourites).

## R6. Login popup

- **Decision**: Extend `auth/useAuth.ts` to also expose `loginWithPopup` from `useAuth0()`. `useFavourites` awaits `loginWithPopup()` when `isAuthenticated` is false, then re-runs the pending toggle/enable action; any throw (user closed popup, timeout) is caught and discarded.
- **Rationale**: `loginWithPopup` completes the session in-page without navigation; the SPA callback URL already registered for `/admin` covers the popup's silent callback, so no Auth0 tenant change. `loginWithRedirect` would bounce to `/admin` (hardcoded `redirect_uri`) and lose the pending action.
- **Alternatives considered**: `loginWithRedirect` + `appState` restore — rejected: full navigation, violates "never leaves the page" requirement.

## R7. Page response serialization

- **Decision**: Return Spring `Page<T>` directly from `GET /api/favourites/{type}` (PageImpl JSON), reusing `ArticleResponse.from` / `EventResponse.from`.
- **Rationale**: `NewsController`/`EventsController` already do this and the frontend types (`ArticlePage`, `EventPage`) consume `{content,totalElements,totalPages,number,size}`. Consistency beats adopting `PagedModel`.

## R8. Frontend state scope

- **Decision**: `useFavourites(type)` hook holds a `Set<string>` of favourited ids per content type; `NewsEventsPage` instantiates it twice (news + events). No context/global store.
- **Rationale**: Only one page uses favourites; two independent id sets match the two API types. Constitution V (YAGNI).

## R9. Heart button inside `<a>` cards

- **Decision**: `FavouriteButton` renders a `<button>` that calls `e.preventDefault()` and `e.stopPropagation()` before invoking `onClick`.
- **Rationale**: Feed cards are anchor elements linking to `originalUrl`; without this the click would open the article.

## R10. Test approach

- **Decision**:
  - Backend: one integration test class `FavouritesControllerTest extends AbstractIntegrationTest` seeding real Mongo docs, driving MockMvc with `jwt().jwt(j -> j.subject("user-a"))` (no admin authority needed). Covers add+list+ids, remove, idempotent add, 401 unauthenticated, per-user isolation, 404 unknown content, 400 bad type, hidden-content inclusion, deleted-content skipping.
  - Frontend: `favouritesApi` (URLs/Bearer header/parsing, `vi.stubGlobal('fetch')`), `useFavourites` (`renderHook`, mocked `useAuth`), `FavouriteButton` (filled/empty render, propagation stopped).
- **Rationale**: Matches `AdminBlogControllerTest` / `searchApi.test.ts` / `useAdminRole.test.ts` recipes verbatim; keeps JaCoCo ≥ 0.78 (new controller is not excluded).

## R11. Unique index creation

- **Decision**: Create the unique `(userId, type, contentId)` index via Mongock change unit
  `V013CreateFavouritesUniqueIndex` (`mongoTemplate.indexOps("favourites").createIndex(...)`).
- **Rationale**: `spring.data.mongodb.auto-index-creation` is not enabled (Spring Data default
  `false`), and no existing mechanism creates annotated indexes — the `@CompoundIndex`
  annotations on existing documents are effectively documentation. FR-002's idempotency relies
  on real uniqueness, so the index must be created explicitly; Mongock change units
  (`com.simonrowe.migration.changeunits.V001–V012`) are the repo's established mechanism.
  Index creation is idempotent and harmless when change units run in integration tests.
- **Alternatives considered**: enabling `auto-index-creation` globally — rejected (would start
  enforcing every annotated index across all collections at startup, with unknown prod data);
  `@PostConstruct` ensureIndex in the service — rejected in favour of the existing migration
  convention.

## R12. Native image / reflection hints

- **Decision**: None needed.
- **Rationale**: GraalVM native build is disabled in `backend/build.gradle.kts` (JVM buildpack image); repo has no reflect-config or RuntimeHints for existing documents either.
