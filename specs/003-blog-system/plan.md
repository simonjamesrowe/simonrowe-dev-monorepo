# Implementation Plan: Blog System

**Branch**: `003-blog-system` | **Date**: 2026-02-21 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/003-blog-system/spec.md`

## Summary

Build the blog system for simonrowe.dev comprising a blog listing page with a mixed-layout grid (alternating vertical and horizontal card orientations), a detail page that renders Markdown content with syntax-highlighted code blocks, a typeahead search powered by Elasticsearch, and a homepage preview section showing the 3 most recent posts. The backend exposes a Spring Boot 4 REST API backed by MongoDB for persistence and Elasticsearch for search. The frontend renders Markdown to HTML client-side using react-markdown with Prism.js syntax highlighting, matching the patterns established in the existing react-ui reference application.

## Technical Context

**Language/Version**: Java 25 (backend), TypeScript (frontend)
**Primary Dependencies**: Spring Boot 4, Spring Data MongoDB, Spring Data Elasticsearch, React (latest stable), react-markdown, rehype-raw, react-syntax-highlighter (Prism)
**Storage**: MongoDB (primary persistence for blogs and tags), Elasticsearch (search index)
**Testing**: JUnit 5 + Testcontainers (backend integration), Vitest + React Testing Library (frontend unit/component)
**Target Platform**: Docker containers orchestrated via Docker Compose
**Project Type**: Web application (separate backend and frontend containers)
**Performance Goals**: Blog listing interactive within 2 seconds (SC-001), search typeahead results within 500ms (SC-002)
**Constraints**: All 18 existing blog posts and 26 tags from MongoDB backup must be supported (FR-015, FR-016)
**Scale/Scope**: 18 blog posts, 26 tags, single-author blog with public read-only access

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Monorepo with Separate Containers | PASS | Backend and frontend are separate directories with independent Dockerfiles. Docker Compose orchestrates locally and in production. |
| II. Modern Java & React Stack | PASS | Java 25 + Spring Boot 4 + Gradle (Kotlin DSL) + MongoDB + Elasticsearch for backend. React (latest stable) for frontend. Kafka used for search index updates via content change events. |
| III. Quality Gates (NON-NEGOTIABLE) | PASS | Google Java Style enforced. JaCoCo coverage thresholds. Testcontainers for integration tests against real MongoDB and Elasticsearch. Frontend component tests for critical user journeys (listing, detail rendering, search). |
| IV. Observability & Operability | PASS | Spring Boot Actuator on separate management port. Prometheus metrics exposed. OpenTelemetry tracing integrated. Structured logging for all blog operations. |
| V. Simplicity & Incremental Delivery | PASS | Four independently testable user stories (P1-P4). No premature abstractions. Content management deferred per constitution. Blog data seeded from existing MongoDB backup. |

## Project Structure

### Documentation (this feature)

```text
specs/003-blog-system/
├── plan.md              # This file
├── research.md          # Phase 0: technology research
├── data-model.md        # Phase 1: MongoDB document schemas
├── quickstart.md        # Phase 1: verification steps
├── contracts/
│   └── blog-api.yaml    # Phase 1: OpenAPI specification
└── checklists/
    └── requirements.md  # Requirements traceability
```

### Source Code (repository root)

```text
backend/
├── build.gradle.kts
├── src/main/java/com/simonrowe/blog/
│   ├── Blog.java                    # MongoDB document entity
│   ├── Tag.java                     # MongoDB document entity
│   ├── BlogRepository.java          # Spring Data MongoDB repository
│   ├── TagRepository.java           # Spring Data MongoDB repository
│   ├── BlogService.java             # Business logic (query, filter published)
│   ├── BlogController.java          # REST API endpoints
│   ├── BlogSearchService.java       # Elasticsearch search operations
│   └── BlogSearchDocument.java      # Elasticsearch index document
├── src/test/java/com/simonrowe/blog/
│   ├── BlogControllerTest.java      # WebMvcTest slice tests
│   ├── BlogServiceTest.java         # Unit tests
│   ├── BlogRepositoryTest.java      # DataMongoTest with Testcontainers
│   ├── BlogSearchServiceTest.java   # Elasticsearch Testcontainers tests
│   └── BlogIntegrationTest.java     # Full integration tests

frontend/
├── src/
│   ├── components/
│   │   └── blog/
│   │       ├── BlogCard.tsx              # Shared card rendering (vertical/horizontal variants)
│   │       ├── BlogGrid.tsx              # Mixed-layout grid with alternating card orientations
│   │       ├── BlogDetail.tsx            # Full article view with Markdown rendering
│   │       ├── BlogSearch.tsx            # Typeahead search with Elasticsearch backend
│   │       ├── CodeBlock.tsx             # Prism.js syntax highlighting for code blocks
│   │       ├── MarkdownRenderer.tsx      # Configured react-markdown with rehype/remark plugins
│   │       └── HomepageBlogPreview.tsx   # 3 latest posts for homepage section
│   ├── pages/
│   │   ├── BlogListingPage.tsx           # Blog section entry point
│   │   └── BlogDetailPage.tsx            # Route: /blogs/:id
│   ├── services/
│   │   └── blogApi.ts                    # API client for blog endpoints
│   └── types/
│       └── blog.ts                       # TypeScript interfaces (Blog, Tag, BlogSearchResult)
├── tests/
│   ├── components/
│   │   └── blog/
│   │       ├── BlogCard.test.tsx
│   │       ├── BlogGrid.test.tsx
│   │       ├── BlogDetail.test.tsx
│   │       ├── BlogSearch.test.tsx
│   │       └── HomepageBlogPreview.test.tsx
│   └── pages/
│       ├── BlogListingPage.test.tsx
│       └── BlogDetailPage.test.tsx
```

**Structure Decision**: Web application structure (Option 2) selected. Backend and frontend are independent projects at the repository root, each with their own build configuration, Dockerfile, and test suites. This aligns with Constitution Principle I (Monorepo with Separate Containers).

## Complexity Tracking

> No constitution violations. All principles are satisfied without deviations.
