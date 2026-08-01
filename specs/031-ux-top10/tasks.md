---

description: "Task list for UX Top-10 Improvements"
---

# Tasks: UX Top-10 Improvements

**Input**: Design documents from `/specs/031-ux-top10/`

**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/`

**Tests**: **REQUIRED.** The spec asks for them explicitly (FR-042, SC-018, and the
spec's Testing section), so every story carries test tasks.

**Organization**: Grouped by user story so each is independently implementable and
testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel — different files, no dependency on incomplete work
- **[Story]**: `[US1]`…`[US8]`, mapping to the user stories in `spec.md`

## Path Conventions

Web app monorepo: `backend/src/main/java/com/simonrowe/…`,
`backend/src/test/java/com/simonrowe/…`, `frontend/src/…`, `frontend/tests/…`,
`frontend/e2e/…`.

## ⚠️ Two shared-file hazards

1. **`frontend/src/styles.css` is one 9,141-line file** touched by US1, US5, US6,
   US7 and US8. Tasks that edit it are **never** marked `[P]` with each other, and
   each says which selector block to locate — the file has duplicated sections
   (`.blog-listing-page` at both `:3184` and `:6864`; `.hero__tagline` declared
   four times), so locate by surrounding selector, never by remembered line number.
2. **`frontend/tests/App.test.tsx`** is edited by US1 (footer) and US3 (routing).
   Sequence them; do not parallelise.

---

## Phase 1: Setup & Preconditions

**Purpose**: a running environment with production-like data, a green baseline, and
the live data facts that two change units depend on.

- [ ] T001 Start the local stack (`./scripts/start.sh`) and restore the latest production backup through the admin Data Ops UI, per `specs/031-ux-top10/quickstart.md` §1 (use the `prod-data-restore` skill; do not run `mongorestore` against prod data directly). Needed for manual verification (T036, T046, T060, T065, T072, T081, T094) and for the e2e run (T097) — **not** a blocker for writing code, since T003/T004 are already satisfied
- [x] T002 [P] Capture the green baseline before touching anything: `cd backend && ../gradlew test`, `cd frontend && npm test && npm run lint && npm run build` — record any pre-existing failures in `specs/031-ux-top10/research.md` so they are not later attributed to this feature
- [x] T003 [P] ~~Record live data preconditions~~ — **DONE.** Read from the production read-only API rather than a local restore (same data, no restore needed) and recorded in `contracts/asset-manifest.md` Part 0: the two GitHub `link` values, all 10 `skill_groups` names, all 10 `jobs` company names. **Two findings that change downstream tasks**: (a) both GitHub links already have distinct names, so V017 is cosmetic rather than corrective — the user-visible defect is fixed by T091 alone; (b) skill ratings are **decimals** (`8.6`, `7.3`, `7.2`, `6.9`), which changes the level-word banding in T073/T074
- [x] T004 [P] ~~Confirm the blog/digest split~~ — **DONE.** 43 published posts, 15 carrying the `Weekly Digest` tag, 28 remainder — matching `spec.md`'s assumption exactly, no adjustment needed. Exactly one tag name matches (`"Weekly Digest"`, that casing) out of 50 distinct tags, so V015's case/whitespace-insensitive matching is defensive rather than load-bearing; keep it, as FR-020 requires it

**Checkpoint**: environment reproducible, baseline known, migration inputs are facts rather than assumptions.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: three shared frontend primitives, plus the blog `contentType` backend.

**⚠️ CRITICAL**: no user story work starts until this phase is complete.

**Why `contentType`'s backend is here and not in US2**: both US1 (Featured writing
needs the three latest *engineering* posts) and US2 (tabs, featured card) read it.
Putting the shared backend in Phase 2 is what makes US1 and US2 genuinely
independent afterwards, rather than US1 secretly depending on US2.

### Shared frontend primitives

- [x] T005 [P] Create `frontend/src/hooks/usePageTitle.ts` — `usePageTitle(pageTitle?: string)`; `undefined` → `Simon Rowe | Software Engineering Leader`, a string → `${pageTitle} · Simon Rowe`. Takes the value as an effect dependency so dynamic titles (profile name, blog post title) update when data arrives, per `research.md` D1
- [x] T006 [P] Create `frontend/tests/hooks/usePageTitle.test.ts` — default title, suffixed title, and re-run when the argument changes from `undefined` to a value
- [x] T007 [P] Create `frontend/src/services/fetchWithRetry.ts` per the contract in `research.md` D2: one retry after ~300 ms on network error or 5xx, **no** retry on 4xx, honours `AbortSignal` (and does not retry after abort), error message = parsed `ErrorResponse.message` → `fallbackMessage` → generic, never the raw `Failed to fetch` text
- [x] T008 [P] Create `frontend/tests/services/fetchWithRetry.test.ts` — retries once on 500 then succeeds; retries once on network throw then succeeds; does **not** retry a 404; surfaces the parsed server message; falls back to `fallbackMessage`; stops on abort; fails after exactly two total attempts (assert the call count, so a future change to unbounded retry breaks the test)
- [x] T009 [P] Add a `title?: string` prop to `frontend/src/components/common/ErrorMessage.tsx` defaulting to `'Something went wrong'`, replacing the hardcoded `<h2>Unable to load homepage</h2>` at `:9`
- [x] T010 [P] Create `frontend/tests/components/common/ErrorMessage.test.tsx` — renders the default heading, renders a supplied heading, renders the retry button only when `onRetry` is given, and invokes `onRetry` on click

### Blog `contentType` backend (shared by US1 + US2)

- [x] T011 Create `backend/src/main/java/com/simonrowe/blog/BlogContentType.java` — `enum BlogContentType { ENGINEERING, DIGEST }` with a javadoc (Checkstyle requires it on public types)
- [x] T012 Append a `BlogContentType contentType` component to **both** `backend/src/main/java/com/simonrowe/blog/Blog.java` and `backend/src/main/java/com/simonrowe/admin/Blog.java` — append at the end of each record, do not attempt to align their differing component orders (`research.md` A5). Then run `../gradlew :backend:compileJava` and fix every broken `new Blog(...)` call site the compiler reports
- [x] T013 Add a `contentType` component to `backend/src/main/java/com/simonrowe/blog/BlogSummaryResponse.java` and `BlogDetailResponse.java`, populated in both `fromEntity` overloads with the null coercion `blog.contentType() == null ? ENGINEERING : blog.contentType()` (`contracts/blogs-api.md`)
- [x] T014 Change `BlogService.getLatest` in `backend/src/main/java/com/simonrowe/blog/BlogService.java:42-50` to `getLatest(int limit, BlogContentType contentType)` — **filter first, then limit**, so asking for 3 engineering posts never returns fewer because digests occupy the top of the list. `contentType == null` means no filtering
- [x] T015 Add `@RequestParam(required = false) BlogContentType contentType` to `getLatestBlogs` in `backend/src/main/java/com/simonrowe/blog/BlogController.java:29-33`, keeping the existing `limit` `@Min(1) @Max(10)` constraints
- [x] T016 Pass `BlogContentType.DIGEST` in the `new Blog(...)` call at `backend/src/main/java/com/simonrowe/agents/WeeklyDigestAgent.java:108-113`
- [x] T017 Create `backend/src/main/java/com/simonrowe/migration/changeunits/V015BackfillBlogContentType.java` — `@ChangeUnit(id = "backfill-blog-content-type", order = "015", author = "simonrowe")`, raw `org.bson.Document` via `MongoTemplate`, following the `V014MakeFavouritesGlobal` house pattern and the algorithm in `data-model.md` §1: collect tag `_id`s whose trimmed lower-cased `name` is `weekly digest`, then for each `blogs` document **missing** `contentType` set `DIGEST` if any `@DBRef` in `tags` has a matching `$id`, else `ENGINEERING`. `@RollbackExecution` `$unset`s the field. Class javadoc must state why it is idempotent. Order `015` is the next free slot (V010 is used twice; highest is 014)
- [x] T018 [P] Add the contract tests from `contracts/blogs-api.md` to `backend/src/test/java/com/simonrowe/blog/BlogControllerTest.java`: list includes `contentType`; a stored `null` serializes as `ENGINEERING`; `?limit=3&contentType=ENGINEERING` returns exactly the 3 engineering posts when 2 newer digests exist; `?limit=3` unfiltered is unchanged; `?contentType=NONSENSE` → 400; `?limit=0` and `?limit=11` → 400; detail includes `contentType`
- [x] T019 [P] Create `backend/src/test/java/com/simonrowe/migration/changeunits/V015BackfillBlogContentTypeTest.java` in the real-Mongo direct-drive style of `V014MakeFavouritesGlobalTest` — `extends AbstractIntegrationTest`, `@Autowired MongoTemplate`, `new V015…().execution(mongoTemplate)`. Cases: tagged post → `DIGEST`; untagged → `ENGINEERING`; tag name `"  weekly digest  "` and `"WEEKLY DIGEST"` both classify as `DIGEST`; a post already carrying `contentType` is untouched; a second run changes nothing. **Must clean up in `@AfterEach`** or it pollutes the shared Testcontainer for the whole suite
- [x] T020 [P] Add `contentType: BlogContentType` (non-optional) and `export type BlogContentType = 'ENGINEERING' | 'DIGEST'` to `frontend/src/types/blog.ts`, and extend `fetchLatestBlogs` in `frontend/src/services/blogApi.ts` to `fetchLatestBlogs(limit = 3, contentType?: BlogContentType)`

**Checkpoint**: shared primitives exist and are tested; the blog API exposes and filters on `contentType`; existing suites still green. All eight stories can now proceed.

---

## Phase 3: User Story 1 — Recruiter lands on the home page (Priority: P1) 🎯 MVP

**Goal**: the home page becomes scrollable and substantive — a current-role summary,
an employer logo row, three recent engineering posts, a contact CTA — and every
public page gains a footer.

**Independent Test**: load `/`, scroll to the bottom, confirm four data-driven
sections plus a footer, every link resolving, in both themes at 1440px and 390px;
then block `GET /api/jobs` and confirm the page still renders with the jobs-backed
sections quietly absent.

### Tests for User Story 1

- [x] T021 [P] [US1] Create `frontend/tests/components/home/CurrentlyStrip.test.tsx` — renders the current role from job data (the job with no `endDate`), returns `null` when no current job is present, and hardcodes no facts
- [x] T022 [P] [US1] Create `frontend/tests/components/home/EmployerLogoStrip.test.tsx` — dedupes by `company`, excludes `isEducation === true` entries, links each logo to `/experience`, renders `null` on empty input, and renders a text fallback for a job with no `companyImage`
- [x] T023 [P] [US1] Create `frontend/tests/components/home/FeaturedWriting.test.tsx` — renders at most 3 posts, renders fewer without placeholders when fewer exist, renders `null` when none exist, and includes a "Read the blog" link to `/blogs`
- [x] T024 [P] [US1] Create `frontend/tests/components/layout/Footer.test.tsx` — brand name + positioning line, nav links to all six public sections, social links and email from profile data, a `Download CV` link to `${API_BASE_URL}/api/resume`, root is `contentinfo`, and it renders its static columns even when the profile fetch fails (never an error frame)
- [x] T025 [P] [US1] Extend `frontend/tests/pages/HomePage.test.tsx` — all four sections render in order below the hero; a rejected `fetchJobs()` drops the two jobs-backed sections without erroring the page; a rejected profile fetch still shows the existing page-level error (unchanged behaviour)

### Implementation for User Story 1

- [x] T026 [P] [US1] Create `frontend/src/components/home/CurrentlyStrip.tsx` — takes jobs + profile as props, derives the current role from the job with no `endDate`, renders 2–3 lines of prose from `profile.headline` and the job's `shortDescription`. No hardcoded facts (FR-005)
- [x] T027 [P] [US1] Create `frontend/src/components/home/EmployerLogoStrip.tsx` — takes jobs as a prop, filters `isEducation !== true`, dedupes by `company`, renders `companyImage.url` at a normalised height wrapped in a `<Link to="/experience">`, with a text fallback when there is no image
- [x] T028 [P] [US1] Create `frontend/src/components/home/FeaturedWriting.tsx` — takes `BlogSummary[]`, reuses the existing `ArticleCard` styling, plus a "Read the blog" link to `/blogs`
- [x] T029 [US1] Rework `frontend/src/components/home/CTASection.tsx` into the contact CTA band: keep the `cta-section tour-contact` root class (it restores the site tour's `.tour-contact` target, which currently has no element on the home page), change `Get In Touch` → `Get in touch` linking `/profile#contact`, and replace `Explore Work` with a `Download CV` link to `${API_BASE_URL}/api/resume` (FR-008)
- [x] T030 [US1] Create `frontend/src/components/layout/Footer.tsx` — markup written **to fit the existing orphaned CSS contract** at `styles.css:645-764` (`.footer__inner`, `__brand`, `__brand-link`, `__brand-mark`, `__summary`, `__group`, `__heading`, `__bar`, `__copyright`, `__link`). Absorbs `ConnectStrip`'s social + CV markup. Calls `useProfile()` itself and renders its static columns immediately; never shows a loading or error state (`research.md` D10)
- [x] T031 [US1] Update `frontend/src/pages/HomePage.tsx` — add a `fetchJobs()` call and a `fetchLatestBlogs(3, 'ENGINEERING')` call, then render `HeroSection`, `CurrentlyStrip`, `EmployerLogoStrip`, `FeaturedWriting`, `CTASection` in that order. Each section receives its data as a prop and returns `null` when absent, so a failed jobs or blogs fetch degrades that section only — the page-level gate stays on profile alone, as today
- [x] T032 [US1] Render `<Footer />` inside `PublicLayout` in `frontend/src/App.tsx:111-132`, after `<main className="app-layout__main">`
- [x] T033 [US1] **Invert the two footer assertions** in `frontend/tests/App.test.tsx:63,72` — they currently assert `queryByRole('contentinfo')` is **absent**. Both must become present-assertions or the suite goes red. (Flagged as the single most likely thing to be missed — `research.md` D10)
- [x] T034 [US1] Edit `frontend/src/styles.css`: add BEM blocks for `.currently-strip`, `.employer-logo-strip`, `.featured-writing` (normalised logo height, legible in both themes via `--on-surface*` / `--surface-container-*` tokens, not hardcoded hex); refresh the existing `.footer` block rather than adding a parallel one (FR-011). `.app-layout` is already `flex-direction:column; min-height:100vh` with `.app-layout__main{flex:1}`, so `.footer{margin-top:auto}` needs no layout change
- [x] T035 [US1] Delete dead code and its CSS (FR-009): `frontend/src/components/home/StatsGrid.tsx` + `styles.css` `.stats-grid*` block; `frontend/src/components/home/AIChatModule.tsx` + the `.chat-module*` block; `frontend/src/components/home/ConnectStrip.tsx`, `frontend/tests/components/home/ConnectStrip.test.tsx`, and the mobile-only `.connect-strip*` block (its content now lives in the footer). Leave `AboutSection.tsx` alone — it is used by `ProfilePage.tsx:4,41`
- [ ] T036 [US1] Manual verification: `/` at 1440px and 390×844, light and dark — four sections plus footer in order, employer logos legible and uncropped in both themes, every link resolves, and `/experience` is reachable from a logo

**Checkpoint**: US1 complete. The headline change is demoable on its own.

---

## Phase 4: User Story 2 — Engineering writing separated from digests (Priority: P1)

**Goal**: the blog listing opens on Engineering, promotes the latest engineering
post, and lets a reader reach digests in one click. Authors and the digest
generator classify posts explicitly.

**Independent Test**: open `/blogs` — Engineering preselected, no digests listed,
featured card is an engineering post; the Weekly Digest tab shows only digests and
All shows both; create a post in the admin editor without touching the field and
confirm it is `ENGINEERING`.

**Depends on**: Phase 2 (the `contentType` backend).

### Tests for User Story 2

- [x] T037 [P] [US2] Create `frontend/tests/components/blog/BlogContentTabs.test.tsx` — three tabs with correct `role="tab"` / `aria-selected` semantics, Engineering active by default, click reports the selected type
- [x] T038 [P] [US2] Extend `frontend/tests/pages/BlogListingPage.test.tsx` — default view lists only `ENGINEERING` posts; the featured card is the newest `ENGINEERING` post even when a `DIGEST` post is newer; the Weekly Digest tab lists only digests; All lists both; an empty active tab renders an empty state rather than an empty featured frame; and no engineering posts at all does not crash or fall back to a digest
- [ ] T039 [P] [US2] Add the admin contract tests from `contracts/admin-blogs-api.md` to `backend/src/test/java/com/simonrowe/admin/AdminBlogControllerTest.java` — create without the field defaults to `ENGINEERING`; create with `DIGEST` round-trips; blank coerces to `ENGINEERING`; an unrecognised value → 400; update changes the value; **update omitting the field preserves the stored value** (a `DIGEST` post must not be silently reclassified by an admin edit); list responses include the field

### Implementation for User Story 2

- [x] T040 [P] [US2] Create `frontend/src/components/blog/BlogContentTabs.tsx` — All · Engineering · Weekly Digest, using `role="tablist"`/`role="tab"` with `aria-selected`, styled with the existing `.chip` / `.chip--active` classes. Purpose-built rather than adapting `CategoryFilters.tsx`, which is a chip row plus a tag search input the blog listing does not have (`research.md` D5)
- [x] T041 [US2] Update `frontend/src/pages/BlogListingPage.tsx` — render `BlogContentTabs` defaulting to Engineering, filter the fetched list client-side on `contentType` (~43 posts, `GET /api/blogs` is unpaged so there is no server paging to fight), replace the positional `blogs[0]` featured pick at `:28` with "first post whose `contentType` is `ENGINEERING`", and add the empty state the page currently lacks
- [x] T042 [P] [US2] Change the card CTA copy to "Read post" — `frontend/src/components/blog/ArticleCard.tsx:34-36` (`View Post →`) and `frontend/src/components/blog/FeaturedArticle.tsx:33` (`Read Detailed Analysis →`) — and update any test asserting the old strings
- [x] T043 [US2] Handle `contentType` in `backend/src/main/java/com/simonrowe/admin/AdminBlogController.java`: parse it from the raw `Map` body with `POST` absent/blank → `ENGINEERING` but `PUT` absent → **preserve the stored value**; reject an unrecognised non-blank value in the validation block at `:199-240`; add it to `toDto` at `:160-177` so the editor round-trips
- [x] T044 [US2] Add a `Content type` `<select>` to the blog editor's left column, below Short Description, per `contracts/admin-blogs-api.md` — options Engineering / Weekly Digest, `ENGINEERING` preselected for a new post (not an empty option, so the default is visible)
- [x] T045 [US2] Add a frontend test for the admin editor select — default selection on a new post, round-trip of a `DIGEST` post, and that saving includes the field in the request body
- [ ] T046 [US2] Verify against the restored data: `curl -s localhost:8080/api/blogs | jq '[.[].contentType] | group_by(.) | map({(.[0]): length}) | add'` matches the counts recorded in T004, and restarting the backend leaves them identical (V015 idempotency)

**Checkpoint**: US1 and US2 both work independently.

---

## Phase 5: User Story 3 — No visitor hits a dead end (Priority: P1)

**Goal**: `/blog` and `/blog/:id` redirect to their canonical routes; every unknown
URL renders a friendly 404 inside the normal chrome.

**Independent Test**: visit `/blog`, `/blog/<real id>` and `/nonsense`; each ends
somewhere useful, and the Back button from a redirect leaves the site rather than
bouncing.

### Tests for User Story 3

- [x] T047 [P] [US3] Create `frontend/tests/pages/NotFoundPage.test.tsx` — heading, friendly line, links to `/` and `/blogs`, and a 404-appropriate document title
- [x] T048 [US3] Extend `frontend/tests/App.test.tsx` (sequence after T033 — same file) — `/blog` renders the blog listing, `/blog/:id` renders the blog detail, an unknown path renders the 404 page, and the 404 renders inside `PublicLayout` (nav and footer present)
- [x] T049 [P] [US3] Create `frontend/e2e/routing.local.spec.ts` in the `local` project (`testMatch: /\.local\.spec\.ts$/`) — `/blog` lands on the listing, an unknown URL shows the 404 page, and `/blogs` defaults to the Engineering tab. Note there is no `webServer` block in `playwright.config.ts`, so the stack from T001 must be running

### Implementation for User Story 3

- [x] T050 [P] [US3] Create `frontend/src/pages/NotFoundPage.tsx` — "Page not found" heading, one friendly line, links to `/` and `/blogs`, and `usePageTitle('Page not found')`
- [x] T051 [US3] In `frontend/src/App.tsx`: add `<Route path="/blog" element={<Navigate to="/blogs" replace />} />` and `<Route path="/blog/:id" element={…redirect preserving the id…} />`, plus a lazy-loaded catch-all `<Route path="*">` rendering `NotFoundPage` inside `PublicLayout`. Use the existing `named()` lazy helper at `:26-27`; keep the 404 lazy so it stays out of the initial bundle
- [x] T052 [P] [US3] Change the default title in `frontend/index.html:6` from `Simon Rowe | Full Stack Developer` to `Simon Rowe | Software Engineering Leader` (FR-003)
- [x] T053 [US3] Add a `.not-found-page` BEM block to `frontend/src/styles.css` (sequence with other `styles.css` tasks)

**Checkpoint**: no URL dead-ends. US1–US3 (all P1) complete — this is the natural first deployable slice.

---

## Phase 6: User Story 4 — Failures explain themselves and can be retried (Priority: P2)

**Goal**: one retrying fetch path, correct error headings everywhere, a retry action
on every page.

**Independent Test**: with the backend stopped, load every public page and confirm a
correctly-titled error frame with a working Retry; restart and press Retry to
recover without a page reload.

**Depends on**: Phase 2 (`fetchWithRetry`, `ErrorMessage` title).

### Tests for User Story 4

- [x] T054 [P] [US4] Add per-service tests under `frontend/tests/services/` asserting each migrated service routes through `fetchWithRetry` and surfaces its own fallback message
- [x] T055 [P] [US4] Extend the page tests for `BlogListingPage`, `BlogDetailPage`, `NewsEventsPage`, `McpPage` and `ExperiencePage` — each renders a page-appropriate error heading (never "Unable to load homepage") and a Retry button that reissues only the failed request and clears the error on success
- [x] T056 [P] [US4] Extend `frontend/tests/components/skills/SkillGroupGrid.test.tsx` — failure renders `ErrorMessage` with a retry action instead of the current bare `<p className="skill-group-grid__error">`

### Implementation for User Story 4

- [x] T057 [US4] Migrate services to `fetchWithRetry`, **deleting** the duplicated helpers: the three byte-identical `handleResponse` copies (`blogApi.ts:7-21`, `newsApi.ts:6-20`, `eventsApi.ts:6-20`), the two `parseErrorMessage` copies (`jobsApi.ts:4-15`, `skillsApi.ts:4-15`), the inlined third in `profileApi.ts:6-25`, and the bare `throw new Error` paths in `tourApi.ts`, `searchApi.ts:37,51` and `codeExampleApi.ts:14-18`. **Explicitly out of scope**: `contactApi.ts` (keeps `ContactApiError` for field-level errors, and a POST must not be silently retried), plus `adminApi.ts`, `dataOperationsApi.ts`, `favouritesApi.ts` (bearer-token `authFetch` wrappers) and `chatService.ts` (STOMP)
- [x] T058 [US4] Add `title` and `onRetry` to every public page's `ErrorMessage` usage: `BlogListingPage.tsx:36`, `BlogDetailPage.tsx:56`, `NewsEventsPage.tsx:89` and `McpPage.tsx:67` currently pass no `onRetry`, and `ExperiencePage.tsx` has no error handling at all. Add the retry mechanism to each page's local state machine (an attempt counter in the effect dependency array, as `useProfile.ts:51-53` already does)
- [x] T059 [US4] Replace the bare error paragraph in `frontend/src/components/skills/SkillGroupGrid.tsx:58-60` with `ErrorMessage` carrying a title and retry, and remove the hardcoded `#dc2626` `.skill-group-grid__error` rule from `styles.css:2604-2607` (sequence with other `styles.css` tasks)
- [ ] T060 [US4] Manual verification with the backend stopped: every public page shows a correct heading and a working Retry; then throttle a single `/api/blogs` request to fail once and confirm silent automatic recovery with no error shown

**Checkpoint**: transient failures are invisible; real failures are legible and recoverable.

---

## Phase 7: User Story 5 — Mobile visitors see the pitch (Priority: P2)

**Goal**: the mobile hero shows the badge, the tagline and two prompt chips, with
the chat input inside the first screen.

**Independent Test**: load `/` at 390×844 — badge and tagline visible, tagline on
one line, exactly two chips, chat input above the fold, no large dead space.

### Tests for User Story 5

- [x] T061 [US5] **Invert the mobile assertions** in `frontend/src/components/home/HeroSection.test.tsx:51-71` — they currently assert mobile *hides* the badge, tagline and chips. Assert instead that badge and tagline are present and that **exactly two** chips render at mobile width, while desktop still renders all four. (The second of the two must-invert tests — `research.md` D15)

### Implementation for User Story 5

- [x] T062 [US5] In `frontend/src/components/home/HeroSection.tsx`, drop the `!isMobile &&` guards on the badge (`:47`) and tagline (`:50`), and render `SUGGESTED_PROMPTS.slice(0, isMobile ? 2 : 4)` in the chips block (`:76-89`). Keep `useMediaQuery` — it is still needed for the chip count and the textarea rows
- [x] T063 [US5] Reduce the `<textarea rows={6}>` at `HeroSection.tsx:58-74` at mobile widths — it is the main consumer of vertical space and the cheapest lever for FR-025
- [x] T064 [US5] Consolidate the mobile hero CSS in `frontend/src/styles.css` (sequence with other `styles.css` tasks): `.hero__tagline` is declared four times (`:981` base, `:1127` light theme, `:1165` and `:1177` both inside the same `max-width:768px` block, `:1832` in a second responsive block). The `:1177` 1-line clamp was dead because JS removed the element and now becomes live — keep exactly one mobile declaration and delete the redundant ones, then tighten the mobile `.hero` vertical rhythm
- [ ] T065 [US5] Manual verification at 390×844 in both themes: badge and one-line tagline visible, two chips, chat input inside the first screen, tapping a chip opens the chat with that prompt; and confirm the desktop hero is visually unchanged (FR-026)

**Checkpoint**: mobile and desktop communicate the same thing.

---

## Phase 8: User Story 6 — News & Events pages and filters correctly (Priority: P2)

**Goal**: 24 articles on first paint, "Load more" for the rest, and source chips
that list every source and re-query the backend.

**Independent Test**: `/news-events` requests `size=24`; Load more appends and
disappears on the last page; a chip issues a fresh `page=0&source=` request; the
sources endpoint lists sources absent from page 0.

### Tests for User Story 6

- [x] T066 [P] [US6] Add the `/api/news/sources` contract tests from `contracts/news-api.md` to `backend/src/test/java/com/simonrowe/aggregation/NewsControllerTest.java` — distinct names from duplicated sources; alphabetical order; `visible == false` articles contribute nothing; no articles → `[]` with 200; and `/api/news/sources` is **not** shadowed by the existing `@GetMapping("/{id}")` at `:47` (that failure mode would surface as a confusing 404)
- [x] T067 [P] [US6] Extend `frontend/tests/pages/NewsEventsPage.test.tsx` per `contracts/news-api.md` — initial call is `fetchNews(0, 24, undefined)`; Load more appends without dropping earlier articles; the button is hidden when `last` is true; a chip calls `fetchNews(0, 24, 'InfoQ')` rather than filtering in memory; chips render source names absent from page 0; a stale page-2 response arriving after a source switch is discarded; and favourites behave exactly as before (FR-040)

### Implementation for User Story 6

- [x] T068 [P] [US6] Add `GET /api/news/sources` to `backend/src/main/java/com/simonrowe/aggregation/NewsController.java` returning a sorted, distinct `List<String>` of `sourceName` for visible articles, via `MongoTemplate.findDistinct` (Spring Data has no derived projection for a distinct scalar)
- [x] T069 [P] [US6] Add `fetchNewsSources(): Promise<string[]>` to `frontend/src/services/newsApi.ts`, routed through `fetchWithRetry`
- [x] T070 [US6] Rework `frontend/src/pages/NewsEventsPage.tsx`: replace the single `fetchNews(0, 100)` at `:50-63` with `fetchNews(0, 24, activeSource)`; hold `{ articles, page, last, loadingMore }`; append on Load more and hide the button when `last`; source a chip list from `fetchNewsSources()` instead of `[...new Set(articles.map(a => a.sourceName))]` at `:92`; reset to page 0 on chip selection. Guard against interleaving with a monotonic request id in a ref, discarding any response that is not the latest. Keep `'all'` and the `'events'` pseudo-source as local-only values (`:100-106`) — only a real source name becomes a query parameter — and leave the favourites path at `:65-81` filtering in memory
- [x] T071 [US6] Add a `.feed__load-more` BEM block to `frontend/src/styles.css` (sequence with other `styles.css` tasks)
- [ ] T072 [US6] Manual verification: devtools shows `size=24` not `size=100`; Load more appends and preserves scroll position; the button disappears on the last page; `curl -s localhost:8080/api/news/sources | jq` lists every source; favourites unchanged

**Checkpoint**: all P1 and P2 stories complete.

---

## Phase 9: User Story 7 — Skills and icons look deliberate (Priority: P3)

**Goal**: every rating carries a level word matching its bar; every skill group and
employer resolves to a sharp mark from one approved set.

**Independent Test**: `/experience` — spot-check ratings of 9, 7, 5 and 3 against
Expert/Advanced/Proficient/Familiar, confirm the accessible description matches, and
confirm every group icon and employer logo is sharp and consistent in both themes.

**⚠️ Contains the one blocking human gate in the feature (T075).**

### Tests for User Story 7

- [x] T073 [P] [US7] Create `frontend/tests/components/skills/SkillRatingBar.test.tsx` — **decimal** boundary values, because live ratings are decimals (see below): `10 → Expert`, `9 → Expert`, `8.9 → Advanced`, `8.6 → Advanced`, `7 → Advanced`, `6.9 → Proficient`, `5 → Proficient`, `4.9 → Familiar`, `0 → Familiar`. The accessible label must contain the same word as the visible text (FR-028), and out-of-range or null ratings must still render safely

### Implementation for User Story 7

- [x] T074 [P] [US7] Update `frontend/src/components/skills/SkillRatingBar.tsx` — add a pure `skillLevel(rating)` mapping rendered as a `<span class="skill-rating-bar__level">`. **Bands must be defined on continuous values, not integer sets**: `>= 9` Expert, `>= 7` Advanced, `>= 5` Proficient, else Familiar. Live skill-group ratings are decimals (`8.6`, `7.3`, `7.2`, `6.9` — see `contracts/asset-manifest.md` Part 0), so the design doc's "9–10 / 7–8 / 5–6" phrasing would leave `6.9` and `8.6` unclassified. Restructure the markup so the bar track keeps `role="progressbar"` and the level word is a **sibling outside it** (text inside a `progressbar` role is invalid), and change the label to `${skillName} proficiency: ${level} (${rating} out of 10)`. Move the inline `backgroundColor: 'var(--primary)'` at `:21` into CSS
- [ ] T075 [US7] 🚦 **BLOCKING HUMAN GATE (FR-032)** — assemble the proposed icon and logo set from `contracts/asset-manifest.md`, render it as a preview grid in **both light and dark themes**, present it, and get explicit approval. Do not write T076–T078 until approval is given. Flag any employer logo that needs a monochrome treatment for dark-theme legibility, and any for which only a raster asset exists
- [ ] T076 [US7] Add the **approved** SVGs to `backend/src/main/resources/media/icons/`, named to match the manifest's deterministic `legacyId` slugs (`icon:<skill-group-slug>`, `logo:<company-slug>`)
- [ ] T077 [US7] Create `backend/src/main/java/com/simonrowe/migration/changeunits/V016InstallSkillAndCompanyIcons.java` — `@ChangeUnit(id = "install-skill-and-company-icons", order = "016", author = "simonrowe")`. Per `data-model.md` §2: read each bundled SVG via `ClassPathResource`, look it up by `legacyId` first and reuse the existing asset id if present, otherwise write `{uploads.path}/{assetId}/original.svg` and insert a `media_assets` row (`mimeType` `image/svg+xml`, `variants` `{}`, the deterministic `legacyId`); then set `skill_groups.image` / `jobs.companyImage` to a `common.Image` sub-document whose `url` is the `originalPath`, matching on trimmed case-insensitive `name` / `company` using the values recorded in T003. Never null out an existing image for an unmatched document. `@RollbackExecution` deletes the rows it created (by `legacyId` prefix) and `$unset`s the fields it set
- [ ] T078 [US7] Create `backend/src/test/java/com/simonrowe/migration/changeunits/V016InstallSkillAndCompanyIconsTest.java` (real-Mongo direct-drive, `@AfterEach` cleanup, `uploads.path` pointing at `target/test-uploads` as the test profile already does) — creates assets and repoints references; a second run creates no duplicate rows and leaves ids unchanged; an unmatched skill group or job keeps its existing image; and the written `Image.url` equals the asset's `originalPath`
- [x] T079 [US7] Add `.skill-rating-bar__level` to `frontend/src/styles.css` and replace the hardcoded non-theme-aware track colour `#e5e7eb` at `:2507` with a token (sequence with other `styles.css` tasks)
- [ ] T080 [US7] Verify GraalVM native-image resource reachability: `cd backend && ../gradlew bootBuildImage`, then confirm the bundled SVGs still resolve from the resulting container. Spring Boot AOT is expected to register `src/main/resources` patterns, but this must be proven rather than assumed (`research.md` D12)
- [ ] T081 [US7] Manual verification: every skill group has an icon and every employer a sharp uncropped logo, in both themes, on `/experience` and in the home logo strip; and no skill group falls back to its first-letter placeholder

**Checkpoint**: the profile reads as credible.

---

## Phase 10: User Story 8 — Chrome and copy read as one product (Priority: P3)

**Goal**: solid primary buttons, one name for the contact action, an admin link only
for admins, distinct social labels, and useful page titles.

**Independent Test**: walk every public page in both themes checking button
contrast, the single contact-action name, absence of the admin link when signed
out, distinct GitHub labels, and a page-identifying tab title.

### Tests for User Story 8

- [x] T082 [P] [US8] Add tests for `frontend/src/components/layout/TopNav.tsx` and `MobileMenu.tsx` — the admin entry is absent when `useAdminRole()` is false and present when true, and the public links are unaffected either way
- [x] T083 [P] [US8] Extend `frontend/tests/components/profile/SocialLinks.test.tsx` — a link with a `name` renders that name; a link without one falls back to the type label; and two GitHub links with distinct names render distinctly
- [x] T084 [P] [US8] Extend `frontend/tests/components/contact/ContactForm.test.tsx` — the submit reads "Send message", and the success and failure messages use the same verb
- [x] T085 [P] [US8] Add a `usePageTitle` assertion to each page test — `document.title` is the expected page-and-site string for all seven routed pages plus the 404
- [x] T086 [P] [US8] Create `backend/src/test/java/com/simonrowe/migration/changeunits/V017NameGithubSocialLinksTest.java` (real-Mongo direct-drive, `@AfterEach` cleanup) — both GitHub documents get their target names; a second run changes nothing; a document already carrying the target name is not rewritten; non-GitHub links are untouched

### Implementation for User Story 8

- [x] T087 [US8] In `frontend/src/styles.css`, replace `.button--primary`'s `linear-gradient(135deg, var(--primary), var(--primary-container))` at `:248-253` with a solid `var(--primary)` fill and `var(--on-primary)` text, keep the hover lift from `.button:hover` (`:239-242`), replace the hardcoded `box-shadow: 0 4px 12px rgba(119,209,255,0.1)` with a token, and add the `:focus-visible` style the modifier currently lacks (sequence with other `styles.css` tasks)
- [x] T088 [P] [US8] Change the contact submit label at `frontend/src/components/contact/ContactForm.tsx` (~`:132`) from `Initiate Connection →` to `Send message`, and align the success and failure copy to the same verb
- [x] T089 [P] [US8] Sweep for contact-action copy variants and settle on "Get in touch" in sentence case everywhere (`ContactSection`, `ContactDetails`, `CTASection`, nav and footer) — `grep -ri "get in touch\|initiate connection" frontend/src`
- [x] T090 [US8] Gate the admin nav entries on `useAdminRole()`: the `UserCircle` `NavLink` at `frontend/src/components/layout/TopNav.tsx:39-41` and the `{ label: 'Admin', to: '/admin' }` entry at `frontend/src/components/layout/MobileMenu.tsx:14`. Add the `aria-label` the TopNav link currently lacks. Leave the sign-in flow alone — `AdminLayout.tsx:46-58` still redirects unauthenticated visitors on `/admin`
- [x] T091 [P] [US8] Invert the label fallback in `frontend/src/components/profile/SocialLinks.tsx:34,41` from `platformLabels[link.type] ?? link.name` to `link.name ?? platformLabels[link.type]`
- [x] T092 [US8] Create `backend/src/main/java/com/simonrowe/migration/changeunits/V017NameGithubSocialLinks.java` — `@ChangeUnit(id = "name-github-social-links", order = "017", author = "simonrowe")`. Set `name` to "GitHub — personal" / "GitHub — this site" on the two `social_medias` documents with `type == "github"`, distinguished by the exact `link` values recorded in T003. Guard on `name != target` for idempotency. Note the entity field is `link` while the API exposes it as `url` (`data-model.md` §3). `@RollbackExecution` `$unset`s `name` on the two matched documents
- [x] T093 [US8] Replace the imperative `document.title` effects with `usePageTitle` in all seven pages — `HomePage.tsx:18`, `ProfilePage.tsx:22`, `ExperiencePage.tsx:22`, `BlogListingPage.tsx:18`, `BlogDetailPage.tsx:32`, `NewsEventsPage.tsx:47`, `McpPage.tsx:24`. Three of these currently omit the site name entirely (`'Blog'`, `'Experience & Skills'`, `'News & Events'`); the home page keeps the bare site title
- [ ] T094 [US8] Manual verification in both themes: primary button text meets WCAG AA against its fill (measure, don't eyeball); signed out shows no admin entry on desktop or mobile while `/admin` still redirects to Auth0; signed in as `admin@simonrowe.dev` the entry appears; the two GitHub links read distinctly; every tab title identifies page and site

**Checkpoint**: all eight stories complete.

---

## Phase 11: Polish & Cross-Cutting

- [x] T095 Run the backend gates: `cd backend && ../gradlew test`, `../gradlew :backend:checkstyleMain :backend:checkstyleTest` (`maxWarnings = 0` — expect `final` parameters, javadoc on public types, 100-column limits), and `../gradlew :backend:jacocoTestCoverageVerification` (minimum 0.78; `migration/**` and `WeeklyDigestAgent*` are excluded, so the new `blog/` and `aggregation/` code carries the coverage)
- [x] T096 Run the frontend gates: `cd frontend && npm test`, `npm run lint`, `npm run build` (`tsc -b` catches type errors the tests miss)
- [ ] T097 Run `npm run e2e` against the stack from T001
- [ ] T098 Walk the full manual checklist in `specs/031-ux-top10/quickstart.md` §3 — all eight stories, both themes, 1440px and 390×844
- [ ] T099 Confirm every acceptance scenario in `spec.md` and every SC-001…SC-018 success criterion is satisfied, and note any that are not
- [ ] T100 [P] Add a `Recent Changes` entry and any needed `Active Technologies` line to `CLAUDE.md` **by hand** — `update-agent-context.sh` corrupts the manual-additions block and drops prior entries when run against this repo (verified during planning), so do not use it
- [ ] T101 [P] *(Optional, explicitly out of FR-009's scope — do only if the PR is still comfortably reviewable)* Sweep the dead code outside `components/home/`: unrouted `pages/NewsPage.tsx` and `pages/EventsPage.tsx`; test-only `blog/BlogGrid.tsx`, `blog/HomepageBlogPreview.tsx`, `blog/BlogSearch.tsx`; zero-importer `blog/CategoryFilters.tsx`; and `components/layout/ScrollToTop.tsx`, shadowed by the local `ScrollToTop` in `App.tsx:55-61`. Also the duplicate `BlogSearchResult` type in `services/searchApi.ts:7` vs `types/blog.ts:31`
- [ ] T102 Open the PR: `git push -u origin simonrowe/ux-review-simonrowe-dev && gh pr create --base main`, with a description covering all ten items, before/after screenshots in both themes, and an explicit note that three Mongock change units run on deploy. Take a production backup (`prod-backup-ops`) before merging

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1 (Setup)** — no dependencies. T003 and T004 are hard prerequisites for T077 and T092 (the change units must not guess live data).
- **Phase 2 (Foundational)** — depends on Phase 1. **Blocks every story.**
- **Phases 3–10 (Stories)** — all depend on Phase 2 only. Any subset can ship.
- **Phase 11 (Polish)** — depends on whichever stories are included.

### Story dependencies

| Story | Depends on | Notes |
|---|---|---|
| US1 (P1) | Phase 2 | Featured writing needs `fetchLatestBlogs(3, 'ENGINEERING')` from T014/T020 |
| US2 (P1) | Phase 2 | Frontend + admin only; the shared backend is in Phase 2 |
| US3 (P1) | Phase 2 (T005 for the 404 title) | T048 edits `App.test.tsx` — **sequence after T033** |
| US4 (P2) | Phase 2 (T007–T010) | Independent of all other stories |
| US5 (P2) | — | Fully independent |
| US6 (P2) | Phase 2 (T007 for the new service call) | Independent |
| US7 (P3) | Phase 2 | **T075 is a blocking human gate** — T076–T078 wait on it |
| US8 (P3) | Phase 2 (T005 for `usePageTitle`) | Independent |

### The two file-level serialisation constraints

- **`frontend/src/styles.css`**: T034 (US1), T053 (US3), T059 (US4), T064 (US5), T071 (US6), T079 (US7), T087 (US8) all edit it. Run them one at a time, in story order.
- **`frontend/tests/App.test.tsx`**: T033 (US1) then T048 (US3).

### Parallel opportunities

- Phase 1: T002, T003, T004 together.
- Phase 2: T005–T010 all together (six distinct new files). The backend chain T011 → T012 → T013 → T014 → T015 is sequential (records before DTOs before service before controller); T016–T017 follow T012; T018–T020 are parallel afterwards.
- Within each story: all test-writing tasks marked `[P]` together, then the new-component tasks marked `[P]` together.
- Across stories: with more than one person, US4, US5, US6 and US8 are fully independent of each other once Phase 2 lands — subject only to the `styles.css` constraint above.

---

## Parallel Example: Phase 2 primitives

```bash
# Six independent new files — launch together:
Task: "Create frontend/src/hooks/usePageTitle.ts"
Task: "Create frontend/tests/hooks/usePageTitle.test.ts"
Task: "Create frontend/src/services/fetchWithRetry.ts"
Task: "Create frontend/tests/services/fetchWithRetry.test.ts"
Task: "Add title prop to frontend/src/components/common/ErrorMessage.tsx"
Task: "Create frontend/tests/components/common/ErrorMessage.test.tsx"
```

## Parallel Example: User Story 1 components

```bash
# Three independent new components:
Task: "Create frontend/src/components/home/CurrentlyStrip.tsx"
Task: "Create frontend/src/components/home/EmployerLogoStrip.tsx"
Task: "Create frontend/src/components/home/FeaturedWriting.tsx"
# ...but T034 (styles.css) and T031 (HomePage composition) must follow, serially.
```

---

## Implementation Strategy

### MVP (US1 alone)

Phase 1 → Phase 2 → Phase 3. Stop and validate. The home page going from one
viewport to a real page is the single most valuable change and demos on its own.

### Recommended first deployable slice (all P1)

Phase 1 → Phase 2 → US1 → US2 → US3. This covers the three problems that actively
cost visitors: an empty home page, digests masquerading as engineering writing, and
dead-end URLs. Everything after that is improvement rather than repair.

### Then, in priority order

US4 → US5 → US6 (P2), then US7 → US8 (P3). US7 is deliberately late because it is
the only story that stops for a human approval gate.

### If parallelising

One person takes Phase 2, then US1 + US2 (the two largest). A second takes
US4 + US6 (both service-layer). A third takes US5 + US8 (both small and
independent). US7 goes to whoever can present the icon preview soonest, since the
gate is wall-clock rather than effort. Coordinate `styles.css` edits through a
single sequence.

---

## Notes

- `[P]` = different files, no dependency on incomplete work.
- Commit after each task or logical group; every story is independently revertable.
- **Two existing tests must be inverted, not extended** — T033 (`App.test.tsx`
  contentinfo) and T061 (`HeroSection.test.tsx` mobile). These are the most likely
  sources of a surprise red suite.
- **Any change-unit test that writes to Mongo must clean up in `@AfterEach`** or it
  pollutes the shared Testcontainer for the entire backend suite.
- Adding a component to a Java `record` is compile-checked — the compiler enumerates
  every call site, so T012 cannot silently miss one.
- Locate `styles.css` edits by surrounding selector, never by remembered line
  number: the file has duplicated sections and several selectors declared four times.

**Totals**: 102 tasks — 4 setup, 16 foundational, 16 US1, 10 US2, 7 US3, 7 US4,
5 US5, 7 US6, 9 US7, 13 US8, 8 polish. One blocking human gate (T075).
