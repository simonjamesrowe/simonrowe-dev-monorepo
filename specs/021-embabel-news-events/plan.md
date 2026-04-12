# Implementation Plan: Embabel News & Events Aggregation

**Branch**: `021-embabel-news-events` | **Date**: 2026-04-12 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/021-embabel-news-events/spec.md`

## Summary

Build an agentic content aggregation system using the Embabel framework that scrapes articles and events from four external sources (AI Native Dev, Rundown AI, London Java Community, Spring Blog), generates AI summaries, stores them in MongoDB, and presents them in two new frontend tabs (News, Events). Content integrates with existing site search (Elasticsearch), AI chatbot (vector embeddings), and a weekly auto-generated digest blog post.

## Technical Context

**Language/Version**: Java 21 (LTS)  
**Primary Dependencies**: Spring Boot 3.5.x, Embabel Agent 0.3.5, Spring AI 1.1.4, JSoup 1.18.x, Rome 2.1.x  
**Storage**: MongoDB (primary), Elasticsearch (search + vectors), Kafka (async events)  
**Testing**: JUnit 5 + Testcontainers (MongoDB, Elasticsearch, Kafka)  
**Target Platform**: Linux server (Docker Compose), macOS (development)  
**Project Type**: Web application (backend + frontend)  
**Performance Goals**: News/Events pages load within 2 seconds; aggregation completes within 10 minutes per run  
**Constraints**: Embabel incompatible with GraalVM native image (see Complexity Tracking)  
**Scale/Scope**: ~50-100 articles/week across 4 sources; single admin user

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Monorepo with Separate Containers | PASS | All code in existing monorepo; no new containers |
| II. Modern Java & React Stack | VIOLATION | Embabel adds kotlin-reflect, incompatible with GraalVM native image. See Complexity Tracking. |
| III. Quality Gates | PASS | Tests via Testcontainers; Checkstyle; JaCoCo |
| IV. Observability & Operability | PASS | Structured logging; existing OTEL integration |
| V. Simplicity & Incremental Delivery | PASS | 6 user stories delivered incrementally by priority |
| VI. Admin CMS UX Standards | PASS | Admin visibility toggle follows existing patterns |
| VII. Interactive Site Tour | N/A | No tour changes |
| VIII. Backup & Restore | PASS | New collections included in existing mongodump |
| IX. Shell Scripting Standards | N/A | No new shell scripts |

### Post-Design Re-check

| Principle | Status | Notes |
|-----------|--------|-------|
| II. Stack — OpenAI only | PASS | Embabel uses Spring AI's OpenAI integration; no new LLM provider |
| II. Stack — Kafka events | PASS | New content types added to existing ContentType enum |
| II. Stack — CSS/BEM | PASS | New pages follow existing styles.css + BEM conventions |
| II. Stack — Frontend routes | PASS | `/news` and `/events` follow existing route conventions |

## Project Structure

### Documentation (this feature)

```text
specs/021-embabel-news-events/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0: technology research
├── data-model.md        # Phase 1: entity definitions
├── quickstart.md        # Phase 1: setup and testing guide
├── contracts/
│   └── api.yaml         # Phase 1: OpenAPI contract
└── checklists/
    └── requirements.md  # Spec quality checklist
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/simonrowe/
│   ├── aggregation/           # NEW: entities, repos, controllers
│   │   ├── AggregatedArticle.java
│   │   ├── AggregatedArticleRepository.java
│   │   ├── AggregatedEvent.java
│   │   ├── AggregatedEventRepository.java
│   │   ├── ContentSource.java
│   │   ├── ContentSourceRepository.java
│   │   ├── NewsController.java
│   │   ├── EventsController.java
│   │   └── AdminAggregationController.java
│   ├── agents/                # NEW: Embabel agents and actions
│   │   ├── ContentAggregationAgent.java
│   │   ├── WeeklyDigestAgent.java
│   │   ├── actions/
│   │   │   ├── FetchContentAction.java
│   │   │   ├── ParseContentAction.java
│   │   │   ├── SummarizeContentAction.java
│   │   │   ├── StoreContentAction.java
│   │   │   ├── GatherActivityAction.java
│   │   │   └── PublishDigestAction.java
│   │   └── scrapers/
│   │       ├── RssScraper.java
│   │       ├── SitemapHtmlScraper.java
│   │       └── ScraperFactory.java
│   ├── events/                # MODIFY: add content types
│   ├── search/                # MODIFY: add news/events indexing
│   ├── embedding/             # MODIFY: add news/events embedding
│   ├── chat/                  # NO CHANGE (auto via vector store)
│   └── mcp/                   # MODIFY: add news/events MCP tools
└── src/test/java/com/simonrowe/
    ├── aggregation/           # NEW: controller + repository tests
    └── agents/                # NEW: agent integration tests

frontend/
├── src/
│   ├── types/
│   │   ├── news.ts            # NEW
│   │   └── events.ts          # NEW
│   ├── services/
│   │   ├── newsApi.ts         # NEW
│   │   └── eventsApi.ts       # NEW
│   ├── pages/
│   │   ├── NewsPage.tsx       # NEW
│   │   └── EventsPage.tsx     # NEW
│   ├── components/
│   │   ├── layout/TopNav.tsx  # MODIFY: add nav links
│   │   └── search/SiteSearch.tsx # MODIFY: handle new types
│   └── App.tsx                # MODIFY: add routes
└── tests/
    ├── NewsPage.test.tsx      # NEW
    └── EventsPage.test.tsx    # NEW
```

**Structure Decision**: Extends existing `backend/` + `frontend/` web application structure. New `aggregation/` and `agents/` packages in backend follow existing package-per-domain convention (like `chat/`, `search/`, `embedding/`).

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| GraalVM native image disabled | Embabel 0.3.5 depends on kotlin-reflect and provides no GraalVM metadata. User explicitly requested Embabel framework. | Building agentic flows without Embabel (plain Spring AI + @Scheduled) was rejected because the user's stated goal is to use Embabel. The JVM runtime overhead (~200MB more memory, ~2s slower startup) is acceptable for this personal site. Native image can be re-enabled if/when Embabel adds GraalVM support. |
