# Tasks: Favourite News & Events

**Input**: Design documents from `/specs/029-favourite-news-events/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/favourites-api.yaml, quickstart.md

**Tests**: Included — the design doc's Testing section explicitly requires backend Testcontainers integration tests and frontend Vitest tests, and JaCoCo ≥ 0.78 applies to the new controller/service.

**Organization**: Grouped by user story. US1 = save/unsave hearts, US2 = favourites-only view, US3 = login popup flow.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1 / US2 / US3

## Path Conventions

Web app monorepo: `backend/src/main/java/com/simonrowe/...`, `backend/src/test/java/com/simonrowe/...`, `frontend/src/...`, `frontend/tests/...` per plan.md.

---

## Phase 1: Setup

No setup tasks — existing project, no new dependencies, no scaffolding required.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Domain model, persistence, security wiring, and shared frontend types that every story builds on.

- [X] T001 [P] Create `FavouriteType` enum (`NEWS`/`EVENT`) with `pathSegment` field and static `fromPathSegment(String)` returning `Optional<FavouriteType>` (or throwing for unknown), mapping `news`→`NEWS`, `events`→`EVENT`, in `backend/src/main/java/com/simonrowe/favourites/FavouriteType.java`
- [X] T002 [P] Create `Favourite` record — `@Document(collection = "favourites")`, `@CompoundIndex(name = "idx_user_type_content", def = "{'userId':1,'type':1,'contentId':1}", unique = true)`, fields `@Id String id, String userId, FavouriteType type, String contentId, Instant createdAt` — in `backend/src/main/java/com/simonrowe/favourites/Favourite.java` (see data-model.md)
- [X] T003 Create `FavouriteRepository extends MongoRepository<Favourite, String>` with `findByUserIdAndType(String, FavouriteType)`, `findByUserIdAndTypeOrderByCreatedAtDesc(String, FavouriteType, Pageable)`, `deleteByUserIdAndTypeAndContentId(String, FavouriteType, String)`, `existsByUserIdAndTypeAndContentId(String, FavouriteType, String)` in `backend/src/main/java/com/simonrowe/favourites/FavouriteRepository.java` (depends on T001, T002)
- [X] T004 [P] Create Mongock change unit `V013CreateFavouritesUniqueIndex` (`@ChangeUnit(id = "v013-create-favourites-unique-index", order = "013")`) creating the unique compound index on `favourites` `(userId, type, contentId)` via `mongoTemplate.indexOps("favourites")`, with `@RollbackExecution` dropping it, in `backend/src/main/java/com/simonrowe/migration/changeunits/V013CreateFavouritesUniqueIndex.java` — follow the structure of the existing `V0xx` change units and confirm how they are registered (package scan vs explicit list)
- [X] T005 Add `.requestMatchers("/api/favourites/**").authenticated()` **before** `.anyRequest().permitAll()` in `backend/src/main/java/com/simonrowe/auth/SecurityConfig.java`
- [X] T006 [P] Create `frontend/src/types/favourites.ts` exporting `FavouriteContentType = 'news' | 'events'` (re-use `ArticlePage`/`EventPage` from `types/news.ts`/`types/events.ts` — do not duplicate page shapes)

**Checkpoint**: Backend compiles with the new package; `/api/favourites/**` now requires auth.

---

## Phase 3: User Story 1 — Save and unsave a news article or event (Priority: P1) 🎯 MVP

**Goal**: Logged-in user clicks a heart on any feed card to save/unsave; state persists server-side per user and hearts hydrate on load.

**Independent Test**: Log in, click a heart on `/news-events`, reload → heart still filled; click again → empties. API-level: PUT then GET `/ids` shows the id; DELETE removes it; duplicate PUT keeps one document; no token → 401; unknown content id → 404.

### Implementation for User Story 1

- [X] T007 [US1] Implement `FavouritesService` in `backend/src/main/java/com/simonrowe/favourites/FavouritesService.java`: `add(userId, type, contentId)` — verify referenced content exists via `AggregatedArticleRepository`/`AggregatedEventRepository` `existsById` (throw `ResponseStatusException(NOT_FOUND)` if absent), insert `Favourite` with `Instant.now()`, catch `DuplicateKeyException` as success; `remove(userId, type, contentId)` — delete, idempotent; `getIds(userId, type)` — `Set<String>` of contentIds (depends on T003)
- [X] T008 [US1] Implement `FavouritesController` in `backend/src/main/java/com/simonrowe/favourites/FavouritesController.java`: `@RestController @RequestMapping("/api/favourites")`; resolve `{type}` via `FavouriteType.fromPathSegment` → `ResponseStatusException(BAD_REQUEST)` for unknown; `@PutMapping("/{type}/{id}")` → 204, `@DeleteMapping("/{type}/{id}")` → 204, `@GetMapping("/{type}/ids")` → `Set<String>`; user id from `@AuthenticationPrincipal final Jwt jwt` → `jwt.getSubject()` (pattern: `AdminBlogController`) (depends on T007)
- [X] T009 [US1] Backend integration test `backend/src/test/java/com/simonrowe/favourites/FavouritesControllerTest.java` extending `AbstractIntegrationTest`, auth via `jwt().jwt(j -> j.subject("user-a"))` (no admin authority), seeding real `AggregatedArticle`/`AggregatedEvent` docs, `@AfterEach` cleanup. Cover: add then `/ids` contains id; remove then `/ids` empty; idempotent double-add → single document in `FavouriteRepository`; 401 without token on all endpoints; user isolation (user-a's ids not visible to user-b); 404 on PUT for unknown content id; 400 for `{type}` = `podcasts` (depends on T008)
- [X] T010 [P] [US1] Create `frontend/src/services/favouritesApi.ts` mirroring `services/adminApi.ts` (`GetAccessToken` first arg, `authFetch` with `Bearer` header, `handleResponse<T>`, `API_BASE_URL` from `config/api.ts`): `getFavouriteIds(getAccessToken, type): Promise<string[]>`, `addFavourite(getAccessToken, type, id): Promise<void>`, `removeFavourite(getAccessToken, type, id): Promise<void>` (also `getFavourites` — added in US2, may stub now)
- [X] T011 [P] [US1] Create `FavouriteButton` in `frontend/src/components/common/FavouriteButton.tsx`: presentational `<button>` with Lucide `Heart` (filled via `fill="currentColor"` when `active`), props `{ active: boolean; onClick: () => void; label?: string }`, calls `e.preventDefault(); e.stopPropagation()` (cards are `<a>` elements), `aria-pressed` + accessible name; match style of `components/common/LoadingIndicator.tsx`
- [X] T012 [US1] Create `useFavourites(type)` hook in `frontend/src/hooks/useFavourites.ts`: holds `Set<string>` of favourited ids; loads from `getFavouriteIds` in `useEffect` when `isAuthenticated` (empty set when logged out); exposes `isFavourite(id)`, `toggleFavourite(id)` (optimistic add/remove + `addFavourite`/`removeFavourite`, revert on error), `loading`; uses `useAuth().getAccessToken` (login-popup gating added in US3 — for now, no-op when unauthenticated) (depends on T010)
- [X] T013 [US1] Render hearts in `frontend/src/pages/NewsEventsPage.tsx`: instantiate `useFavourites('news')` and `useFavourites('events')`; add `FavouriteButton` to `.feed__hero-card`, `.feed__card` (news) and `.feed__timeline-item` (events) with `active={isFavourite(item.id)}` and `onClick={() => toggleFavourite(item.id)}` (depends on T011, T012)
- [X] T014 [US1] Add BEM styles to `frontend/src/styles.css` in the "The Feed - News & Events" section: `.feed__favourite` (absolute corner placement on cards, works over `.feed__hero-image`), active/hover states using existing CSS custom properties; no new frameworks
- [X] T015 [P] [US1] Frontend service test `frontend/tests/services/favouritesApi.test.ts` (recipe: `tests/services/searchApi.test.ts` — `vi.mock('../../src/config/api')`, `vi.stubGlobal('fetch', vi.fn())`): correct URLs (`/api/favourites/news/ids`, PUT/DELETE `/api/favourites/news/{id}`), `Authorization: Bearer` header from `getAccessToken`, response parsing, error propagation
- [X] T016 [P] [US1] Frontend component test `frontend/tests/components/FavouriteButton.test.tsx`: renders empty vs filled by `active`; click invokes `onClick` and calls `preventDefault`/`stopPropagation`
- [X] T017 [US1] Frontend hook test `frontend/tests/hooks/useFavourites.test.ts` (mock `../../src/auth/useAuth` and `../../src/services/favouritesApi` with `vi.mock`): loads id set when authenticated; empty when logged out; optimistic toggle updates `isFavourite` immediately and calls add/remove; reverts on API failure (depends on T012)

**Checkpoint**: Hearts work end-to-end for a logged-in user; backend tests green; MVP deliverable.

---

## Phase 4: User Story 2 — View favourites only (Priority: P2)

**Goal**: A "Show favourites only" toggle in the feed filter bar swaps the listing to the user's saved items (newest-saved first, hidden content included, deleted content skipped).

**Independent Test**: With ≥1 favourite saved, enable the toggle → only saved items render in saved order (a `visible=false` favourite still shows); disable → full feed returns; zero favourites → empty state, no error.

### Implementation for User Story 2

- [X] T018 [US2] Extend `FavouritesService` with paged listing in `backend/src/main/java/com/simonrowe/favourites/FavouritesService.java`: `getFavouriteArticles(userId, pageable): Page<ArticleResponse>` and `getFavouriteEvents(userId, pageable): Page<EventResponse>` — page `favourites` via `findByUserIdAndTypeOrderByCreatedAtDesc`, bulk-load content with `findAllById`, re-order to favourite order, skip missing ids, wrap in `PageImpl` with the favourites page total; **no `visible` filter** (see research.md R3)
- [X] T019 [US2] Add `@GetMapping("/{type}")` to `FavouritesController` returning `Page<ArticleResponse>`/`Page<EventResponse>` (raw `Page` JSON, same as `NewsController`) with `page`/`size` request params (defaults 0/20) (depends on T018)
- [X] T020 [US2] Extend `FavouritesControllerTest` with listing cases: favourited article appears in `GET /api/favourites/news` ordered newest-saved first; `visible=false` favourited article included; favourite whose content was deleted is skipped without error; pagination fields (`totalElements`, `number`, `size`) present; 401 without token; per-user isolation on the listing (depends on T019)
- [X] T021 [US2] Add `getFavourites(getAccessToken, type, page = 0, size = 20)` to `frontend/src/services/favouritesApi.ts` returning `ArticlePage | EventPage`, plus URL/header/parsing cases in `frontend/tests/services/favouritesApi.test.ts`
- [X] T022 [US2] Add favourites-only mode to `frontend/src/pages/NewsEventsPage.tsx`: heart-labelled "Show favourites only" toggle rendered with the existing `.feed__filters` pills; when active, fetch `getFavourites('news', ...)` and `getFavourites('events', ...)` instead of the public listings and render the same card/timeline markup; empty state via existing `.feed__empty`/`.feed__events-empty` patterns; toggling off restores the normal feed (depends on T021, US1 page wiring T013)
- [X] T023 [US2] Add `.feed__favourites-toggle` (active state with filled heart) styles to `frontend/src/styles.css` alongside the `.feed__pill` styles

**Checkpoint**: Favourites-only view works for a logged-in user; US1 unaffected.

---

## Phase 5: User Story 3 — Login prompt with seamless completion (Priority: P3)

**Goal**: Logged-out heart/toggle clicks open `loginWithPopup` (no navigation); on success the pending action completes automatically; cancel leaves the page untouched.

**Independent Test**: While logged out, click a heart → popup opens, page does not navigate; complete login → heart fills with no extra clicks; dismiss popup → nothing saved. Same for the favourites toggle.

### Implementation for User Story 3

- [X] T024 [US3] Extend `frontend/src/auth/useAuth.ts` to expose `loginWithPopup` from `useAuth0()` (keep existing `login`/`logout`/`getAccessToken` exports unchanged)
- [X] T025 [US3] Add login gating to `frontend/src/hooks/useFavourites.ts`: when `toggleFavourite` (or a new `ensureAuthenticated()` helper used by the page toggle) is invoked while unauthenticated, `await loginWithPopup()`, then complete the pending action (save/unsave or resolve `true` so the page enables favourites-only view) and load the id set; catch popup cancellation/errors and discard the pending action (depends on T024)
- [X] T026 [US3] Wire the favourites-only toggle in `frontend/src/pages/NewsEventsPage.tsx` through the same gate: logged-out toggle click triggers the popup and, on success, enables favourites-only mode; on cancel stays on the normal feed (depends on T025)
- [X] T027 [US3] Extend `frontend/tests/hooks/useFavourites.test.ts`: logged-out toggle invokes `loginWithPopup` and completes the save after resolve; rejected popup → no API call, no state change; id set loads after login (depends on T025)

**Checkpoint**: All three stories functional independently.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T028 Run backend verification: `cd backend && ../gradlew check` (Checkstyle `google_checks.xml` 100-char/2-space rules, JaCoCo ≥ 0.78, all tests) and fix any violations
- [X] T029 Run frontend verification: `cd frontend && npm test` and `npm run lint` (if configured) and fix any failures
- [X] T030 Validate quickstart.md manually end-to-end (`./scripts/start.sh`, hearts, toggle, popup flow, curl matrix: 401/400/404) and correct quickstart.md if behaviour differs

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 2)**: T001, T002 → T003; T004, T005, T006 independent of each other. BLOCKS all stories.
- **US1 (Phase 3)**: needs Phase 2. Backend chain T007 → T008 → T009. Frontend: T010/T011 parallel → T012 → T013 → T014; tests T015/T016 parallel anytime after their targets, T017 after T012.
- **US2 (Phase 4)**: needs US1's controller/service/page wiring (extends the same files). T018 → T019 → T020; T021 → T022 → T023.
- **US3 (Phase 5)**: needs US1's hook (edits it) and US2's toggle for T026. T024 → T025 → T026/T027.
- **Polish (Phase 6)**: after all desired stories.

### Story independence

US1 alone is a shippable MVP (hearts for logged-in owner). US2 extends the same controller/page but is independently testable via its own toggle. US3 layers auth UX over both without changing backend code.

### Parallel Opportunities

- Phase 2: T001 ∥ T002 ∥ T004 ∥ T005 ∥ T006
- US1: backend (T007–T009) ∥ frontend (T010–T017); within frontend T010 ∥ T011, T015 ∥ T016
- US2 backend (T018–T020) ∥ US2 frontend service work (T021)

---

## Implementation Strategy

MVP first: Phase 2 → US1 → validate (backend integration tests + manual heart check) → US2 → US3 → Polish. Each checkpoint is a coherent, testable increment; stop at any checkpoint if scope must shrink.

**Task count**: 30 total — Foundational 6, US1 11, US2 6, US3 4, Polish 3.
