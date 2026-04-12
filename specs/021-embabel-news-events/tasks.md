# Tasks: Embabel News & Events Aggregation

**Input**: Design documents from `/specs/021-embabel-news-events/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/api.yaml, quickstart.md

**Tests**: Not explicitly requested — test tasks omitted.

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story (US1–US6)
- Exact file paths included in descriptions

## Path Conventions

- **Backend**: `backend/src/main/java/com/simonrowe/`
- **Frontend**: `frontend/src/`

---

## Phase 1: Setup

**Purpose**: Add Embabel framework dependencies and configure the project

- [x] T001 Add Embabel Maven repository and dependencies (embabel-agent-starter, embabel-agent-starter-openai, jsoup, rome) to `backend/build.gradle.kts`
- [x] T002 Disable GraalVM native image build for backend in `backend/build.gradle.kts` (documented constitution violation — Embabel requires JVM mode)
- [x] T003 [P] Add Embabel agent scan configuration to `backend/src/main/java/com/simonrowe/Application.java` or a new `@Configuration` class at `backend/src/main/java/com/simonrowe/agents/AgentConfig.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: MongoDB entities, repositories, and Kafka event types that ALL user stories depend on

**Warning**: No user story work can begin until this phase is complete

- [x] T004 [P] Create `AggregatedArticle` MongoDB entity (record with @Document) at `backend/src/main/java/com/simonrowe/aggregation/AggregatedArticle.java` per data-model.md
- [x] T005 [P] Create `AggregatedArticleRepository` extending MongoRepository at `backend/src/main/java/com/simonrowe/aggregation/AggregatedArticleRepository.java` with findByVisibleTrueOrderByPublishedDateDesc, existsByOriginalUrl queries
- [x] T006 [P] Create `AggregatedEvent` MongoDB entity (record with @Document) at `backend/src/main/java/com/simonrowe/aggregation/AggregatedEvent.java` per data-model.md
- [x] T007 [P] Create `AggregatedEventRepository` extending MongoRepository at `backend/src/main/java/com/simonrowe/aggregation/AggregatedEventRepository.java` with findByVisibleTrueAndEventDateAfter, findByVisibleTrueAndEventDateBefore queries
- [x] T008 [P] Create `ContentSource` MongoDB entity (record with @Document) at `backend/src/main/java/com/simonrowe/aggregation/ContentSource.java` per data-model.md
- [x] T009 [P] Create `ContentSourceRepository` extending MongoRepository at `backend/src/main/java/com/simonrowe/aggregation/ContentSourceRepository.java` with findByActiveTrue query
- [x] T010 Add `AGGREGATED_ARTICLE` and `AGGREGATED_EVENT` to `ContentType` enum in `backend/src/main/java/com/simonrowe/events/ContentChangeEvent.java`
- [x] T011 Create MongoDB seed script to insert the 4 initial ContentSource documents (AI Native Dev, Rundown AI, London Java Community, Spring Blog) at `scripts/seed-content-sources.js` with companion shell wrapper `scripts/seed-content-sources.sh`

**Checkpoint**: Foundation ready — entities, repositories, and event types in place

---

## Phase 3: User Story 1 — Browse Curated Tech News (Priority: P1) MVP

**Goal**: Visitors can browse a "News" tab showing AI-summarized articles from external tech sources

**Independent Test**: Navigate to /news, see list of articles with titles, summaries, source names, dates, and links to originals

### Implementation for User Story 1

- [x] T012 [US1] Create `NewsController` with GET /api/news (paginated, visible only, reverse chronological) and GET /api/news/{id} at `backend/src/main/java/com/simonrowe/aggregation/NewsController.java` per contracts/api.yaml
- [x] T013 [P] [US1] Create `ArticleResponse` DTO record at `backend/src/main/java/com/simonrowe/aggregation/ArticleResponse.java` mapping AggregatedArticle fields for API response
- [x] T014 [P] [US1] Create TypeScript types for news articles (ArticleResponse, ArticlePage) at `frontend/src/types/news.ts`
- [x] T015 [P] [US1] Create `newsApi.ts` service with fetchNews(page, size, source?) and fetchNewsById(id) at `frontend/src/services/newsApi.ts` following blogApi.ts pattern
- [x] T016 [US1] Create `NewsPage.tsx` listing component at `frontend/src/pages/NewsPage.tsx` with reverse-chronological article cards showing title, source name, summary, date, and link-out to original. Include empty state message. Follow BlogListingPage.tsx pattern.
- [x] T017 [US1] Add `/news` route to `frontend/src/App.tsx` wrapped in PublicLayout
- [x] T018 [US1] Add "News" NavLink to top navigation in `frontend/src/components/layout/TopNav.tsx` between "Blog" and the actions section
- [x] T019 [US1] Add CSS styles for news page (.news-page, .news-page__grid, .news-card, .news-card__source, .news-card__summary, .news-card__date) using BEM naming in `frontend/src/styles.css`

**Checkpoint**: News tab visible in navigation, displays articles from MongoDB. Manually insert test data to verify.

---

## Phase 4: User Story 2 — Browse Upcoming and Past Events (Priority: P1)

**Goal**: Visitors can browse an "Events" tab showing tech community events with upcoming/past separation

**Independent Test**: Navigate to /events, see events split into Upcoming and Past sections with dates, venues, descriptions

### Implementation for User Story 2

- [x] T020 [US2] Create `EventsController` with GET /api/events (paginated, filterable by upcoming param) and GET /api/events/{id} at `backend/src/main/java/com/simonrowe/aggregation/EventsController.java` per contracts/api.yaml
- [x] T021 [P] [US2] Create `EventResponse` DTO record at `backend/src/main/java/com/simonrowe/aggregation/EventResponse.java` mapping AggregatedEvent fields for API response
- [x] T022 [P] [US2] Create TypeScript types for events (EventResponse, EventPage) at `frontend/src/types/events.ts`
- [x] T023 [P] [US2] Create `eventsApi.ts` service with fetchEvents(page, size, upcoming?) and fetchEventsById(id) at `frontend/src/services/eventsApi.ts` following blogApi.ts pattern
- [x] T024 [US2] Create `EventsPage.tsx` listing component at `frontend/src/pages/EventsPage.tsx` with "Upcoming" and "Past" sections, event cards showing title, date, venue, location, description, and link-out to original. Follow BlogListingPage.tsx pattern.
- [x] T025 [US2] Add `/events` route to `frontend/src/App.tsx` wrapped in PublicLayout
- [x] T026 [US2] Add "Events" NavLink to top navigation in `frontend/src/components/layout/TopNav.tsx` after the "News" link
- [x] T027 [US2] Add CSS styles for events page (.events-page, .events-page__section, .events-page__section-title, .event-card, .event-card__date, .event-card__venue, .event-card__description) using BEM naming in `frontend/src/styles.css`

**Checkpoint**: Events tab visible in navigation, displays events with upcoming/past separation. Manually insert test data to verify.

---

## Phase 5: User Story 3 — Scheduled Content Aggregation (Priority: P1)

**Goal**: External content is automatically fetched, summarized, and stored on a recurring schedule using Embabel agents

**Independent Test**: Trigger aggregation manually via admin endpoint, verify new articles/events appear in MongoDB and on News/Events tabs

### Implementation for User Story 3

- [x] T028 [P] [US3] Create `RssScraper` using Rome library to parse RSS/Atom feeds at `backend/src/main/java/com/simonrowe/agents/scrapers/RssScraper.java` — accepts feed URL, returns list of raw article data (title, url, date, content)
- [x] T029 [P] [US3] Create `SitemapHtmlScraper` using JSoup to parse sitemap.xml, discover article URLs, and scrape full article content at `backend/src/main/java/com/simonrowe/agents/scrapers/SitemapHtmlScraper.java` — accepts sitemap URL, returns list of raw article data
- [x] T030 [US3] Create `ScraperFactory` that returns the appropriate scraper based on ContentSource.scrapeStrategy at `backend/src/main/java/com/simonrowe/agents/scrapers/ScraperFactory.java`
- [x] T031 [US3] Create `FetchContentAction` Embabel @Action that loads active ContentSources, invokes scrapers, and outputs raw fetched content at `backend/src/main/java/com/simonrowe/agents/actions/FetchContentAction.java`
- [x] T032 [US3] Create `ParseContentAction` Embabel @Action that filters duplicates (by originalUrl), separates articles from events, and structures parsed content at `backend/src/main/java/com/simonrowe/agents/actions/ParseContentAction.java`
- [x] T033 [US3] Create `SummarizeContentAction` Embabel @Action that generates 2-3 sentence AI summaries for each article/event using the LLM at `backend/src/main/java/com/simonrowe/agents/actions/SummarizeContentAction.java`
- [x] T034 [US3] Create `StoreContentAction` Embabel @Action that saves AggregatedArticle/AggregatedEvent to MongoDB and publishes Kafka ContentChangeEvents at `backend/src/main/java/com/simonrowe/agents/actions/StoreContentAction.java`
- [x] T035 [US3] Create `ContentAggregationAgent` Embabel @Agent orchestrating FetchContent → ParseContent → SummarizeContent → StoreContent actions with @AchievesGoal at `backend/src/main/java/com/simonrowe/agents/ContentAggregationAgent.java`
- [x] T036 [US3] Create `AggregationScheduler` with @Scheduled (cron configurable, default every 6 hours) that invokes ContentAggregationAgent via Embabel AgentPlatform at `backend/src/main/java/com/simonrowe/aggregation/AggregationScheduler.java`
- [x] T037 [US3] Add aggregation schedule configuration properties to `backend/src/main/resources/application.yml` (aggregation.schedule.cron, aggregation.digest.cron)
- [x] T038 [US3] Create `AdminAggregationController` with POST /api/admin/aggregation/trigger (manual trigger) and admin CRUD for content sources/articles/events at `backend/src/main/java/com/simonrowe/aggregation/AdminAggregationController.java` per contracts/api.yaml
- [x] T039 [US3] Update ContentSource.lastFetchedAt and ContentSource.lastError after each scrape attempt in StoreContentAction

**Checkpoint**: Aggregation runs on schedule and on manual trigger. New content from external sources appears in MongoDB and on News/Events tabs.

---

## Phase 6: User Story 4 — Search Aggregated Content (Priority: P2)

**Goal**: Aggregated news and events appear in existing site search alongside blogs, jobs, and skills

**Independent Test**: Search for a topic covered by aggregated content, see news/event results labeled by type in search results

### Implementation for User Story 4

- [x] T040 [US4] Add `articleToSiteDocument()` and `eventToSiteDocument()` converter methods in `backend/src/main/java/com/simonrowe/search/IndexService.java` mapping AggregatedArticle/Event to SiteSearchDocument with type "news"/"event"
- [x] T041 [US4] Add news/events bulk indexing to `fullSyncSiteIndex()` method in `backend/src/main/java/com/simonrowe/search/IndexService.java`
- [x] T042 [US4] Add `AGGREGATED_ARTICLE` and `AGGREGATED_EVENT` cases to `ContentChangeConsumer` switch in `backend/src/main/java/com/simonrowe/events/ContentChangeConsumer.java` to index on create/update/delete
- [x] T043 [US4] Add "news" and "events" groups to `GroupedSearchResponse` and update `siteSearch()` method in `backend/src/main/java/com/simonrowe/search/SearchService.java`
- [x] T044 [US4] Update `GroupedSearchResponse` type in `frontend/src/types/search.ts` to include news and events arrays
- [x] T045 [US4] Update `SiteSearch.tsx` to render news/event results with appropriate type labels and navigation in `frontend/src/components/search/SiteSearch.tsx`

**Checkpoint**: Searching for topics in aggregated content returns news/event results alongside existing content types.

---

## Phase 7: User Story 5 — Ask the Chatbot About News and Events (Priority: P2)

**Goal**: AI chatbot can answer questions about aggregated news and upcoming events with source attribution

**Independent Test**: Ask chatbot "What's the latest tech news?" and verify it references aggregated articles with source names

### Implementation for User Story 5

- [x] T046 [US5] Add `embedArticle()` and `embedAllArticles()` methods to `backend/src/main/java/com/simonrowe/embedding/EmbeddingService.java` — embed title + summary + fullContent with sourceType "aggregated_article" metadata
- [x] T047 [US5] Add `embedEvent()` and `embedAllEvents()` methods to `backend/src/main/java/com/simonrowe/embedding/EmbeddingService.java` — embed title + summary + description with sourceType "aggregated_event" metadata
- [x] T048 [US5] Add AGGREGATED_ARTICLE and AGGREGATED_EVENT to `fullVectorSync()` in `backend/src/main/java/com/simonrowe/embedding/EmbeddingService.java`
- [x] T049 [US5] Add `AGGREGATED_ARTICLE` and `AGGREGATED_EVENT` cases to `EmbeddingChangeConsumer` switch in `backend/src/main/java/com/simonrowe/events/EmbeddingChangeConsumer.java`
- [x] T050 [US5] Add `searchNews(query)` and `getUpcomingEvents()` MCP tools to `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java` so the chatbot can query aggregated content directly

**Checkpoint**: Chatbot answers questions about aggregated content with source attribution. Vector search returns relevant news/events.

---

## Phase 8: User Story 6 — Weekly Site Activity Digest (Priority: P3)

**Goal**: Automated weekly digest blog post summarizing the week's site activity, blogs, and notable news

**Independent Test**: Trigger digest manually, verify a new blog post is created with "Weekly Digest" tag covering past week's activity

### Implementation for User Story 6

- [x] T051 [US6] Create `GatherActivityAction` Embabel @Action that collects past week's published blogs, aggregated articles, and git commit summaries at `backend/src/main/java/com/simonrowe/agents/actions/GatherActivityAction.java`
- [x] T052 [US6] Create `PublishDigestAction` Embabel @Action that creates a Blog entity from digest content with "Weekly Digest" tag and published=true, and publishes Kafka event at `backend/src/main/java/com/simonrowe/agents/actions/PublishDigestAction.java`
- [x] T053 [US6] Create `WeeklyDigestAgent` Embabel @Agent orchestrating GatherActivity → (LLM summarize) → PublishDigest with @AchievesGoal at `backend/src/main/java/com/simonrowe/agents/WeeklyDigestAgent.java`
- [x] T054 [US6] Add weekly digest @Scheduled method (default Monday 8am) to `AggregationScheduler` at `backend/src/main/java/com/simonrowe/aggregation/AggregationScheduler.java` invoking WeeklyDigestAgent
- [x] T055 [US6] Add POST /api/admin/digest/trigger endpoint to `AdminAggregationController` at `backend/src/main/java/com/simonrowe/aggregation/AdminAggregationController.java` for manual digest triggering
- [x] T056 [US6] Ensure "Weekly Digest" Tag exists in MongoDB (add to seed script or create on first digest if not present) in `backend/src/main/java/com/simonrowe/agents/actions/PublishDigestAction.java`

**Checkpoint**: Weekly digest auto-publishes as blog post. Appears on blog listing, is searchable, and available to chatbot.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Admin UX, error handling, and production readiness

- [x] T057 [P] Add admin UI for managing aggregated content (list with visibility toggle, delete) in `frontend/src/pages/admin/` following existing admin page patterns
- [x] T058 [P] Add admin UI for viewing/editing content sources (active toggle, last fetched, last error) in `frontend/src/pages/admin/`
- [x] T059 Add error handling and structured logging to all Embabel agents and scrapers — log source failures, scraping errors, summarization failures
- [x] T060 Add rate limiting to scraper requests — respect robots.txt and implement configurable delays between requests in `backend/src/main/java/com/simonrowe/agents/scrapers/`
- [x] T061 [P] Add mobile-responsive CSS for News and Events pages in `frontend/src/styles.css`
- [x] T062 [P] Update MobileMenu component with News and Events links in `frontend/src/components/layout/MobileMenu.tsx`
- [x] T063 Run quickstart.md validation — verify all manual testing steps pass end-to-end

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 completion — BLOCKS all user stories
- **US1 News Tab (Phase 3)**: Depends on Phase 2
- **US2 Events Tab (Phase 4)**: Depends on Phase 2, can run in parallel with US1
- **US3 Aggregation (Phase 5)**: Depends on Phase 2, can run in parallel with US1/US2 (but US1/US2 need data to display)
- **US4 Search (Phase 6)**: Depends on Phase 2 + data in MongoDB (from US3 or manual seeding)
- **US5 Chatbot (Phase 7)**: Depends on Phase 2 + data in MongoDB (from US3 or manual seeding)
- **US6 Digest (Phase 8)**: Depends on Phase 2, benefits from US3 data but not strictly required
- **Polish (Phase 9)**: Depends on US1–US3 minimum

### User Story Dependencies

- **US1 (P1)**: After Phase 2 — no dependencies on other stories
- **US2 (P1)**: After Phase 2 — no dependencies on other stories
- **US3 (P1)**: After Phase 2 — no dependencies on other stories (populates data for US1/US2)
- **US4 (P2)**: After Phase 2 — integrates with existing search infrastructure
- **US5 (P2)**: After Phase 2 — integrates with existing embedding/chatbot infrastructure
- **US6 (P3)**: After Phase 2 — needs Blog repository and Tag access

### Within Each User Story

- DTOs/types before controllers/services
- Backend API before frontend pages
- Core implementation before integration

### Parallel Opportunities

- T004–T009: All entity/repository tasks in Phase 2 are parallel
- T012–T015: Backend DTO + Frontend types/services in US1 are parallel
- T020–T023: Backend DTO + Frontend types/services in US2 are parallel
- T028–T029: RSS and Sitemap scrapers are parallel
- T040–T042: Search indexing tasks are parallel
- T046–T049: Embedding tasks are parallel
- T057–T058: Admin UI pages are parallel
- US1, US2, US3 can all proceed in parallel after Phase 2 (with manual test data for US1/US2)

---

## Parallel Example: Phase 2 Foundation

```
# All entity + repository tasks in parallel:
Task T004: AggregatedArticle entity
Task T005: AggregatedArticleRepository
Task T006: AggregatedEvent entity
Task T007: AggregatedEventRepository
Task T008: ContentSource entity
Task T009: ContentSourceRepository
```

## Parallel Example: User Story 1

```
# Backend DTO + Frontend types/services in parallel:
Task T013: ArticleResponse DTO
Task T014: TypeScript news types
Task T015: newsApi.ts service

# Then sequentially:
Task T012: NewsController (needs T013)
Task T016: NewsPage.tsx (needs T015)
Task T017: App.tsx route
Task T018: TopNav link
Task T019: CSS styles
```

---

## Implementation Strategy

### MVP First (User Stories 1 + 3)

1. Complete Phase 1: Setup (Embabel + dependencies)
2. Complete Phase 2: Foundational (entities + repos)
3. Complete Phase 5: US3 Aggregation (populates data)
4. Complete Phase 3: US1 News Tab (displays data)
5. **STOP and VALIDATE**: Trigger aggregation, verify news appears on tab
6. Deploy/demo if ready

### Incremental Delivery

1. Setup + Foundation → framework ready
2. Add US3 Aggregation → data pipeline working
3. Add US1 News Tab → first visible feature (MVP!)
4. Add US2 Events Tab → second tab complete
5. Add US4 Search → aggregated content searchable
6. Add US5 Chatbot → chatbot knows about news/events
7. Add US6 Digest → weekly summary auto-publishes
8. Polish → admin UX, mobile, error handling

### Recommended Execution Order

Even though US1/US2 have higher display priority, US3 (aggregation) should be implemented first since it populates the data that US1/US2 display. Without US3, the News/Events tabs would need manual test data.

Suggested order: Phase 1 → Phase 2 → Phase 5 (US3) → Phase 3 (US1) → Phase 4 (US2) → Phase 6 (US4) → Phase 7 (US5) → Phase 8 (US6) → Phase 9

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Commit after each task or logical group
- Stop at any checkpoint to validate independently
- GraalVM native image is disabled — backend runs in JVM mode (see plan.md Complexity Tracking)
- Embabel agent testing may require mocking the AgentPlatform — check embabel-agent-test dependency
