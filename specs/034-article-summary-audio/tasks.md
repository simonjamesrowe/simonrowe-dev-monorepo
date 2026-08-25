---

description: "Task list for on-demand article summaries with audio"
---

# Tasks: On-demand article summaries with audio

**Input**: Design documents from `/specs/034-article-summary-audio/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/article-summary-api.yaml](./contracts/article-summary-api.yaml), [quickstart.md](./quickstart.md)

**Authoritative design**: `docs/superpowers/specs/2026-08-24-article-summary-audio-design.md`

**Tests**: Included. The spec has an explicit Testing section naming eleven required
scenarios, and Constitution Principle III makes quality gates non-negotiable.

**Organization**: Grouped by user story so each is independently implementable and
testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: `[US1]`–`[US4]`, mapping to the user stories in spec.md

## Path Conventions

Web app monorepo: `backend/src/main/java/com/simonrowe/…`,
`backend/src/test/java/com/simonrowe/…`, `frontend/src/…`, `frontend/tests/…`.

## Phase ordering note

Both User Story 2 (audio) and User Story 3 (existing-summary labels) are P2. US3 is
sequenced first: it is much cheaper, it completes the *reading* experience US1 starts, and
US2 depends on a large behaviour-preserving refactor of the narration package that has no
bearing on US3. US4 (P3) is sequenced last as the spec prioritises it, but note that the
insert-first dedup guard it depends on is implemented as part of US1's service — there is no
sane way to write the `POST` without it. US4's phase adds the *stale reclaim* and the
concurrency proof.

This maps to the plan's four increments as: **A** → Phase 2, **C** → Phases 3–4 + 6,
**B + D** → Phase 5.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Configuration and rate limiting, so nothing later has to invent property names.

- [X] T001 Add the `aggregation.summary` block (`model: ${ARTICLE_SUMMARY_MODEL:gpt-5.6-luna}`, `generation-timeout: 3m`, `max-source-chars: 12000`) and the `rate-limit.summary.requests-per-minute: ${SUMMARY_RATE_LIMIT_REQUESTS_PER_MINUTE:5}` entry to `backend/src/main/resources/application.yml`, alongside the existing `aggregation.digest` and `rate-limit.narration` blocks
- [X] T002 Add a `BucketConfig summary` component to the `RateLimitConfig` record in `backend/src/main/java/com/simonrowe/ratelimit/RateLimitConfig.java` — this changes the canonical constructor arity, so expect `RateLimitInterceptorTest` to break at compile time and fix its five-ish `new RateLimitConfig(...)` call sites
- [X] T003 Add an `isSummaryPath(String)` predicate and a `summaryBuckets` map to `backend/src/main/java/com/simonrowe/ratelimit/RateLimitInterceptor.java`, matching `/api/news/{id}/summary` and `/api/news/{id}/summary/narration`. **Test the narration suffix before the bare-summary suffix** — the two paths are prefix-overlapping, so a naive `endsWith("/summary")` check ordered first would never see the narration path
- [X] T004 Register `/api/news/*/summary` and `/api/news/*/summary/narration` in `WebConfig.addInterceptors` in `backend/src/main/java/com/simonrowe/WebConfig.java`, next to the existing `/api/blogs/*/narration` pattern
- [X] T005 [P] Extend `backend/src/test/java/com/simonrowe/ratelimit/RateLimitInterceptorTest.java` with cases proving both summary paths land in the summary bucket, that the narration-suffixed path is not misrouted to the bare-summary bucket, and that a 429 carries `Retry-After`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The source-text component, the data layer, indexes, security and data-ops
registration. Every user story depends on this phase.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Source text extraction (plan Increment A — pure refactor, zero behaviour change)

- [X] T006 Create `backend/src/main/java/com/simonrowe/aggregation/ArticleSourceTextProvider.java` as a `@Component` taking `SitemapHtmlScraper`, moving `ArticleSectionWriter`'s private `sourceTextFor` cascade verbatim: fresh scrape if it clears `MIN_USABLE_SOURCE_CHARS` (500), else stored `fullContent` if it clears it, else the longest of (scrape, `fullContent`, stored `summary`), truncated to `MAX_SOURCE_CHARS` (12,000). Expose `HARD_MIN_SOURCE_CHARS` (200) as a public constant and carry across all three javadoc comments explaining the paywall/consent-wall reasoning — that is the knowledge being preserved
- [X] T007 Change `backend/src/main/java/com/simonrowe/agents/ArticleSectionWriter.java` to inject `ArticleSourceTextProvider` and delete its own `sourceTextFor`, `clearsFloor`, `longestOf`, `truncate` and the three length constants. Its `write` method's behaviour, including the `HARD_MIN_SOURCE_CHARS` short-circuit to `fallbackSection`, must be unchanged
- [X] T008 [P] Create `backend/src/test/java/com/simonrowe/aggregation/ArticleSourceTextProviderTest.java` covering: scrape over the usable floor wins; scrape under it falls through to `fullContent`; both under it falls through to the longest of the three; over-length text is truncated to 12,000; a null scrape is tolerated
- [X] T009 Run `cd backend && ../gradlew test --tests '*ArticleSectionWriterTest' --tests '*WeeklyDigestAgentTest' --tests '*DigestComposerTest'` and confirm they pass **with no assertion edits** — that is the regression gate for T006/T007

### Data layer

- [X] T010 [P] Create `backend/src/main/java/com/simonrowe/summary/SummaryStatus.java` — `enum SummaryStatus { GENERATING, READY, FAILED }`
- [X] T011 [P] Create `backend/src/main/java/com/simonrowe/summary/ArticleSummary.java` as a mutable `@Document(collection = "article_summaries")` class (not a record — the flow transitions it in place, as with `Narration`) with the fields in data-model.md, a private no-arg constructor for Spring Data, a public constructor setting `status = GENERATING`, `version = 1`, `requestedAt`/`updatedAt = now`, accessor methods in the codebase's no-`get` prefix style, and `markReady(body, model, sourceCharacterCount, now)` / `markFailed(code, retryable, now)` / `markGenerating(now)` mutators that all bump `version` and `updatedAt` via a private `touch(now)`
- [X] T012 [P] Create `backend/src/main/java/com/simonrowe/summary/ArticleSummaryRepository.java` extending `MongoRepository<ArticleSummary, String>` with `Optional<ArticleSummary> findByArticleId(String)` and `List<ArticleSummary> findByStatus(SummaryStatus)`
- [X] T013 [P] Create `backend/src/main/java/com/simonrowe/summary/ArticleSummaryResponse.java` as a `@JsonInclude(NON_NULL)` record with a `PublicState` enum (`NOT_REQUESTED, GENERATING, READY, FAILED`), static `notRequested()`, static `from(ArticleSummary)`, and an `isTerminal()` returning false only for `GENERATING`. Model it on `NarrationResponse`, and add a javadoc note that there is deliberately no `UNAVAILABLE` because the chat model is a hard dependency of the running application

### Indexes and migration

- [X] T014 Create `backend/src/main/java/com/simonrowe/migration/changeunits/V020CreateArticleSummaryIndexes.java` — `@ChangeUnit(id = "create-article-summary-indexes", order = "020", author = "simonrowe")` creating `idx_article_summary_article` on `{articleId: 1}` and `idx_article_summary_status_article` on `{status: 1, articleId: 1}`, with a `@RollbackExecution` dropping both. Follow `V013CreateFavouritesUniqueIndex`, including its javadoc note that auto-index-creation is off so `@Indexed` alone is decorative
- [X] T015 [P] Create `backend/src/test/java/com/simonrowe/migration/V020CreateArticleSummaryIndexesTest.java` asserting both indexes exist after execution and that a second execution is a no-op (index creation is idempotent)

### Security

- [X] T016 Add `.requestMatchers(HttpMethod.POST, "/api/news/*/summary").authenticated()` and `.requestMatchers(HttpMethod.POST, "/api/news/*/summary/narration").authenticated()` to `backend/src/main/java/com/simonrowe/auth/SecurityConfig.java`, immediately after the favourites matchers, with a comment recording that reads are public because the artefact is globally shared and writes cost money
- [X] T017 [P] Extend `backend/src/test/java/com/simonrowe/auth/SecurityConfigTest.java`: anonymous `POST` to both summary paths → 401; anonymous `GET` to `/api/news/{id}/summary`, `/api/news/{id}/summary/narration` and `/api/news/summaries/ids` → not 401; anonymous `POST /api/blogs/{id}/narration` → still **not** 401 (the blog contract is frozen)

### Data-ops registration (design-doc gap — see research.md F2)

- [X] T018 Add `"article_summaries"` to `BACKUP_COLLECTIONS` in `backend/src/main/java/com/simonrowe/dataops/BackupService.java`, and include its document count in the manifest alongside the existing `narrations` count
- [X] T019 Add `"article_summaries"` to `IMPORT_ORDER_INDEPENDENT` in `backend/src/main/java/com/simonrowe/dataops/RestoreService.java` (it holds no `@DBRef` and points at `aggregated_articles` by plain id, exactly like `favourites`), and add an `ensureArticleSummaryIndexes()` call in the per-collection loop mirroring `ensureFavouriteIndexes()` — a restore drops the collection and its indexes with it
- [X] T020 [P] Create `backend/src/test/java/com/simonrowe/dataops/ArticleSummaryBackupCoverageTest.java` extending `AbstractIntegrationTest`, modelled on `NarrationBackupCoverageTest`: a stored summary appears in the backup archive under `collections/article_summaries.json`, survives a round-trip restore, and its two indexes exist afterwards

### Frontend foundations

- [X] T021 [P] Create `frontend/src/types/articleSummary.ts` with `ArticleSummaryState`, a discriminated `ArticleSummaryResponse` union (a `READY` arm carrying a required `body`, everything else with `body?: never`), and a `SummaryFailureCode` union — following the shape of `BlogNarrationResponse` in `frontend/src/types/blog.ts`
- [X] T022 [P] Create `frontend/src/services/articleSummaryApi.ts` with `fetchArticleSummary(articleId, {afterVersion?, waitSeconds?, signal?})`, `requestArticleSummary(getAccessToken, articleId, signal?)` and `fetchSummarisedArticleIds()`. Reads are unauthenticated `fetch`; the write sends `Authorization: Bearer <token>` as `favouritesApi.ts` does. No client retry on the write — the operation is idempotent but an ambiguous network outcome must stay visible, per the comment on `requestBlogNarration`
- [X] T023 Create `frontend/src/hooks/useEnsureAuthenticated.ts` by moving the `ensureAuthenticated` callback out of `frontend/src/hooks/useFavourites.ts` verbatim, **including the comment explaining that `auth0-react` resolves even when the popup is cancelled, so a session must be confirmed by actually obtaining a token**
- [X] T024 Change `frontend/src/hooks/useFavourites.ts` to consume `useEnsureAuthenticated` and continue re-exporting `ensureAuthenticated` in its return value, so every existing caller is unchanged
- [X] T025 Run `cd frontend && npm test -- useFavourites` and confirm `frontend/tests/hooks/useFavourites.test.ts` passes **with no edits** — the regression gate for T023/T024

**Checkpoint**: Data layer, indexes, security, rate limiting, backup coverage and the
shared auth hook are in place. `ArticleSectionWriter` and `useFavourites` behave exactly as
before. User story work can begin.

---

## Phase 3: User Story 1 — Request an in-depth summary of a news article (Priority: P1) 🎯 MVP

**Goal**: A signed-in visitor chooses **Summarise** on a news card, a right-side drawer
opens, and within about half a minute shows 4–6 paragraphs of neutral third-person prose
under a clear "AI-generated summary" label — without the news list losing its filters,
paging or scroll position.

**Independent Test**: Sign in, open `/news-events`, choose **Summarise** on an article with
substantial source text, confirm the prose appears in the drawer with the disclosure label,
close the drawer, and confirm the list behind is exactly as it was.

### Tests for User Story 1 ⚠️

> Write these first and confirm they fail before implementing.

- [X] T026 [P] [US1] Create `backend/src/test/java/com/simonrowe/summary/ArticleSummaryServiceTest.java` with `@MockitoBean`/mock `Ai`: generation on a fresh article stores `READY` with the model's prose, the model name and the source character count; source text under `HARD_MIN_SOURCE_CHARS` stores `FAILED`/`INSUFFICIENT_SOURCE_TEXT`/`retryable=false` and calls the model **zero times**; a null/blank completion stores `FAILED`/`MODEL_ERROR`/`retryable=true`; a thrown model exception does the same; a missing or `visible=false` article stores nothing and yields `ARTICLE_NOT_FOUND`
- [X] T027 [P] [US1] Add to `ArticleSummaryServiceTest`: an existing `READY` document short-circuits with **no** model call; an existing non-retryable `FAILED` document is returned unchanged with **no** model call (no silent re-spend); an existing retryable `FAILED` document does regenerate
- [X] T028 [P] [US1] Add to `ArticleSummaryServiceTest`: the document id equals `sha256(SUMMARY_FORMAT_VERSION + articleId)` computed independently in the test, so editing the prompt without bumping the constant is caught
- [X] T029 [P] [US1] Create `backend/src/test/java/com/simonrowe/summary/ArticleSummaryControllerTest.java` (standalone `MockMvcBuilders` with a mocked service, as `BlogNarrationControllerTest` does): `POST` reaching a terminal state → 200 with the body; `POST` losing the dedup race → 202 `GENERATING`; `GET` with no document → 200 `NOT_REQUESTED` (**not** 404 — the article exists, the summary does not); `GET` on a missing article → 404; `waitSeconds=26` → 400 from `@Max(25)`; long-poll returns promptly when the version has already moved and when the state is terminal
- [X] T030 [P] [US1] Create `frontend/tests/components/news/NewsSummaryDrawer.test.tsx`: renders the prose, the "AI-generated summary" disclosure label, the source name, the date, the title as a link to the original, the "Read the original" link and the heart; renders a generating state; renders the insufficient-source message with no retry affordance; renders a retryable failure with a retry affordance; Escape, overlay click and the close button each call `onClose`; `document.body.style.overflow` is locked while mounted and restored on unmount
- [X] T031 [P] [US1] Create `frontend/tests/components/news/SummaryButton.test.tsx`: signed out, clicking runs the login popup and does **not** call `POST` when the popup is dismissed; signed in, clicking calls `POST` once

### Implementation for User Story 1

- [X] T032 [US1] Create `backend/src/main/java/com/simonrowe/summary/ArticleSummaryService.java` injecting `AggregatedArticleRepository`, `ArticleSummaryRepository`, `ArticleSourceTextProvider`, `Ai`, `MongoTemplate`, `MeterRegistry`, `@Value("${aggregation.summary.model}")` and `@Value("${aggregation.summary.generation-timeout}")`. Define `SUMMARY_FORMAT_VERSION` **immediately adjacent to the prompt text**, with a comment stating that changing the prompt without bumping the constant serves stale summaries. Prompt requirements: neutral third person; 4–6 paragraphs; Markdown; no heading (the drawer supplies it); do not restate the title; summarise what the piece *says* rather than describing that it is an article
- [X] T033 [US1] Implement `request(articleId)` in `ArticleSummaryService`: resolve the visible article (404 otherwise); compute `id = sha256(SUMMARY_FORMAT_VERSION + articleId)`; short-circuit an existing `READY` or non-retryable `FAILED`; otherwise attempt `repository.insert(...)` with `GENERATING` and, on success, resolve source text, short-circuit under the hard floor to `FAILED`/`INSUFFICIENT_SOURCE_TEXT`, else call `ai.withLlm(model).respond(List.of(new UserMessage(prompt))).getContent()` and store the prose. Catch `DuplicateKeyException` and return the current document as `202`. Increment `article.summary.requests{result=…}` counters and record `article.summary.generation.duration`, mirroring the existing `narration.requests` / `narration.generation.duration` metrics
- [X] T034 [US1] Implement `getStatus(articleId, afterVersion, waitSeconds)` in `ArticleSummaryService` as a copy of `BlogNarrationService.getStatus`'s loop shape: 500 ms internal poll, return immediately when `afterVersion` is null, when the version differs, when the state is terminal, or when `waitSeconds == 0`, and re-read once on deadline
- [X] T035 [US1] Create `backend/src/main/java/com/simonrowe/summary/ArticleSummaryController.java` — `@RestController @RequestMapping("/api/news/{articleId}/summary") @Validated`, with `GET` (`afterVersion`, `waitSeconds` bounded `@Min(0) @Max(25)`) and `POST` returning 200 for a terminal outcome and 202 for a dedup loss, per `contracts/article-summary-api.yaml`
- [X] T036 [P] [US1] Create `frontend/src/hooks/useArticleSummaries.ts` exposing `summaryFor(articleId)`, `requestSummary(articleId)` and per-article loading state. `requestSummary` calls `useEnsureAuthenticated` first and returns early when it resolves false; on a `202` it long-polls `fetchArticleSummary` with `afterVersion` and `waitSeconds: 25` under an `AbortController`, aborting on unmount
- [X] T037 [P] [US1] Create `frontend/src/components/news/SummaryButton.tsx` — a `Sparkles`-icon button beside `FavouriteButton`, following `FavouriteButton`'s `e.preventDefault(); e.stopPropagation()` pattern because news cards are anchors. In this phase it always reads **Summarise**; US3 adds the second label
- [X] T038 [US1] Create `frontend/src/components/news/NewsSummaryDrawer.tsx` following `CodeExampleDrawer`: existing `drawer-overlay`/`drawer` CSS, Escape to close, click-outside to close, `body` overflow lock. Contents in order: source name and date; title linking to the original; the **"AI-generated summary"** disclosure label; the prose; (an audio panel slot, filled in US2); a "Read the original" link; the heart. Render the prose with `ReactMarkdown` using the `a`/`img` renderers built from `classifyLink`/`isAllowedImage`/`buildAllowlist` in `frontend/src/components/chat/linkPolicy.ts` — **no `rehype-raw`**
- [X] T039 [US1] Wire `frontend/src/pages/NewsEventsPage.tsx`: `useArticleSummaries`, a `summaryArticleId` state, `SummaryButton` beside `FavouriteButton` on both the hero cards and the grid cards, and `NewsSummaryDrawer` rendered when `summaryArticleId` is set. **Do not** add the button to the events timeline
- [X] T040 [US1] Add a `news-summary__*` BEM block to `frontend/src/styles.css` for the drawer body, the disclosure label, the prose typography and the summary button, using existing CSS custom properties. Single stylesheet, plain CSS, BEM — no framework, no CSS-in-JS
- [X] T041 [US1] Run `cd backend && ../gradlew test checkstyleMain checkstyleTest` and `cd frontend && npm test && npm run lint`, and confirm every new US1 test passes and nothing existing regressed

**Checkpoint**: US1 is complete and shippable on its own — summaries can be requested, read
and shared, with no audio and no card-label differentiation.

---

## Phase 4: User Story 3 — See at a glance which articles already have summaries (Priority: P2)

**Goal**: Cards with a ready summary read **Read summary** and open instantly for everyone,
signed in or not; the rest read **Summarise** and prompt sign-in.

**Independent Test**: With summaries existing for some articles and not others, load
`/news-events` signed out and confirm the two labels appear on the correct cards, and that
**Read summary** opens the drawer with no sign-in prompt.

### Tests for User Story 3 ⚠️

- [X] T042 [P] [US3] Add to `backend/src/test/java/com/simonrowe/summary/ArticleSummaryControllerTest.java`: `GET /api/news/summaries/ids` returns only the article ids of `READY` summaries — excluding `GENERATING` and `FAILED` — and returns `[]` when there are none
- [X] T043 [P] [US3] Create `frontend/tests/hooks/useArticleSummaries.test.ts`: the ids set is loaded on mount without a token; a failed ids fetch leaves the set empty rather than throwing; `hasSummary(id)` reflects the set
- [X] T044 [P] [US3] Add to `frontend/tests/components/news/SummaryButton.test.tsx`: renders **Read summary** when the id is in the set and **Summarise** when it is not; **Read summary** opens the drawer without invoking the login popup even when signed out
- [X] T045 [P] [US3] Add to `frontend/tests/pages/NewsEventsPage.test.tsx`: a card whose id is in the summaries set shows **Read summary** and the others show **Summarise**; event timeline items render **no** summary control

### Implementation for User Story 3

- [X] T046 [US3] Add `@GetMapping("/summaries/ids")` to `backend/src/main/java/com/simonrowe/aggregation/NewsController.java`, declared **before** the `/{id}` mapping, returning the `Set<String>` of article ids with a `READY` summary. Cross-reference the existing `listSources()` javadoc for why literal-before-template ordering is a readability choice rather than a correctness one, and mirror `GET /api/favourites/{type}/ids` in shape
- [X] T047 [US3] Extend `frontend/src/hooks/useArticleSummaries.ts` to load the ids set from `fetchSummarisedArticleIds()` on mount (no token — reads are public), expose `hasSummary(articleId)`, and add an article's id to the set locally when a generation completes `READY`, so the label flips without a refetch
- [X] T048 [US3] Change `frontend/src/components/news/SummaryButton.tsx` to take a `hasSummary` prop and render **Read summary** (opens the drawer directly) versus **Summarise** (runs `ensureAuthenticated`, then generates), with distinct `aria-label`s
- [X] T049 [US3] Update `frontend/src/pages/NewsEventsPage.tsx` to pass `hasSummary` through to both the hero and grid `SummaryButton` instances

**Checkpoint**: US1 and US3 both work. A signed-out visitor can read every existing summary
and is prompted to sign in only when their action would create something new.

---

## Phase 5: User Story 2 — Listen to the summary (Priority: P2)

**Goal**: A signed-in visitor with a ready summary open can generate and play spoken audio
of it; playback stops when the drawer closes; a later visitor gets it instantly.

**Independent Test**: With a ready summary open, request audio, wait for it to render,
confirm playback works, then close the drawer and confirm playback stops.

**Depends on**: Phase 5a is a standalone behaviour-preserving refactor that can land at any
time after Phase 2 — it is sequenced here only because nothing else needs it.

### Phase 5a — Narration generalisation (plan Increment B; no behaviour change)

- [X] T050 [P] [US2] Create `backend/src/main/java/com/simonrowe/narration/NarrationContentType.java` — `enum NarrationContentType { BLOG, ARTICLE_SUMMARY }`, with a javadoc note that room is deliberately left for a future `ARTICLE_FULL`
- [X] T051 [P] [US2] Create `backend/src/main/java/com/simonrowe/narration/NarrationSource.java` — an interface with `NarrationContentType contentType()`, `NarrationDescriptor scriptFor(String contentId)` and `boolean isCurrent(Narration narration)`
- [X] T052 [US2] Rename `BlogNarrationScriptBuilder` to `NarrationScriptBuilder` in `backend/src/main/java/com/simonrowe/narration/`, and rename `BlogNarrationScriptBuilderTest` to match. **`FORMAT_VERSION` must stay the literal string `blog-narration-v1`** — it feeds the fingerprint that *is* the narration `_id`, so changing it would change every existing blog narration's id and orphan its stored MP3. Add a comment saying exactly that, so the next reader does not "tidy" it
- [X] T053 [US2] Change `backend/src/main/java/com/simonrowe/narration/Narration.java`: replace `@Indexed String blogId` with `NarrationContentType contentType` + `String contentId`, change the constructor to `(id, contentType, contentId, scriptCharacterCount, voiceName, languageCode, audioEncoding, providerOutputObject, now)`, and replace the `idx_narration_blog_updated` `@CompoundIndex` with `idx_narration_content_updated` on `{contentType: 1, contentId: 1, updatedAt: -1}`. Leave `fingerprint` (`@Indexed(unique = true)`, still equal to `id`), `idx_narration_status_lease` and every state-transition method untouched
- [X] T054 [US2] Change `backend/src/main/java/com/simonrowe/narration/NarrationRepository.java`: `findByBlogId(String)` → `findByContentTypeAndContentId(NarrationContentType, String)`
- [X] T055 [US2] Rename `BlogNarrationService` to `NarrationService` in `backend/src/main/java/com/simonrowe/narration/`, replacing the `BlogRepository` dependency with a `Map<NarrationContentType, NarrationSource>` registry built from the injected `List<NarrationSource>`. Every method that took a blog id or a `Blog` now takes `(contentType, contentId)` and delegates to `source.scriptFor(...)` / `source.isCurrent(...)`. `invalidateBlog` becomes `invalidate(contentType, contentId)`
- [X] T056 [US2] Create `backend/src/main/java/com/simonrowe/narration/BlogNarrationSource.java` implementing `NarrationSource` for `BLOG`, holding the `BlogRepository` lookup (`findByIdAndPublishedTrue`), the `NarrationScriptBuilder` call on `(blog.title(), blog.content())`, and the existing 422/413 `ResponseStatusException` guards for blank and over-long scripts
- [X] T057 [US2] Change `backend/src/main/java/com/simonrowe/narration/NarrationRequestConsumer.java` to depend on `NarrationService` and the source registry instead of `BlogRepository`, resolving script and currency through `NarrationSource`. Its lease, budget, provider-start, poll, download, stale and failure paths must be unchanged
- [X] T058 [US2] Change `backend/src/main/java/com/simonrowe/narration/NarrationContentChangeConsumer.java` to call `narrationService.invalidate(NarrationContentType.BLOG, event.contentId())`, keeping its `ContentChangeEvent.ContentType.BLOG` filter — aggregated articles are immutable, so no `AGGREGATED_ARTICLE` invalidation path is needed
- [X] T059 [US2] Change `backend/src/main/java/com/simonrowe/narration/BlogNarrationController.java` to delegate to `NarrationService` with `NarrationContentType.BLOG`. Its path, its **public** `POST`, its status codes and its response body must be byte-for-byte unchanged
- [X] T060 [US2] Update `NarrationRestoreValidator.ensureIndexes()` in `backend/src/main/java/com/simonrowe/narration/NarrationRestoreValidator.java` to create `contentId`, `contentType`+`contentId`+`updatedAt` (`idx_narration_content_updated`) and drop the stale `blogId` names. A restore drops collections, so this method — not Mongock — is what puts indexes back (see research.md F1)
- [X] T061 [US2] Create `backend/src/main/java/com/simonrowe/migration/changeunits/V021GeneraliseNarrationContentType.java` — `@ChangeUnit(id = "generalise-narration-content-type", order = "021", author = "simonrowe")` working at the raw `Document` level: for each `narrations` document that has a `blogId`, `$set contentType: "BLOG"` and `contentId: <blogId>` and `$unset blogId`; drop `idx_narration_blog_updated` and the single-field `blogId` index if present; create `idx_narration_content_updated`. `@RollbackExecution` reverses all three. Idempotent because the filter matches nothing on a re-run. This unit performs **no external I/O**, so the standard change-unit test pattern applies, not the isolated-boot pattern
- [X] T062 [P] [US2] Create `backend/src/test/java/com/simonrowe/migration/V021GeneraliseNarrationContentTypeTest.java`: a `blogId`-shaped document is migrated to `contentType: "BLOG"` + `contentId` with `blogId` removed; an already-migrated document is untouched; a second execution changes nothing; the old indexes are gone and the new one exists
- [X] T063 [US2] Fix the `new Narration("narration-1", "blog-1", …)` call site at `backend/src/test/java/com/simonrowe/dataops/NarrationBackupCoverageTest.java:~84` for the new constructor arity. The audio path is **confirmed unaffected** — `NarrationStorage.store` derives it from `narration.id()`, which is the unchanged fingerprint — so the test's path assertions need no change
- [X] T064 [US2] Update the remaining narration test classes (`BlogNarrationServiceTest`, `BlogNarrationConcurrencyTest`, `BlogNarrationControllerTest`, `NarrationRequestConsumerTest`, `NarrationRestoreValidatorTest`, `NarrationMediaServingTest`) for the renamed types and new constructor arity. **Adjust names and arities only — no assertion may be weakened or deleted.** These suites are the regression net for T050–T061
- [X] T065 [US2] Run `cd backend && ../gradlew test --tests 'com.simonrowe.narration.*' --tests '*NarrationBackupCoverageTest' --tests '*V021*'` and `cd frontend && npm test -- BlogNarration`, and confirm all pass — with `frontend/src/components/blog/BlogNarration.test.tsx` **untouched**

### Phase 5b — Summary audio

- [X] T066 [P] [US2] Create `backend/src/test/java/com/simonrowe/summary/SummaryNarrationControllerTest.java`: `POST` on an article with a `READY` summary queues and returns 202; `POST` on an article with no `READY` summary returns 404; a 503 carries an `UNAVAILABLE` `NarrationResponse` when the provider is unconfigured; `GET` long-poll honours `afterVersion`/`waitSeconds` with the same bounds as the blog endpoint
- [X] T067 [P] [US2] Create `frontend/tests/components/narration/useNarration.test.ts`: initial status fetch; long-poll continues while pending and stops on a terminal state; the in-flight request is aborted on unmount; a request error surfaces as a client error without unhandled rejection
- [X] T068 [P] [US2] Add to `frontend/tests/components/news/NewsSummaryDrawer.test.tsx`: the audio panel renders in the drawer; unmounting the drawer unmounts the `<audio>` element; the panel reports audio unavailable when the state is `UNAVAILABLE` while the prose still renders
- [X] T069 [US2] Create `backend/src/main/java/com/simonrowe/narration/ArticleSummaryNarrationSource.java` implementing `NarrationSource` for `ARTICLE_SUMMARY`: look up the `READY` `ArticleSummary` and its `AggregatedArticle` (404 if either is missing or the article is not visible), and build the script from the article title plus the summary body through the same `NarrationScriptBuilder`. `isCurrent` compares the freshly computed fingerprint to the narration's id, so regenerating a summary marks the old audio `STALE` for free
- [X] T070 [US2] Create `backend/src/main/java/com/simonrowe/summary/SummaryNarrationController.java` — `@RestController @RequestMapping("/api/news/{articleId}/summary/narration") @Validated`, delegating to `NarrationService` with `NarrationContentType.ARTICLE_SUMMARY`, and reproducing `BlogNarrationController`'s exact response handling including the 503-carries-`UNAVAILABLE` case
- [X] T071 [US2] Create `frontend/src/components/narration/useNarration.ts` by extracting the long-poll orchestration and abort handling from `frontend/src/components/blog/BlogNarration.tsx` verbatim, parameterised by an `endpointBase` and by request/status callables so both the public blog `POST` and the authenticated summary `POST` fit. Preserve `LONG_POLL_SECONDS = 25`, `MAX_LONG_POLLS = 4` and the `delayed` behaviour
- [X] T072 [US2] Create `frontend/src/components/narration/NarrationPanel.tsx` by extracting the seven-state render machine, the audio player, the `PLAYBACK_SPEEDS` control and the pause-other-`<audio>`-elements behaviour from `BlogNarration.tsx` verbatim, with the heading/eyebrow text and the aria-label subject passed in as props
- [X] T073 [US2] Reduce `frontend/src/components/blog/BlogNarration.tsx` to a thin wrapper over `useNarration` + `NarrationPanel`, keeping every existing class name, aria attribute and string exactly as `BlogNarration.test.tsx` expects
- [X] T074 [US2] Run `cd frontend && npm test -- BlogNarration` and confirm `frontend/src/components/blog/BlogNarration.test.tsx` passes **untouched** — the regression gate for T071–T073
- [X] T075 [US2] Mount `NarrationPanel` (driven by `useNarration` against `/api/news/{articleId}/summary/narration`) in the audio-panel slot of `frontend/src/components/news/NewsSummaryDrawer.tsx`, rendered only when the summary is `READY`, with the `POST` routed through `useEnsureAuthenticated`
- [X] T076 [US2] Extend the `news-summary__*` block in `frontend/src/styles.css` for the in-drawer audio panel, reusing the existing `blog-narration__*` visual language rather than duplicating its rules

**Checkpoint**: All three of US1, US2 and US3 work independently.

---

## Phase 6: User Story 4 — Two people ask for the same summary at the same time (Priority: P3)

**Goal**: Exactly one summary is generated per article under concurrency, and a generation
whose process died is retried after the timeout — but a fresh one is not.

**Independent Test**: Issue concurrent `POST`s for one article and confirm one model call
and one document; age a `GENERATING` document past the timeout and confirm it is reclaimed.

**Note**: The insert-first dedup guard itself lands in US1 (T033) — the `POST` cannot be
written without it. This phase adds the stale reclaim and the proof.

### Tests for User Story 4 ⚠️

- [X] T077 [P] [US4] Create `backend/src/test/java/com/simonrowe/summary/ArticleSummaryConcurrencyTest.java` extending `AbstractIntegrationTest` with a mocked `Ai`: N concurrent `request(articleId)` calls produce exactly **one** model invocation, exactly **one** document, one `200 READY` and N-1 `202 GENERATING`
- [X] T078 [P] [US4] Add to `ArticleSummaryConcurrencyTest`: a `GENERATING` document whose `updatedAt` is older than `generation-timeout` is reclaimed (version bumped, generation runs); one whose `updatedAt` is recent is **not**; and two concurrent reclaimers of the same stale document result in exactly one winner and one model call

### Implementation for User Story 4

- [X] T079 [US4] Implement the stale reclaim in `ArticleSummaryService.request`: when the existing document is `GENERATING` and `updatedAt` is older than `aggregation.summary.generation-timeout`, reclaim it with a `mongoTemplate.findAndModify` query guarded on **both** `status == GENERATING` **and** `updatedAt < cutoff` — the `updatedAt` clause is what stops two reclaimers both matching — using `FindAndModifyOptions.options().returnNew(true)`, `$inc version`, `$set updatedAt`. Model it on `BlogNarrationService.claim`. When the reclaim returns null, another caller won: return `202 GENERATING`
- [X] T080 [US4] Add an `article.summary.requests{result=reclaimed}` counter increment and a `LOG.warn` naming the `articleId` and the age of the abandoned generation, so a recurring reclaim is visible in Loki without SSH

**Checkpoint**: All four user stories are complete and independently verifiable.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T081 [P] Create `docs/runbooks/article-summaries.md` covering: where summaries live, what each `failureCode` means and whether to retry, how to force regeneration (bump `SUMMARY_FORMAT_VERSION`), the interaction with the shared 1,000,000 chars/month TTS budget, and how to confirm `NARRATION_ENABLED` and the `GOOGLE_CLOUD_TTS_*` values in production
- [X] T082 [P] Add a `## Recent Changes` note and `## Active Technologies` entries for this feature to `CLAUDE.md` — **already applied during planning**; verify it survived and is still accurate
- [X] T083 Confirm the design's open question: verify that summary narration audio lands at `uploads/narrations/{id}/narration.mp3` unchanged and that `NarrationBackupCoverageTest` and `NarrationRestoreValidator` still cover it, rather than assuming so. Expected outcome per research.md F3: confirmed unaffected, because the path derives from the fingerprint
- [X] T084 Verify `NARRATION_ENABLED` and every `GOOGLE_CLOUD_TTS_*` value are set in the production environment **before** shipping Phase 5. If they are not, summaries will work while summary audio reports "temporarily unavailable" — establish that up front rather than debugging it after deploy
- [X] T085 Run the full gate: `cd backend && ../gradlew test checkstyleMain checkstyleTest jacocoTestCoverageVerification` and `cd frontend && npm test && npm run lint && npm run build`
- [X] T086 Walk every step of [quickstart.md](./quickstart.md) against a local stack with restored production data, including the seven browser checks and the "blog narration is unchanged" section
  - **Done against a live local stack** (Docker infra + backend on :8080, real MongoDB): Mongock applied `V020` and `V021` at boot; both index sets present and correctly shaped; unauthenticated `POST` → 401 and public `GET` → `200 NOT_REQUESTED`; long-poll returns immediately on a terminal state; `waitSeconds=26` → 400; summary narration `POST` → 401 and `GET` → 404 with no `READY` summary; `POST /api/blogs/{id}/narration` → 404 not 401, so the blog contract is provably still public.
  - **This step found a real defect**: public status `GET`s shared the 5/min write bucket, so a drawer session (1 read + 4 long-polls) 429'd the reader mid-generation. Fixed by exempting non-`POST` summary requests from the bucket; re-verified live (12 consecutive reads, all 200). FR-014, the contract and the runbook updated to match.
  - **Browser walkthrough completed 2026-08-25** against the local stack with the 2026-08-24 production backup restored (624 articles, 52 blogs, 1.2 GB media): signed in as the admin identity and generated a real summary of "The AI-Native SDLC playbook" — **5 paragraphs**, neutral third person, no heading, no title restatement, 12,000 source characters (full scrape, truncated at the cap), 4,398 body characters, `status: READY`, `model: gpt-5.6-luna`. Disclosure label, source, date, title link, "Read the original" and the heart all render in the documented order. Closing the drawer preserved the list. The card label flipped to **Read summary** on that one card only, and a **logged-out** visitor opened it instantly with no login prompt and no second model call (one generation, one document — verified in the log and in Mongo). The audio panel correctly reported "Narration is temporarily unavailable" with TTS unconfigured.
  - **Still not exercised**: summary audio actually rendering. That needs `NARRATION_ENABLED=true` and the `GOOGLE_CLOUD_TTS_*` values, which are unset locally and in production.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: no dependencies
- **Phase 2 (Foundational)**: depends on Phase 1 — **blocks every user story**
- **Phase 3 (US1, P1)**: depends on Phase 2
- **Phase 4 (US3, P2)**: depends on Phase 3 (extends `useArticleSummaries` and `SummaryButton`)
- **Phase 5 (US2, P2)**: Phase 5a depends only on Phase 2 and can be pulled forward or run in parallel with Phases 3–4; Phase 5b depends on 5a **and** on Phase 3's drawer
- **Phase 6 (US4, P3)**: depends on Phase 3 (extends `ArticleSummaryService.request`)
- **Phase 7 (Polish)**: depends on every phase being shipped

### Critical path

T001–T005 → T006–T025 → T032–T035 → T038–T039 → (T046–T049 | T050–T065 → T069–T075) → T079

### Within each user story

- Tests are written before the implementation they cover and must fail first
- Entity → repository → service → controller, then frontend types → service → hook → component → page
- The three refactor gates (T009, T025, T065/T074) must pass before anything builds on them

### Parallel opportunities

- **Phase 1**: T005 alongside T002–T004
- **Phase 2**: T008 with T010–T013; T015, T017, T020, T021, T022 are four independent files
- **Phase 3**: T026–T031 are six independent test files; T036 and T037 are independent of each other
- **Phase 4**: T042–T045 in parallel
- **Phase 5**: T050 and T051 in parallel; T062, T066, T067, T068 in parallel
- **Phase 6**: T077 and T078 in the same new file, so sequential
- **Phase 5a is the big one**: it touches only the `narration` package and its migration, so one developer can take T050–T065 end to end while another takes Phases 3–4

### Sequencing traps

- **T002 before T003–T005.** Adding a record component changes the canonical constructor, so `RateLimitInterceptorTest` breaks first and must be fixed before its new cases are added.
- **T014 before any integration test that reads summaries.** Auto-index-creation is off, so a test relying on `idx_article_summary_status_article` fails confusingly without the change unit.
- **T060 in the same increment as T061.** Migrating indexes via Mongock while leaving `NarrationRestoreValidator` on the old names means the next production restore silently drops the content index.
- **T052 must not "tidy" `FORMAT_VERSION`.** The rename is the moment someone will be tempted; the comment added in that task exists to stop it.

---

## Parallel Example: User Story 1

```bash
# All six US1 test files are independent — write them together:
Task: "backend .../summary/ArticleSummaryServiceTest.java — generation, floors, failure codes"
Task: "backend .../summary/ArticleSummaryServiceTest.java — no-respend short-circuits"   # same file, sequential
Task: "backend .../summary/ArticleSummaryControllerTest.java — status codes and long-poll"
Task: "frontend/tests/components/news/NewsSummaryDrawer.test.tsx"
Task: "frontend/tests/components/news/SummaryButton.test.tsx"

# Frontend hook and button are independent files:
Task: "frontend/src/hooks/useArticleSummaries.ts"
Task: "frontend/src/components/news/SummaryButton.tsx"
```

---

## Implementation Strategy

### MVP first (User Story 1 only)

1. Phase 1 — Setup
2. Phase 2 — Foundational (blocks everything)
3. Phase 3 — US1
4. **Stop and validate**: sign in, summarise a real article, read it in the drawer, close it,
   confirm the list is intact
5. Shippable: a complete in-depth-summary feature with no audio

### Incremental delivery

1. Setup + Foundational → foundation ready, `ArticleSectionWriter` and `useFavourites`
   provably unchanged
2. + US1 → **MVP**, deploy/demo
3. + US3 → shared summaries become visible to logged-out visitors, deploy/demo
4. + US2 → audio (verify `NARRATION_ENABLED` in production **first**), deploy/demo
5. + US4 → concurrency hardening and the exactly-one-model-call proof
6. Polish → runbook, full gate, quickstart walkthrough

### Parallel team strategy

Two developers after Phase 2: one takes Phase 5a (the narration generalisation — entirely
inside the `narration` package, gated by its own existing test suites), the other takes
Phases 3, 4 and 6 (the summary feature). They meet at Phase 5b, which needs both the drawer
and the generalised pipeline.

---

## Notes

- `[P]` = different files, no dependency on an incomplete task
- Three tasks exist purely as regression gates and must pass with **zero** assertion edits:
  T009 (`ArticleSectionWriterTest`), T025 (`useFavourites.test.ts`), T074
  (`BlogNarration.test.tsx`). If one of them needs editing to pass, the refactor changed
  behaviour and is wrong.
- Commit after each task or logical group; stop at any checkpoint to validate.
- Never bump `NarrationScriptBuilder.FORMAT_VERSION`, and never edit the prompt in
  `ArticleSummaryService` without bumping `SUMMARY_FORMAT_VERSION`.
