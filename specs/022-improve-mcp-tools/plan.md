# Implementation Plan: Improve MCP Tools

**Branch**: `022-improve-mcp-tools` | **Date**: 2026-04-15 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/022-improve-mcp-tools/spec.md`

## Summary

Review and improve the existing MCP tools for the AI chat assistant. Four changes: (1) add a `submitContactForm` tool with one-successful-use-per-session spam protection, (2) fix `searchBlogs` to use the blog-specific Elasticsearch index instead of the generic site search, (3) add optional search parameters to `getJobs`, `getSkills`, and `getUpcomingEvents` tools so they return filtered results instead of dumping everything, and (4) move `searchNews` from in-memory string matching to Elasticsearch. All changes are backend-only.

## Technical Context

**Language/Version**: Java 21 (LTS)
**Primary Dependencies**: Spring Boot 3.5.x, Spring AI 1.1.4, Spring Data MongoDB, Spring Data Elasticsearch
**Storage**: MongoDB (primary), Elasticsearch (search indices: `site_search`, `blog_search`)
**Testing**: JUnit 5, Mockito, Testcontainers (MongoDB, Elasticsearch)
**Target Platform**: Linux server (Docker container, GraalVM native image)
**Project Type**: Web application (backend + frontend monorepo)
**Performance Goals**: Search tool responses < 2 seconds
**Constraints**: No new ES indices, no frontend changes, GraalVM native image compatibility
**Scale/Scope**: Single-user portfolio site, low traffic

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Monorepo with Separate Containers | PASS | Backend-only changes, no container changes |
| II. Modern Java & React Stack | PASS | Uses existing Spring AI MCP, Elasticsearch, Brevo SMTP. No new dependencies. |
| III. Quality Gates | PASS | Tests will use Testcontainers for ES integration tests, Mockito for unit tests |
| IV. Observability | PASS | All tool methods already have `@WithSpan`, new tools will follow same pattern |
| V. Simplicity & Incremental | PASS | Reuses existing indices, services, and session tracking patterns. No new abstractions. Contact data not persisted (per constitution: data only forwarded, never queried, MUST NOT be persisted). |
| VI. Admin CMS UX | N/A | No admin UI changes |
| VII. Interactive Site Tour | N/A | No tour changes |
| VIII. Backup & Restore | N/A | No data model changes |
| IX. Shell Scripting | N/A | No scripts |

**Gate result**: PASS — no violations.

## Project Structure

### Documentation (this feature)

```text
specs/022-improve-mcp-tools/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── mcp-tools-api.md # MCP tool signatures
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/simonrowe/
│   ├── mcp/
│   │   └── ProfileMcpTools.java          # Primary changes: new tool, fix existing tools
│   ├── chat/
│   │   ├── ChatController.java           # Add contactSubmittedSessions tracking
│   │   └── ChatSessionCleanupService.java # Clean up contact state on eviction
│   ├── contact/
│   │   └── ContactService.java           # Add submitFromChat() method
│   └── search/
│       └── SearchService.java            # Add searchByType() method
└── src/test/java/com/simonrowe/
    ├── mcp/
    │   └── ProfileMcpToolsTest.java      # Update and add tests
    ├── chat/
    │   └── ChatControllerTest.java       # Test contact session tracking
    └── search/
        └── SearchServiceTest.java        # Test searchByType (if exists, or create)
```

**Structure Decision**: Existing web application structure. All changes fit within existing packages — no new packages or modules required.

## Complexity Tracking

No violations to justify — all constitution gates pass.
