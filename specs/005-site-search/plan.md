# Implementation Plan: Site-Wide Search

**Branch**: `005-site-search` | **Date**: 2026-02-21 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/005-site-search/spec.md`

## Summary

Site-wide typeahead search across blogs, jobs, and skills using Elasticsearch, with Kafka-driven real-time indexing and periodic full synchronization. The feature delivers two search experiences: a global search from the homepage returning results grouped by content type (blogs, jobs, skills) with thumbnails and names, and a dedicated blog search on the blog listing page returning blog-specific results with thumbnails, titles, and publication dates. Content changes propagated via Kafka events trigger near-real-time index updates, backed by a 4-hour full re-sync scheduler for consistency.

## Technical Context

**Language/Version**: Java 25 (backend), TypeScript/JavaScript (frontend)
**Primary Dependencies**: Spring Boot 4.x, Spring Data Elasticsearch, Spring Kafka, Elasticsearch Java client, React (latest stable)
**Storage**: Elasticsearch (search indices), MongoDB (source of truth for content data)
**Testing**: JUnit 5 + Testcontainers with Elasticsearch and Kafka containers (backend), Jest/Vitest (frontend)
**Target Platform**: Docker containers on Linux (production via Docker Compose + Pinggy)
**Project Type**: Web application (backend + frontend monorepo)
**Performance Goals**: Search results returned within 500ms end-to-end; newly created or updated content searchable within 5 minutes
**Constraints**: Two Elasticsearch indices (site_search for global, blog_search for blog-specific); debounced typeahead on frontend (300ms); maximum result set per category capped for dropdown display
**Scale/Scope**: ~100 documents across 3 content types (blogs, jobs, skills); 2 search endpoints; 1 Kafka consumer group; 1 scheduled sync job

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Status | Justification |
|---|-----------|--------|---------------|
| I | Monorepo with Separate Containers | PASS | All search code lives in the existing `backend/` and `frontend/` directories within the monorepo. No new containers required -- Elasticsearch is already provisioned in Docker Compose from 001-project-infrastructure. |
| II | Modern Java & React Stack | PASS | Uses Spring Data Elasticsearch and Spring Kafka (both mandated by constitution). Elasticsearch for search functionality and Kafka for async messaging are explicitly required by the technology stack constraints. React frontend with TypeScript for the search UI components. |
| III | Quality Gates (NON-NEGOTIABLE) | PASS | Unit tests for SearchService and IndexService. Integration tests with Testcontainers (Elasticsearch + Kafka containers). Frontend component tests for SiteSearch and SearchDropdown. All subject to existing JaCoCo thresholds and SonarQube analysis. |
| IV | Observability & Operability | PASS | Search latency metrics exposed via Micrometer/Prometheus on the actuator port. Structured logging for index operations and search queries. OpenTelemetry traces span search requests and Kafka consumer processing. |
| V | Simplicity & Incremental Delivery | PASS | Three user stories delivered incrementally: P1 global search, P2 blog search, P3 real-time indexing. No premature abstractions -- direct Elasticsearch client usage without repository pattern wrappers. Kafka consumer is a simple event handler, not a complex event-sourcing framework. |

## Project Structure

### Documentation (this feature)

```text
specs/005-site-search/
├── plan.md              # This file
├── research.md          # Phase 0: Elasticsearch indexing and search patterns
├── data-model.md        # Phase 1: Elasticsearch index mappings
├── quickstart.md        # Phase 1: Developer quickstart guide
├── contracts/           # Phase 1: API contracts
│   ├── site-search-api.yaml    # GET /api/search OpenAPI spec
│   └── blog-search-api.yaml    # GET /api/search/blogs OpenAPI spec
├── checklists/
│   └── requirements.md  # Specification quality checklist
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── src/
│   ├── main/java/com/simonrowe/
│   │   ├── search/
│   │   │   ├── SearchController.java          # REST controller: /api/search, /api/search/blogs
│   │   │   ├── SearchService.java             # Search query execution against Elasticsearch
│   │   │   ├── SearchResult.java              # Site-wide search result DTO (name, type, image, url)
│   │   │   ├── BlogSearchResult.java          # Blog-specific search result DTO (title, image, date)
│   │   │   ├── GroupedSearchResponse.java     # Response wrapper grouping results by content type
│   │   │   ├── IndexService.java              # Index/update/delete documents in Elasticsearch
│   │   │   ├── SearchIndexSyncScheduler.java  # @Scheduled full re-sync every 4 hours
│   │   │   └── elasticsearch/
│   │   │       ├── ElasticsearchConfig.java   # Elasticsearch client configuration
│   │   │       ├── SiteSearchDocument.java    # site_search index document mapping
│   │   │       └── BlogSearchDocument.java    # blog_search index document mapping
│   │   └── events/
│   │       ├── ContentChangeEvent.java        # Kafka event payload for content changes
│   │       └── ContentChangeConsumer.java     # Kafka listener triggering index updates
│   └── test/java/com/simonrowe/
│       ├── search/
│       │   ├── SearchControllerTest.java      # Unit tests for REST endpoints
│       │   ├── SearchServiceTest.java         # Unit tests for search logic
│       │   ├── IndexServiceTest.java          # Unit tests for indexing logic
│       │   ├── SearchIntegrationTest.java     # Integration test with Testcontainers (ES)
│       │   └── SearchIndexSyncSchedulerTest.java  # Integration test for full sync
│       └── events/
│           └── ContentChangeConsumerTest.java # Integration test with Testcontainers (Kafka + ES)

frontend/
├── src/
│   ├── components/
│   │   └── search/
│   │       ├── SiteSearch.tsx            # Homepage search box with typeahead dropdown
│   │       ├── SiteSearch.test.tsx       # Tests for SiteSearch component
│   │       ├── SearchDropdown.tsx        # Dropdown container for grouped results
│   │       ├── SearchDropdown.test.tsx   # Tests for SearchDropdown component
│   │       ├── SearchResultGroup.tsx     # Single content-type group (header + result list)
│   │       ├── SearchResultGroup.test.tsx
│   │       ├── BlogSearch.tsx            # Blog listing page search with results
│   │       └── BlogSearch.test.tsx       # Tests for BlogSearch component
│   └── services/
│       └── searchApi.ts                  # API client for search endpoints
└── tests/
    └── services/
        └── searchApi.test.ts             # Tests for search API client
```

**Structure Decision**: Option 2 (Web application) -- extending the existing `backend/` and `frontend/` structure established in 001-project-infrastructure. New packages `search/` and `events/` are added under the existing `com.simonrowe` base package. Frontend search components live under `components/search/` following the established component directory pattern. No new top-level directories or containers are introduced.

## Complexity Tracking

No constitution violations. All principles pass without exception.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| *None* | *N/A* | *N/A* |
