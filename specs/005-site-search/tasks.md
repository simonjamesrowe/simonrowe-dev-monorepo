# Tasks: Site-Wide Search

**Feature**: 005-site-search | **Date**: 2026-02-21
**Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

## Phase 2: Foundational

- [ ] T001 [P1] Create Elasticsearch client configuration with connection pooling and index auto-creation on startup `backend/src/main/java/com/simonrowe/search/elasticsearch/ElasticsearchConfig.java`
- [ ] T002 [P1] Create SiteSearchDocument model mapping for the site_search index with fields: name (text+keyword), type (keyword), shortDescription (text), longDescription (text), image (keyword, not indexed), url (keyword, not indexed) `backend/src/main/java/com/simonrowe/search/elasticsearch/SiteSearchDocument.java`
- [ ] T003 [P1] Create BlogSearchDocument model mapping for the blog_search index with fields: title (text+keyword), shortDescription (text), content (text), tags (keyword array), skills (keyword array), image (keyword, not indexed), publishedDate (date), url (keyword, not indexed) `backend/src/main/java/com/simonrowe/search/elasticsearch/BlogSearchDocument.java`
- [ ] T004 [P1] Create SearchResult DTO with fields: name, image (nullable), url `backend/src/main/java/com/simonrowe/search/SearchResult.java`
- [ ] T005 [P1] Create BlogSearchResult DTO with fields: title, shortDescription (nullable), image (nullable), publishedDate, url `backend/src/main/java/com/simonrowe/search/BlogSearchResult.java`
- [ ] T006 [P1] Create GroupedSearchResponse DTO wrapping blogs, jobs, skills arrays of SearchResult; omit empty groups from JSON serialization `backend/src/main/java/com/simonrowe/search/GroupedSearchResponse.java`
- [ ] T007 [P1] Create IndexService with methods: indexSiteDocument, indexBlogDocument, deleteSiteDocument, deleteBlogDocument, bulkIndexSiteDocuments, bulkIndexBlogDocuments using Elasticsearch Bulk API `backend/src/main/java/com/simonrowe/search/IndexService.java`
- [ ] T008 [P1] Create SearchService with siteSearch(query) returning GroupedSearchResponse using multi_match across name, shortDescription, longDescription with post-filter grouping by type; max 5 results per group `backend/src/main/java/com/simonrowe/search/SearchService.java`
- [ ] T009 [P1] Add blogSearch(query) method to SearchService using multi_match across title (^3), tags (^2), shortDescription (^2), content, skills with max 20 results `backend/src/main/java/com/simonrowe/search/SearchService.java`
- [ ] T010 [P1] Add query validation to SearchService: return empty results for queries < 2 chars; truncate queries > 200 chars `backend/src/main/java/com/simonrowe/search/SearchService.java`
- [ ] T011 [P1] Add graceful degradation to SearchService: catch Elasticsearch connection exceptions, log error, return empty results with 200 status `backend/src/main/java/com/simonrowe/search/SearchService.java`
- [ ] T012 [P1] Create SearchController with GET /api/search endpoint accepting query param q, delegating to SearchService.siteSearch `backend/src/main/java/com/simonrowe/search/SearchController.java`
- [ ] T013 [P1] Add GET /api/search/blogs endpoint to SearchController accepting query param q, delegating to SearchService.blogSearch `backend/src/main/java/com/simonrowe/search/SearchController.java`
- [ ] T014 [P1] Create frontend search API service with siteSearch(query, signal) and blogSearch(query, signal) functions using fetch with AbortController support `frontend/src/services/searchApi.ts`
- [ ] T015 [P1] Define frontend TypeScript types: SearchResult (name, image, url), BlogSearchResult (title, shortDescription, image, publishedDate, url), GroupedSearchResponse (blogs?, jobs?, skills?) `frontend/src/services/searchApi.ts`
- [ ] T016 [P1] Write unit tests for IndexService: index, delete, and bulk operations `backend/src/test/java/com/simonrowe/search/IndexServiceTest.java`
- [ ] T017 [P1] Write unit tests for SearchService: site search grouping, blog search boosting, query validation, empty results, error handling `backend/src/test/java/com/simonrowe/search/SearchServiceTest.java`
- [ ] T018 [P1] Write unit tests for SearchController: endpoint routing, query parameter binding, response serialization `backend/src/test/java/com/simonrowe/search/SearchControllerTest.java`
- [ ] T019 [P1] Write unit tests for frontend searchApi service: fetch calls, AbortController cancellation, response parsing `frontend/tests/services/searchApi.test.ts`

## Phase 3: US1 — Global Search (P1)

- [ ] T020 [P1] [US1] Configure site_search Elasticsearch index creation in ElasticsearchConfig with mapping: 1 shard, 0 replicas (dev), standard analyzer on text fields `backend/src/main/java/com/simonrowe/search/elasticsearch/ElasticsearchConfig.java`
- [ ] T021 [P1] [US1] Implement content-type transformation in IndexService: map Blog (title, shortDescription, featured image, /blogs/{slug}), Job (job title, descriptions, company image, /employment), Skill (name, description, image, /skills) to SiteSearchDocument `backend/src/main/java/com/simonrowe/search/IndexService.java`
- [ ] T022 [P1] [US1] Implement full sync of all content types (blogs, jobs, skills) from MongoDB to site_search index with orphan cleanup in IndexService `backend/src/main/java/com/simonrowe/search/IndexService.java`
- [ ] T023 [P1] [US1] Write integration test for site search: index sample documents, execute multi_match query, verify grouped results and relevance ordering using Testcontainers with Elasticsearch `backend/src/test/java/com/simonrowe/search/SearchIntegrationTest.java`
- [ ] T024 [P1] [US1] Create SiteSearch component with search input, 300ms debounce via useRef+setTimeout, minimum 2-char query length, and AbortController for request cancellation `frontend/src/components/search/SiteSearch.tsx`
- [ ] T025 [P1] [US1] Create SearchDropdown component rendering grouped results under "Blogs", "Jobs", "Skills" headers; hide groups with zero results; show loading spinner during fetch; show "No results found" for empty response `frontend/src/components/search/SearchDropdown.tsx`
- [ ] T026 [P1] [US1] Create SearchResultGroup component rendering a content-type header and list of results with thumbnail image and name, each linking to the content detail page via url field `frontend/src/components/search/SearchResultGroup.tsx`
- [ ] T027 [P1] [US1] Implement dropdown show/hide behavior in SiteSearch: show when query >= 2 chars and results available, hide on input clear, blur (with delay for click events), or Escape key `frontend/src/components/search/SiteSearch.tsx`
- [ ] T028 [P1] [US1] Handle missing thumbnail images in SearchResultGroup: render a placeholder/default image when image field is null `frontend/src/components/search/SearchResultGroup.tsx`
- [ ] T029 [P1] [US1] Integrate SiteSearch component into the homepage banner area `frontend/src/components/search/SiteSearch.tsx`
- [ ] T030 [P1] [US1] Write component tests for SiteSearch: debounce behavior, AbortController cancellation on rapid typing, minimum query length enforcement `frontend/src/components/search/SiteSearch.test.tsx`
- [ ] T031 [P1] [US1] Write component tests for SearchDropdown: grouped rendering, empty group hiding, loading state, no-results message `frontend/src/components/search/SearchDropdown.test.tsx`
- [ ] T032 [P1] [US1] Write component tests for SearchResultGroup: result rendering with thumbnail and name, click navigation, placeholder image for null image `frontend/src/components/search/SearchResultGroup.test.tsx`

## Phase 4: US2 — Blog Search (P2)

- [ ] T033 [P2] [US2] Configure blog_search Elasticsearch index creation in ElasticsearchConfig with mapping: 1 shard, 0 replicas (dev), standard analyzer, date field for publishedDate `backend/src/main/java/com/simonrowe/search/elasticsearch/ElasticsearchConfig.java`
- [ ] T034 [P2] [US2] Implement blog content transformation in IndexService: map Blog (title, shortDescription, content as full Markdown, tags array, skills array, featured image, publishedDate, /blogs/{slug}) to BlogSearchDocument `backend/src/main/java/com/simonrowe/search/IndexService.java`
- [ ] T035 [P2] [US2] Implement full sync of blog content from MongoDB to blog_search index with orphan cleanup in IndexService `backend/src/main/java/com/simonrowe/search/IndexService.java`
- [ ] T036 [P2] [US2] Write integration test for blog search: index sample blog documents, execute multi_match query with field boosting (title^3, tags^2, shortDescription^2, content, skills), verify results contain title, shortDescription, image, publishedDate, url `backend/src/test/java/com/simonrowe/search/SearchIntegrationTest.java`
- [ ] T037 [P2] [US2] Create BlogSearch component with search input, debounce, and result list displaying thumbnail, title, and formatted publication date for each matching blog post `frontend/src/components/search/BlogSearch.tsx`
- [ ] T038 [P2] [US2] Handle no-results state in BlogSearch: display "No results found" message when API returns empty array `frontend/src/components/search/BlogSearch.tsx`
- [ ] T039 [P2] [US2] Integrate BlogSearch component into the blog listing page `frontend/src/components/search/BlogSearch.tsx`
- [ ] T040 [P2] [US2] Write component tests for BlogSearch: result rendering with title/image/date, no-results message, debounce behavior, click navigation to blog detail page `frontend/src/components/search/BlogSearch.test.tsx`

## Phase 5: US3 — Real-Time Indexing (P3)

- [ ] T041 [P3] [US3] Create ContentChangeEvent model with fields: eventType (CREATED/UPDATED/DELETED), contentType (BLOG/JOB/SKILL), contentId (string), timestamp (ISO-8601) `backend/src/main/java/com/simonrowe/events/ContentChangeEvent.java`
- [ ] T042 [P3] [US3] Create ContentChangeConsumer Kafka listener on topic content-changes with consumer group search-indexer; route processing based on contentType and eventType `backend/src/main/java/com/simonrowe/events/ContentChangeConsumer.java`
- [ ] T043 [P3] [US3] Implement CREATED/UPDATED event handling in ContentChangeConsumer: fetch full document from MongoDB by contentId, transform to Elasticsearch documents, upsert into appropriate indices (both site_search and blog_search for blogs, only site_search for jobs/skills) `backend/src/main/java/com/simonrowe/events/ContentChangeConsumer.java`
- [ ] T044 [P3] [US3] Implement DELETED event handling in ContentChangeConsumer: delete document by contentId from appropriate index(es) based on contentType `backend/src/main/java/com/simonrowe/events/ContentChangeConsumer.java`
- [ ] T045 [P3] [US3] Add error handling to ContentChangeConsumer: retry up to 3 times with exponential backoff; route failed messages to content-changes.DLT dead-letter topic `backend/src/main/java/com/simonrowe/events/ContentChangeConsumer.java`
- [ ] T046 [P3] [US3] Integrate Kafka producer into existing content management services: publish ContentChangeEvent with contentId as message key on content create, update, and delete operations `backend/src/main/java/com/simonrowe/events/ContentChangeEvent.java`
- [ ] T047 [P3] [US3] Create SearchIndexSyncScheduler with @Scheduled cron (0 0 */4 * * *): execute full re-sync of all content types from MongoDB to both Elasticsearch indices with orphan cleanup `backend/src/main/java/com/simonrowe/search/SearchIndexSyncScheduler.java`
- [ ] T048 [P3] [US3] Add sync-on-startup behavior to SearchIndexSyncScheduler: trigger full sync when the application starts to ensure indices are populated `backend/src/main/java/com/simonrowe/search/SearchIndexSyncScheduler.java`
- [ ] T049 [P3] [US3] Write integration test for ContentChangeConsumer: publish events to Kafka, verify documents are indexed/updated/deleted in Elasticsearch using Testcontainers (Kafka + Elasticsearch) `backend/src/test/java/com/simonrowe/events/ContentChangeConsumerTest.java`
- [ ] T050 [P3] [US3] Write integration test for SearchIndexSyncScheduler: populate MongoDB with test data, run full sync, verify all documents indexed and orphans removed `backend/src/test/java/com/simonrowe/search/SearchIndexSyncSchedulerTest.java`

## Phase 6: Polish

- [ ] T051 [P1] Add Micrometer metrics for search latency (search.query.duration), index operations (search.index.duration), and sync metrics (search.sync.duration, search.sync.documents.indexed, search.sync.documents.deleted) `backend/src/main/java/com/simonrowe/search/SearchService.java`
- [ ] T052 [P1] Add structured logging for search operations: log query terms, result counts, and latency for each search request; log index operations and sync start/completion with document counts `backend/src/main/java/com/simonrowe/search/SearchService.java`
- [ ] T053 [P1] Add OpenTelemetry trace spans for search requests and Kafka consumer processing `backend/src/main/java/com/simonrowe/search/SearchService.java`
- [ ] T054 [P1] Configure search-related application properties in application.yml: spring.elasticsearch.uris, spring.kafka.bootstrap-servers, search.sync.cron, search.site.max-results-per-group (5), search.blog.max-results (20), search.query.max-length (200) `backend/src/main/resources/application.yml`
- [ ] T055 [P1] End-to-end verification: start full stack via Docker Compose, verify index creation, trigger content sync, test site search and blog search endpoints, confirm frontend typeahead and blog search display correct results
