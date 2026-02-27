# Implementation Plan: Blog Post Series - Rebuilding simonrowe.dev with AI

**Branch**: `010-blog-posts` | **Date**: 2026-02-27 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/010-blog-posts/spec.md`

## Summary

Create 5 blog posts documenting the rebuild of simonrowe.dev using Claude Code, Conductor, and SpecKit. Each post includes markdown content with code examples, a featured image, linked tags (via DBRef), and linked skills (via DBRef). Posts are inserted via a MongoDB migration script and indexed in Elasticsearch via the existing IndexService on application startup. No backend or frontend code changes required — this feature is purely data/content.

## Technical Context

**Language/Version**: JavaScript (MongoDB migration script), Java 21 (existing backend, no changes)
**Primary Dependencies**: MongoDB (data insertion), Elasticsearch (auto-indexed via IndexService)
**Storage**: MongoDB (`blogs`, `tags`, `skills` collections)
**Testing**: Manual verification via API endpoints and frontend; migration script is idempotent
**Target Platform**: Web (existing simonrowe.dev deployment)
**Project Type**: Web (monorepo — backend + frontend)
**Performance Goals**: N/A — content insertion, no new runtime code
**Constraints**: Blog posts must use existing data model (DBRef for tags/skills, markdown content, featuredImageUrl)
**Scale/Scope**: 5 blog posts, 8 new tags, 5 featured images, 1 migration script

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Monorepo with Separate Containers | PASS | No container changes. Migration script runs via mongosh in existing MongoDB container |
| II. Modern Java & React Stack | PASS | Uses MongoDB (primary persistence), no new technologies introduced. Content managed through application code + MongoDB |
| III. Quality Gates | PASS | No code changes to backend or frontend, so no test/coverage/style gates apply. Migration script is idempotent |
| IV. Observability & Operability | N/A | No runtime code changes |
| V. Simplicity & Incremental Delivery | PASS | Simplest approach: migration script + existing blog system. No new abstractions, no new APIs, no backend changes |
| VI. Shell Scripting Standards | PASS | Shell wrapper script uses `#!/usr/bin/env bash` with `set -euo pipefail`. Handles container discovery, file copy, mongosh execution, cleanup |

**Post-Phase 1 re-check**: All gates still pass. No code changes to existing services. Pure data insertion using established patterns.

## Project Structure

### Documentation (this feature)

```text
specs/010-blog-posts/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (README only — no new APIs)
│   └── README.md
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
scripts/
├── seed-blog-posts.sh         # NEW: Shell wrapper (entry point)
└── add-blog-posts.js          # NEW: MongoDB migration script (run via wrapper)

backend/
└── uploads/
    ├── blog-rebuild-1-specification.png  # NEW: Featured image post 1
    ├── blog-rebuild-2-foundation.png     # NEW: Featured image post 2
    ├── blog-rebuild-3-parallel.png       # NEW: Featured image post 3
    ├── blog-rebuild-4-migration.png      # NEW: Featured image post 4
    └── blog-rebuild-5-lessons.png        # NEW: Featured image post 5
```

**Structure Decision**: No new backend or frontend source code directories. The migration script follows the existing `scripts/` convention (`add-global-job-data.js` + `seed-global-job.sh`). Shell wrapper is the entry point; `.js` file is never run directly. Featured images go in `backend/uploads/` to be served by Spring's ResourceHandlerRegistry.

## Implementation Approach

### Phase 1: Shell Wrapper and Migration Script

**Shell wrapper** (`scripts/seed-blog-posts.sh`) following the `seed-global-job.sh` pattern:
1. `#!/usr/bin/env bash` with `set -euo pipefail`
2. Resolve `SCRIPT_DIR` and `PROJECT_DIR`
3. Copy featured images from `specs/010-blog-posts/attachments/` to `backend/uploads/` (if not already present)
4. Find running MongoDB container (`docker ps -q --filter "ancestor=mongo:8"`)
5. Copy `add-blog-posts.js` into the container via `docker cp`
6. Execute via `docker exec mongosh --quiet`
7. Clean up temp file from container

**MongoDB migration script** (`scripts/add-blog-posts.js`) following the `add-global-job-data.js` pattern:
1. **Idempotency check**: Query for existing blog posts with matching titles; skip if found
2. **Insert new tags**: 8 new tags into the `tags` collection, skipping any that already exist (unique index on `name`)
3. **Resolve references**: Look up tag `_id` values by name and skill `_id` values by name from the `skills` collection
4. **Insert blog posts**: 5 documents with full markdown content, DBRef arrays for tags and skills, featured image URLs, and `published: true`

### Phase 2: Blog Post Content

Each blog post will contain:
- **Title and short description** for listing cards
- **Full markdown content** (800+ words) with:
  - Narrative text explaining the journey
  - 2-4 fenced code blocks with language tags (```java, ```typescript, ```yaml, etc.)
  - Links to Conductor (https://www.conductor.build/) and SpecKit (https://github.com/github/spec-kit)
  - References to specific PRs and commits
- **Featured image URL** pointing to `backend/uploads/`
- **Tags** via DBRef to the `tags` collection
- **Skills** via DBRef to the `skills` collection

### Phase 3: Featured Images

Create 5 featured images for blog post cards. These will be simple branded images placed in `backend/uploads/`.

### Phase 4: Verification

1. Run shell wrapper: `./scripts/seed-blog-posts.sh`
2. Restart backend to trigger Elasticsearch reindex
3. Verify blog listing, detail pages, search, and homepage preview

## Blog Post Content Outlines

### Post 1: "From Zero to Specification: How I Used AI to Plan My Entire Website Rebuild"

**Short description**: How SpecKit and spec-driven development helped plan 9 features for simonrowe.dev before writing a single line of code.

**Code examples**:
- SpecKit spec template structure (markdown)
- The `specs/` directory tree showing all 9 feature specs
- Example acceptance scenario from a spec

**Tags**: SpecKit, Spec-Driven Development, AI
**Skills**: (none — specification-focused)
**Key themes**: Why spec-first beats "vibe coding", SpecKit slash commands, structuring AI work with specifications

---

### Post 2: "Building the Foundation: Infrastructure and First Features in a Weekend"

**Short description**: Setting up a Spring Boot + React monorepo with MongoDB, building a profile homepage and blog system in two days using Claude Code and Conductor.

**Code examples**:
- Blog entity record (`Blog.java`) showing the Spring Data MongoDB model
- Docker Compose service definition snippet
- React component snippet (e.g., BlogCard or Sidebar)

**Tags**: Spring Boot, React, MongoDB, Conductor, AI
**Skills**: Java, Spring Boot, React, MongoDB, Docker, Typescript
**Key themes**: Monorepo structure decisions, Conductor for managing parallel workspaces, Claude Code generating full-stack features

---

### Post 3: "Shipping Six Features in a Day: Parallel AI Agents with Conductor"

**Short description**: How Conductor's parallel workspace model enabled shipping skills, blog system, site search, and contact form features all in a single day.

**Code examples**:
- Elasticsearch index configuration (`blog-index-settings.json`)
- IndexService search document mapping snippet
- Contact form with reCAPTCHA validation (React Hook Form + Zod)

**Tags**: Conductor, AI Productivity, Parallel Development
**Skills**: React, Elastic Search, Java, Spring Boot, MongoDB
**Key themes**: Conductor's isolated git worktrees, reviewing AI-generated PRs, parallel vs sequential development, merge conflict management

---

### Post 4: "Interactive Tours, Data Migration, and the Finishing Touches"

**Short description**: Migrating 18 blog posts from Strapi, building an interactive tour system, and solving real-world integration challenges with AI agents.

**Code examples**:
- Migration script excerpt (Strapi to Spring Boot schema transformation)
- CORS configuration fix
- Tour step data model

**Tags**: Data Migration, AI
**Skills**: MongoDB, Docker, React, Javascript
**Key themes**: Legacy CMS migration patterns, solving CORS issues, interactive UI features, updating employment data with AI skills

---

### Post 5: "Lessons Learned: What Worked, What Didn't, and What's Next"

**Short description**: Honest retrospective on rebuilding a personal website with AI coding agents — the wins, the struggles, and the roadmap ahead.

**Code examples**:
- Project statistics summary (lines of code, test count, feature count)
- Example test using Testcontainers
- CI/CD workflow snippet

**Tags**: SpecKit, Conductor, Retrospective, AI Productivity
**Skills**: Java, Spring Boot, React, MongoDB, Elastic Search, Docker
**Key themes**: When AI agents excel (boilerplate, scaffolding, data models) vs struggle (complex integration, debugging), spec-first workflow benefits, Conductor tips, future plans (CMS, auth, AI chat)
