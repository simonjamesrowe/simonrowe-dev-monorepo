---

description: "Task list for 035-listen-from-listing"
---

# Tasks: Listen from the listing

**Input**: Design documents from `/specs/035-listen-from-listing/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/narrations-ready.yaml](./contracts/narrations-ready.yaml)

**Tests**: **Included.** The spec has an explicit Testing section naming the required backend and
frontend tests, so test tasks are mandatory here, not optional.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4)
- Exact file paths are given in every task

## Path Conventions

Web app: `backend/src/main/java/com/simonrowe/…`, `backend/src/test/java/com/simonrowe/…`,
`frontend/src/…`, `frontend/tests/…`.

---

## Phase 1: Setup

**Purpose**: Confirm the baseline is green before touching anything, so a later failure is
attributable. **No new dependency is added in either module** — nothing to install.

- [X] T001 Record the baseline: run `cd backend && ../gradlew test checkstyleMain checkstyleTest` and `cd frontend && npm test && npm run lint`, and note any pre-existing failure so it is not mistaken for a regression later
- [X] T002 [P] Read `docs/superpowers/specs/2026-08-26-listen-from-listing-design.md` alongside [research.md](./research.md) — the ten decisions there are the authority for every judgement call below

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The bulk read path and the shared frontend helpers. Every user story reads the ready
map, so this phase blocks all of them.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Backend — the bulk ready lookup

- [X] T003 [P] Create `backend/src/main/java/com/simonrowe/narration/ReadyNarration.java` — a record `(String contentId, String audioUrl, Long durationSeconds)` with javadoc recording that for `ARTICLE_SUMMARY` the `contentId` is the **aggregated article id, not the summary id** (per `ArticleSummaryNarrationSource`), which is what lets the news listing key off ids it already holds
- [X] T004 Add `readyNarrations(NarrationContentType contentType)` to `backend/src/main/java/com/simonrowe/narration/NarrationService.java` — a `MongoTemplate` aggregation `match(contentType, status=READY)` → `sort(updatedAt DESC)` → `group("contentId").first("audioPath")/first("durationSeconds")` → `project` to `ReadyNarration`; mirror the `NewsController.listSources()` aggregation style; javadoc must record why the reduction is server-side (narrations are fingerprint-addressed so one content id owns several rows) and that `idx_narration_content_updated` already orders it so **no new index and no Mongock change unit is needed**
- [X] T005 Create `backend/src/main/java/com/simonrowe/narration/NarrationReadyController.java` — `@GetMapping` on `/api/narrations/ready` taking a required `@RequestParam NarrationContentType contentType`, returning `List<ReadyNarration>`; javadoc must record that it is public because the audio is globally shared (mirroring `GET /api/news/summaries/ids`) **and** that the path deliberately does not match `RateLimitInterceptor`'s `/api/blogs/*/narration` pattern, so a listing page load spends nothing from the 10/min narration bucket
- [X] T006 [P] Create `backend/src/test/java/com/simonrowe/narration/NarrationReadyControllerTest.java` — MockMvc standalone against a mocked `NarrationService` (the shape `SummaryNarrationControllerTest` uses): both content types delegate with the right enum, an empty result serialises as `[]` not 404, the JSON field names are `contentId`/`audioUrl`/`durationSeconds`, and an unrecognised `contentType` is a 400
- [X] T007 [P] Create `backend/src/test/java/com/simonrowe/narration/NarrationReadyAggregationTest.java` extending `AbstractIntegrationTest` — seed real `Narration` documents and assert: one row per `contentId`; the **newest** `READY` row wins when a content id has two; a `STALE` sibling is never returned; `FAILED`/`UNCERTAIN`/`QUEUED`/`PROCESSING` rows are never returned; `BLOG` and `ARTICLE_SUMMARY` are isolated from each other
- [X] T008 [P] Add a case to `backend/src/test/java/com/simonrowe/auth/SecurityConfigTest.java` asserting `GET /api/narrations/ready?contentType=BLOG` is 200 anonymously

### Frontend — shared helpers

- [X] T009 [P] Create `frontend/src/components/narration/formatDuration.ts` — move `formatApproximateDuration` out of `NarrationPanel.tsx` verbatim (`About N min`) and add `formatCompactDuration` (`N min`) over one shared minute calculation, so the *number* cannot drift between the detail-page player and the card even though they word it differently
- [X] T010 Update `frontend/src/components/narration/NarrationPanel.tsx` to import `formatApproximateDuration` from `./formatDuration` and delete the local copy — no rendered output changes, so `frontend/tests/components/narration/NarrationPanel.test.tsx` must stay green untouched
- [X] T011 [P] Create `frontend/tests/components/narration/formatDuration.test.ts` — both formatters, including the `Math.max(1, …)` floor for sub-30-second audio
- [X] T012 [P] Export `LONG_POLL_SECONDS` and `MAX_LONG_POLLS` from `frontend/src/components/narration/useNarration.ts` (currently module-private consts) so the provider imports them rather than introducing a second polling policy; leave every other line of that file alone
- [X] T013 [P] Create `frontend/src/types/narrationAudio.ts` — `ReadyNarration`, `NarrationTrack`, `ChainStage` and `NarrationAudioState` exactly as specified in [data-model.md](./data-model.md#frontend-state)
- [X] T014 Create `frontend/src/services/narrationApi.ts` — `fetchReadyNarrations(contentType, signal?)` calling `GET ${API_BASE_URL}/api/narrations/ready?contentType=…`; public, so no token; rejects on non-2xx with a fallback message, matching the shape of `fetchSummarisedArticleIds`
- [X] T015 [P] Create `frontend/tests/services/narrationApi.test.ts` — the query string, the parsed array, and that a rejection surfaces as an `Error` rather than a raw `TypeError`

**Checkpoint**: `GET /api/narrations/ready` works end to end and the shared frontend helpers exist. Run `curl -s 'http://localhost:8080/api/narrations/ready?contentType=BLOG' | jq` per [quickstart.md](./quickstart.md).

---

## Phase 3: User Story 1 — Play existing audio straight from a listing card (Priority: P1) 🎯 MVP

**Goal**: A reader on `/blogs` or `/news-events` sees `▶ N min` on items that already have audio,
presses it, and playback starts instantly in a docked bar that survives filtering, "Load more" and
navigation.

**Independent Test**: Seed narration audio for one blog post and one aggregated article, open each
listing page, confirm the card advertises a duration, press it, and confirm audio survives a filter
change and a route change.

**Note**: this phase deliberately implements only the *no-network* branch of `listen()` — a cold
card renders its `Listen` invitation but does nothing yet. The chain lands in Phase 5.

### Provider and player

- [X] T016 [US1] Create `frontend/src/components/narration/NarrationAudioProvider.tsx` — a context provider holding: the ready map (`Map<'${contentType}:${contentId}', ReadyNarration>`, populated once per mount by two `fetchReadyNarrations` calls, one per content type), a **detached `new Audio()` in a ref** (never JSX), `track`/`stage`/`playing`/`position`/`rate`, and a `listen(request)` that for this phase handles only the "already in the ready map → load and play, no network" branch. Subscribe to `timeupdate`/`play`/`pause`/`ended` to mirror playback state. On its own element's `play`, pause every other `audio` on the page (the mirror image of `NarrationPanel`'s handler). A failed bulk fetch must leave the map empty and never throw. Class javadoc must record why the provider sits above `<Routes>` and why the element is detached (`PublicLayout` wraps each route individually, so anything inside it remounts on navigation)
- [X] T017 [US1] Create `frontend/src/components/narration/useNarrationAudio.ts` — the context hook, in its own file so `NarrationAudioProvider.tsx` exports only a component and the `react-refresh` ESLint rule stays quiet
- [X] T018 [US1] Create `frontend/src/components/narration/ListenButton.tsx` — props `{contentType, contentId, title, href}`; reads everything from `useNarrationAudio()` keyed on `contentId` and holds **no local state**; renders `▶ N min` via `formatCompactDuration` when the ready map has the key, and a secondary-weight `Listen` otherwise; calls `preventDefault()` + `stopPropagation()` in its click handler exactly as `SummaryButton` does, because news cards are `<a>` anchors; `aria-label` names the item
- [X] T019 [US1] Create `frontend/src/components/narration/NarrationPlayerBar.tsx` — renders nothing when `track` is null; for `stage === 'ready'` shows the title as a link (internal `<Link>` for `BLOG`, `target="_blank" rel="noopener noreferrer"` for a news article), play/pause, a seek input, elapsed/total, the `PLAYBACK_SPEEDS` select and a dismiss control; a labelled `region` with an `aria-live="polite"` status element for stage changes
- [X] T020 [US1] Wire `frontend/src/App.tsx` — mount `<NarrationAudioProvider>` **inside `AuthProvider` and above `<Suspense>`/`<Routes>`**, and render `<NarrationPlayerBar />` inside `PublicLayout` (which is what keeps it off `/admin` with no path sniffing). Add a comment at the mount point recording that the position is load-bearing

### Card placement

- [X] T021 [P] [US1] Add an actions row to `frontend/src/components/blog/ArticleCard.tsx` (`article-card__actions`, alongside the existing "Read post" link) containing `<ListenButton contentType="BLOG" …>`; note in a comment that this card is shared with the home page's `FeaturedWriting`, which inherits the control deliberately
- [X] T022 [P] [US1] Add an actions row to `frontend/src/components/blog/FeaturedArticle.tsx` (`featured-article__actions`) containing the same control
- [X] T023 [US1] Add `<ListenButton contentType="ARTICLE_SUMMARY" contentId={article.id} …>` inside **both** existing `.feed__card-actions` containers in `frontend/src/pages/NewsEventsPage.tsx` — the `feed__hero-card` block and the `feed__card` block — beside the unchanged `SummaryButton` and `FavouriteButton`. Do **not** add one to the events timeline: events are never summarised, so they can have no audio
- [X] T024 [US1] Add `.listen-button` (and its `--ready`/`--cold`/`--busy` modifiers) and `.narration-bar` BEM blocks to `frontend/src/styles.css`, plus `.article-card__actions` and `.featured-article__actions`. Plain CSS, BEM, existing custom properties only. `.feed__card-actions` already flexes with a `0.4rem` gap, so the third child needs no change there

### Tests

- [X] T025 [P] [US1] Create `frontend/tests/components/narration/ListenButton.test.tsx` — ready renders the duration, cold renders `Listen` at secondary weight, and a click inside an `<a>` does not navigate (asserts `preventDefault` was called)
- [X] T026 [P] [US1] Create `frontend/tests/components/narration/NarrationPlayerBar.test.tsx` — renders nothing with no track; ready state shows title link, transport, elapsed/total and the speed select; the region is labelled and has an `aria-live="polite"` status; dismiss clears the track and stops playback
- [X] T027 [P] [US1] Create `frontend/tests/components/narration/NarrationAudioProvider.test.tsx` covering this phase's scope — the ready map is fetched once per content type on mount; a ready `listen()` starts playback with **zero** further fetch calls; a rejected bulk fetch leaves the map empty and renders no error
- [X] T028 [P] [US1] Extend `frontend/tests/pages/NewsEventsPage.test.tsx` — an article with ready audio renders its duration; one without renders the cold state; an event card renders no listen control; a news card renders exactly three actions
- [X] T029 [P] [US1] Extend `frontend/tests/pages/BlogListingPage.test.tsx` — the featured article and the grid cards each render a listen control

**Checkpoint**: US1 is independently shippable. Walk the Story 1 block of [quickstart.md](./quickstart.md#manual-verification-checklist), including the DevTools check that a `/blogs` load issues exactly one `/api/narrations/ready` request per content type and **no** `/api/blogs/*/narration` requests.

---

## Phase 4: User Story 4 — Generating audio is a privileged action everywhere (Priority: P2)

**Goal**: `POST /api/blogs/{blogId}/narration` requires a JWT from every surface, so exposing
generation on listing cards cannot drain the monthly TTS budget anonymously.

**Independent Test**: `POST` the blog narration endpoint anonymously (401), with a valid JWT
(reaches the controller), and `GET` it anonymously (still public).

**Dependency**: must land before Phase 5 — gating only the new listing surface would leave the
identical post anonymously narratable from its detail page.

- [X] T030 [US4] In `backend/src/main/java/com/simonrowe/auth/SecurityConfig.java`, add `.requestMatchers(HttpMethod.POST, "/api/blogs/*/narration").authenticated()` and **rewrite the comment block** that currently explains the asymmetry as deliberate ("Note the asymmetry with /api/blogs/*/narration, whose POST is public…") to record that both endpoints now spend the same monthly TTS budget and are gated alike. `GET` stays public
- [X] T031 [US4] Rewrite the class javadoc on `backend/src/main/java/com/simonrowe/narration/BlogNarrationController.java` — it currently reads "Deliberately frozen: same path, same public (unauthenticated) POST"; replace with why that changed (same TTS budget as summary narration; the listing exposes generation on every card) while recording that the path, the response body and the public `GET` are unchanged
- [X] T032 [US4] In `backend/src/test/java/com/simonrowe/auth/SecurityConfigTest.java`, **invert** `blogNarrationPostRemainsPublic` — rename it to reflect the new posture, assert 401 anonymously, and add companions asserting (a) a valid JWT with no admin role reaches the controller (404 for a missing blog, proving the filter chain let it through) and (b) `GET /api/blogs/{id}/narration` is still public. Delete the "so nobody 'harmonises' the two by accident" javadoc and replace it with the new rationale
- [X] T033 [US4] Change `requestBlogNarration` in `frontend/src/services/blogApi.ts` to take a `getAccessToken: GetAccessToken` first parameter and send `Authorization: Bearer …`, mirroring `requestSummaryNarration` in `services/articleSummaryApi.ts`; keep the 503-is-part-of-the-contract handling exactly as it is
- [X] T034 [US4] Add the `useEnsureAuthenticated()` + `useAuth().getAccessToken` pair to `frontend/src/components/blog/BlogNarration.tsx`'s transport, returning an `UNAVAILABLE` response with `"Sign in to generate audio for this post."` when the popup is dismissed — the exact pattern `SummaryNarration.tsx` already uses. Update the stale comment that says this endpoint is "Public, unlike the summary equivalent"
- [X] T035 [US4] Update `frontend/src/components/blog/BlogNarration.test.tsx` for the new `requestBlogNarration` signature and add a case for the dismissed-popup path; the other seven assertions must keep passing unchanged
- [X] T036 [P] [US4] Correct `docs/runbooks/article-summaries.md` — the paragraph asserting "**`POST /api/blogs/{id}/narration` is still public** — that asymmetry is deliberate and `SecurityConfigTest` asserts it, so do not 'harmonise' the two" is now actively wrong. Also add `GET /api/narrations/ready` (public) to the endpoint table
- [x] T037 [P] [US4] Correct the `034-article-summary-audio` note in `CLAUDE.md` that described the public `POST` as intentional — **already applied during planning**; verify the wording still matches the shipped behaviour before closing this task

**Checkpoint**: US4 is independently verifiable with the three `curl` calls in [quickstart.md](./quickstart.md#verify-the-backend-by-hand). Run `../gradlew test --tests 'com.simonrowe.auth.SecurityConfigTest'`.

---

## Phase 5: User Story 2 — Generate audio for an item that has none, from the listing (Priority: P2)

**Goal**: Pressing `Listen` on a cold card signs the reader in, runs the escalating chain, reports
each stage in the bar, auto-plays on completion, and leaves the card advertising its new duration.

**Independent Test**: With no audio present, press `Listen` on a blog card and on a news card
(one with a summary, one without), complete sign-in, and confirm the bar reports progress,
auto-plays, and the card ends up showing a duration.

**Dependencies**: Phase 3 (provider, button, bar) and Phase 4 (the blog POST now needs a token).

- [X] T038 [US2] Extend `listen(request)` in `frontend/src/components/narration/NarrationAudioProvider.tsx` with the escalating chain — sign in via `useEnsureAuthenticated()` (a dismissed popup issues **no request** and shows **no error**), then: blog → `POST /api/blogs/{id}/narration`; news with a summary → `POST /api/news/{id}/summary/narration`; news without → `POST /api/news/{id}/summary` (blocks 15–30s, `stage: 'summarising'`) then the narration POST. Poll to completion using the imported `LONG_POLL_SECONDS`/`MAX_LONG_POLLS`. One `AbortController` per chain, replaced on each `listen()` call so a new track abandons the in-flight one
- [X] T039 [US2] Add auto-play-on-ready to the provider, gated on a `dismissedTrackKey` flag: when a chain completes, always write the finished track into the ready map (so the card flips whether or not the bar is still on screen), but only start playback if the reader did **not** dismiss the bar for that track
- [X] T040 [US2] Add `lastCompleted: {contentType, contentId, summaryWasGenerated} | null` to the provider's exposed value — the publish half of "who owns *this is now ready*". The provider must not write to `useArticleSummaries`, which is mounted below it
- [X] T041 [US2] Add the in-flight branch to `frontend/src/components/narration/ListenButton.tsx` — spinner plus a stage label ("Summarising…" / "Preparing audio…") when `track` matches this `contentId` and `stage` is `summarising`/`narrating`; derived from provider state only, so a filter change or "Load more" cannot lose it
- [X] T042 [US2] Add the in-flight layout to `frontend/src/components/narration/NarrationPlayerBar.tsx` — title plus the stage label, **no transport controls**, dismiss still available; announce the stage change through the existing `aria-live="polite"` status
- [X] T043 [US2] Add `noteSummarised(articleId: string)` to `frontend/src/hooks/useArticleSummaries.ts` — the same local flip `store()` already performs (`setSummarisedIds(prev => new Set(prev).add(articleId))`), idempotent, no refetch of `/api/news/summaries/ids`. Add it to the `ArticleSummariesApi` interface with javadoc explaining why it exists
- [X] T044 [US2] In `frontend/src/pages/NewsEventsPage.tsx`, watch the provider's `lastCompleted` and call `summaries.noteSummarised(contentId)` when `contentType === 'ARTICLE_SUMMARY' && summaryWasGenerated`, so a card whose summary came from the Listen chain flips from "Summarise" to "Read summary" without a reload
- [X] T045 [P] [US2] Extend `frontend/tests/components/narration/NarrationAudioProvider.test.tsx` — one case per chain branch in the [data-model.md](./data-model.md#provider-state) table, plus: abort-on-new-track, auto-play on ready, a dismissed sign-in popup issuing no request and showing no error, and **dismissing the bar mid-chain suppresses auto-play but still marks the track ready so the card flips to `▶`**
- [X] T046 [P] [US2] Add a `noteSummarised` case to `frontend/tests/hooks/useArticleSummaries.test.ts` (create the file if absent) — it flips `hasSummary`, and calling it twice is harmless
- [X] T047 [P] [US2] Extend `frontend/tests/pages/NewsEventsPage.test.tsx` — a card whose summary came from the Listen chain flips to "Read summary"
- [X] T048 [P] [US2] Extend `frontend/tests/components/narration/ListenButton.test.tsx` with the in-flight state (spinner + each stage label)

**Checkpoint**: walk the Story 2 block of [quickstart.md](./quickstart.md#manual-verification-checklist), including the dismiss-mid-generation case.

---

## Phase 6: User Story 3 — Understand what went wrong without losing your place (Priority: P3)

**Goal**: Every failure is reported in the bar in plain language, retryable only where retrying
helps, with the card back at rest and the listing intact.

**Independent Test**: force each condition in the spec's error table and confirm the stated message,
the stated retryability, that the card is at rest, and that the listing still renders.

**Dependencies**: Phase 5 (there is no chain to fail before it).

- [X] T049 [US3] Add the error-mapping helper to `frontend/src/components/narration/NarrationAudioProvider.tsx` implementing the [research.md](./research.md#r11-error-taxonomy) table: narration `UNAVAILABLE` (from `BUDGET_EXHAUSTED`) → "Audio is unavailable this month.", not retryable; narration `FAILED` with `retryable: true` → the server's message plus a retry; summary `FAILED` with `INSUFFICIENT_SOURCE_TEXT` → "There isn't enough of this article to summarise.", not retryable. Errors set `error` on provider state and reset `stage` to `'idle'`, so the card returns to rest
- [X] T050 [US3] Add 429 handling to the provider's fetch wrapper — inspect `response.status === 429` and the `Retry-After` header **before** delegating to the parsing helpers (the existing `readNarration`/`readSummary` helpers throw on non-2xx/503 and would lose the header), and surface it as a retryable error using the server's own wait time
- [X] T051 [US3] Add the error and retry affordances to `frontend/src/components/narration/NarrationPlayerBar.tsx` — the message with `role="alert"`, a retry control only when `error.retryable`, and a manual re-check control when polling exhausted `MAX_LONG_POLLS` (mirroring `NarrationPanel`'s `delayed` state rather than reporting a failure)
- [X] T052 [US3] Handle a dead audio URL — subscribe to the detached element's `error` event and report "This audio is no longer available." while clearing the track (a narration can be deleted, or restored over: a restore drops collections)
- [X] T053 [P] [US3] Extend `frontend/tests/components/narration/NarrationAudioProvider.test.tsx` with one case per row of the error table, asserting the exact message and the retryable flag, and that `stage` returns to `'idle'`
- [X] T054 [P] [US3] Extend `frontend/tests/components/narration/NarrationPlayerBar.test.tsx` — a retryable error shows a retry control, a non-retryable one does not, poll exhaustion offers a re-check, and the error is announced with `role="alert"`

**Checkpoint**: walk the Story 3 block of [quickstart.md](./quickstart.md#manual-verification-checklist).

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T055 [P] Responsive bar: in `frontend/src/styles.css` (and via the existing `useMediaQuery` hook if a JS branch is needed), keep title, play/pause and a progress line at phone widths and drop the playback-speed control; verify nothing overflows
- [X] T056 [P] Accessibility pass on `NarrationPlayerBar` and `ListenButton` — full keyboard operation, the bar as a labelled `region`, stage changes announced politely, every control with an accessible name; add the keyboard-operation assertions to `frontend/tests/components/narration/NarrationPlayerBar.test.tsx`
- [X] T057 Verify the two-players case. **A real defect was found here, not just verified:** the design's claim that a *detached* `new Audio()` is visible to `document.querySelectorAll('audio')` is wrong — `querySelectorAll` only walks the document, so `NarrationPanel`'s "pause every other audio" would never have paused the bar. Fixed by appending the element to `<body>` (still outside anything React reconciles, so it survives navigation) and removing it on unmount. Both directions are now asserted in `NarrationAudioProvider.test.tsx` rather than checked by hand. `NarrationPanel` itself is unchanged.
- [X] T058 Confirm `NarrationScriptBuilder.FORMAT_VERSION` is still the literal `blog-narration-v1` and that no Mongock change unit, `BackupService.BACKUP_COLLECTIONS` entry or `RestoreService.IMPORT_ORDER_INDEPENDENT` entry was added — this feature adds no persistence
- [X] T059 Endpoint table rows — already covered by T036, which added `POST`/`GET /api/blogs/{id}/narration` and `GET /api/narrations/ready` plus the two explanatory notes. Nothing further needed.
- [X] T060 Run the full gates: `cd backend && ../gradlew test checkstyleMain checkstyleTest` (JaCoCo ≥0.78 must hold) and `cd frontend && npm test && npm run lint` (lint must exit 0 — it is a blocking CI step)
- [ ] T061 Walk the whole of [quickstart.md](./quickstart.md#manual-verification-checklist) against a running local stack with prod-like data (`prod-data-restore` skill), then use the `pr-review-loop` skill to open the PR and drive all three signals green

---

## Dependencies & Execution Order

```text
Phase 1 (Setup)
   ↓
Phase 2 (Foundational: bulk endpoint + shared helpers)   ← blocks everything
   ↓
Phase 3 (US1, P1)  ─── MVP: play what already exists
   ↓
Phase 4 (US4, P2)  ─── auth tightening; MUST precede Phase 5
   ↓
Phase 5 (US2, P2)  ─── the generation chain
   ↓
Phase 6 (US3, P3)  ─── error handling
   ↓
Phase 7 (Polish)
```

Story-level notes:

- **US1** depends only on Phase 2. It is the MVP and is shippable alone.
- **US4** is independent of US1 in code — its backend and doc tasks (T030–T032, T036) could run in
  parallel with Phase 3 — but it **must** be complete before US2, per the spec's own reasoning.
- **US2** depends on US1 (provider, button, bar) and US4 (the blog POST now needs a token).
- **US3** depends on US2 — there is no chain to fail before it.

Within-phase dependencies worth respecting:

- T004 needs T003 (the record is the aggregation's output type); T005 needs T004.
- T010 needs T009. T014 needs T013.
- T017–T019 need T016 (they consume the context). T020 needs T016 and T019.
- T021–T023 need T018. T024 can start any time but is only verifiable after T021–T023.
- T041/T042 need T038. T044 needs T040 and T043.
- T049–T052 need T038.

## Parallel Execution Examples

**Phase 2** — three independent tracks:

```text
Backend:  T003 → T004 → T005, with T006 ‖ T007 ‖ T008 after T005
Frontend: T009 → T010, with T011 in parallel
Frontend: T012 ‖ T013 → T014 → T015
```

**Phase 3** — after T016–T020 land, the card placements and their tests fan out:

```text
T021 ‖ T022 ‖ T023 ‖ T024
then T025 ‖ T026 ‖ T027 ‖ T028 ‖ T029
```

**Phase 4** — the backend, frontend and doc edits are three independent files sets:

```text
T030 → T031 → T032        (backend + its test)
T033 → T034 → T035        (frontend)
T036 ‖ T037               (docs)
```

**Phase 5** — after T038–T044, the four test tasks are fully parallel:

```text
T045 ‖ T046 ‖ T047 ‖ T048
```

## Implementation Strategy

**MVP = Phase 1 + Phase 2 + Phase 3 (US1).** That alone delivers the headline value — browse and
listen at the same time — with no authentication change, no generation chain and no new failure
modes. It is worth pausing there and using it before continuing.

**Then Phase 4 + Phase 5 together.** They are one shippable unit: US4 without US2 is a bare
authorisation tightening with no user-visible benefit, and US2 without US4 would ship a budget hole.

**Then Phase 6 and Phase 7.** US3 hardens what Phase 5 introduced; Phase 7 is the responsive,
accessibility and gate work that closes the feature out.

## Task Count Summary

| Phase | Story | Tasks | Count |
|---|---|---|---|
| 1 Setup | — | T001–T002 | 2 |
| 2 Foundational | — | T003–T015 | 13 |
| 3 | US1 (P1) | T016–T029 | 14 |
| 4 | US4 (P2) | T030–T037 | 8 |
| 5 | US2 (P2) | T038–T048 | 11 |
| 6 | US3 (P3) | T049–T054 | 6 |
| 7 Polish | — | T055–T061 | 7 |
| **Total** | | | **61** |

Of these, 9 are new test files and 7 are extensions to existing test files — matching the spec's
Testing section item for item.
