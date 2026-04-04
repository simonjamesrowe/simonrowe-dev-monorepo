# Implementation Plan: Blog Post Series Phase 2

**Branch**: `013-blog-posts-phase-2` | **Date**: 2026-04-03 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/013-blog-posts-phase-2/spec.md`

## Summary

Create 3 blog posts covering features built March-April 2026 (CMS, AI Chat, Docker deployment), plus 9 new tags. Content uses hybrid voice (first-person narrative + tutorial-style code walkthroughs with full working examples). Implementation follows the existing MongoDB migration script pattern: a `.js` mongosh script with a `.sh` wrapper, plus featured images uploaded to the backend uploads directory.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript (frontend) — no code changes needed; content-only feature
**Primary Dependencies**: MongoDB 8 (mongosh for migration), Docker (container exec for script runner)
**Storage**: MongoDB `blogs` and `tags` collections
**Testing**: Manual verification — blog listing, detail pages, search indexing
**Target Platform**: Web (simonrowe.dev)
**Project Type**: Web application (monorepo)
**Performance Goals**: N/A — content insertion, no new runtime code
**Constraints**: Blog content must be 800+ words each; featured images must match existing style
**Scale/Scope**: 3 blog posts, 9 new tags, 3 featured images

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Monorepo with Separate Containers | PASS | No container changes — content only |
| II. Modern Java & React Stack | PASS | Uses existing MongoDB/blog system, no new dependencies |
| III. Quality Gates | PASS | No code changes — migration script only |
| IV. Observability & Operability | PASS | No runtime changes |
| V. Simplicity & Incremental Delivery | PASS | Direct data insertion, simplest approach |
| VI. Admin CMS UX Standards | PASS | Blogs follow existing @DBRef/DTO patterns |
| VII. Backup & Restore | PASS | No changes to backup/restore scripts |
| VIII. Shell Scripting Standards | PASS | Shell wrapper follows existing `run-content-update.sh` pattern with `set -euo pipefail`, container discovery, docker cp/exec, cleanup |

All gates pass. No violations to track.

## Project Structure

### Documentation (this feature)

```text
specs/013-blog-posts-phase-2/
├── spec.md              # Feature specification
├── plan.md              # This file
├── research.md          # Phase 0 research
├── data-model.md        # Phase 1 data model
├── quickstart.md        # Phase 1 quickstart guide
├── contracts/           # Phase 1 API contracts (N/A — uses existing APIs)
│   └── README.md
└── tasks.md             # Phase 2 tasks (via /speckit.tasks)
```

### Source Code (repository root)

```text
scripts/
├── seed-blog-posts-phase-2.sh    # Shell wrapper (new)
└── add-blog-posts-phase-2.js     # MongoDB migration script (new)

specs/013-blog-posts-phase-2/
└── attachments/
    ├── blog-phase2-6-cms.jpg         # Featured image: CMS post
    ├── blog-phase2-7-ai-chat.jpg     # Featured image: AI Chat post
    └── blog-phase2-8-production.jpg  # Featured image: Production post
```

**Structure Decision**: This is a content-only feature. No backend or frontend code changes required. The blog system already supports all needed functionality. Implementation consists of:
1. A MongoDB migration script to insert tags and blog posts
2. A shell wrapper script to execute it
3. Featured images generated externally and placed in uploads

## Implementation Approach

### Data Insertion Strategy

Use a MongoDB migration script (`mongosh` format) following the established pattern from `scripts/update-profile-job-content.js`:

1. **Idempotency**: Check if posts already exist (by title) before inserting
2. **Tag creation**: Insert 9 new tags, skip any that already exist (by name)
3. **Tag/Skill resolution**: Look up tag and skill ObjectIds to create proper `$ref` DBRef entries
4. **Blog creation**: Insert 3 blog documents with full markdown content, DBRef tag/skill arrays, and metadata
5. **Image handling**: Featured images copied to `backend/uploads/` by the shell wrapper

### Content Writing Guidelines

Each blog post follows the hybrid voice established in clarifications:
- **First-person narrative framing**: "I built...", "Here's what I learned..."
- **Tutorial-style code sections**: Full working examples from the actual codebase
- **Structure**: Introduction → motivation → implementation walkthrough with code → lessons learned
- **Length**: 800+ words each
- **Code blocks**: Use fenced markdown with language identifiers for syntax highlighting

### DBRef Format

Blog documents must use MongoDB's `$ref` format for tags and skills:
```javascript
tags: [
  { "$ref": "tags", "$id": ObjectId("...") },
  { "$ref": "tags", "$id": ObjectId("...") }
]
```

### Search Indexing

No manual indexing needed. The existing `IndexService` performs a full sync on application startup, which will pick up new blog posts automatically. The periodic 4-hour re-sync also ensures eventual consistency.

## Complexity Tracking

> No violations — table not needed.
