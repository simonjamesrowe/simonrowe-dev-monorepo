# Implementation Plan: Fix AI Blog Titles and Images

**Branch**: `026-fix-ai-blog-titles` | **Date**: 2026-07-05 | **Spec**: [spec.md](file:///Users/simonrowe/conductor/workspaces/simonrowe-dev-monorepo/tyler/specs/026-fix-ai-blog-titles/spec.md)

**Input**: Feature specification from `/specs/026-fix-ai-blog-titles/spec.md`

## Summary

This feature involves creating a Mongock database migration to locate historical AI-generated digest blogs with generic titles ("This week in AI" or "AI & Tech Roundup"), and regenerate their titles and featured images using the updated AI prompt logic. This ensures a consistent, theme-based presentation across all past and future digest posts.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.5.x, Spring AI 1.1.4, Mongock

**Storage**: MongoDB

**Testing**: JUnit 5, Testcontainers

**Target Platform**: Backend Service

**Project Type**: Web Application (Backend)

**Performance Goals**: Migration execution time is secondary, but should not overwhelm the LLM API. Should process blogs sequentially.

**Constraints**: Needs valid OpenAI API key to execute the migration.

**Scale/Scope**: Affects existing historical digest blogs in the MongoDB `blogs` collection.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- The migration uses Mongock via standard Spring Boot integration, following the monorepo conventions.
- No new libraries are introduced for this migration.

## Project Structure

### Documentation (this feature)

```text
specs/026-fix-ai-blog-titles/
├── plan.md              
├── research.md          
├── data-model.md        
└── quickstart.md        
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/simonrowe/
│   ├── migration/changeunits/V006FixAiBlogTitles.java
```

**Structure Decision**: A new Mongock ChangeUnit will be added to the existing `migration/changeunits/` directory in the backend.

## Complexity Tracking

None needed.
