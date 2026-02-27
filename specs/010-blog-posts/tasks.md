# Tasks: Blog Post Series - Rebuilding simonrowe.dev with AI

**Input**: Design documents from `/specs/010-blog-posts/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, quickstart.md

**Tests**: Not explicitly requested. Manual verification tasks included.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Phase 1: Setup (Shell Wrapper and Script Skeleton)

**Purpose**: Create the shell wrapper entry point and migration script skeleton following the `seed-global-job.sh` + `add-global-job-data.js` pattern

- [X] T001 Create shell wrapper script at `scripts/seed-blog-posts.sh` following the `scripts/seed-global-job.sh` pattern: `#!/usr/bin/env bash` with `set -euo pipefail`, resolve `SCRIPT_DIR` and `PROJECT_DIR`, copy featured images from `specs/010-blog-posts/attachments/` to `backend/uploads/` (skip if already present), find running MongoDB container via `docker ps -q --filter "ancestor=mongo:8"` (exit with error if not found), `docker cp` the migration script into the container, execute via `docker exec mongosh --quiet`, clean up temp file from container, print completion message
- [X] T002 [P] Create migration script skeleton at `scripts/add-blog-posts.js`: connect to `simonrowe` database via `db.getSiblingDB('simonrowe')`, add idempotency check that queries for existing blog posts with title matching "From Zero to Specification" and skips if found, add placeholder sections for tag insertion, skill resolution, and blog post insertion with print statements for progress, add migration summary at the end

---

## Phase 2: Foundational (Tags and Skill Resolution)

**Purpose**: Insert new tags and resolve tag/skill ObjectId references needed by all blog posts. MUST complete before blog post insertion.

- [X] T003 Add tag insertion logic to `scripts/add-blog-posts.js`: insert 8 new tags into the `tags` collection (SpecKit, Conductor, Spec-Driven Development, AI Productivity, Parallel Development, Data Migration, Retrospective, AI), use `updateOne` with `$setOnInsert` and `upsert: true` to skip existing tags (unique index on `name`), print count of tags inserted vs skipped
- [X] T004 Add tag reference resolution to `scripts/add-blog-posts.js`: query the `tags` collection for all tag names needed across all 5 posts (the 8 new tags plus existing tags: Spring Boot, React, MongoDB), build a `tagsByName` lookup map of `{ name: ObjectId }`, build DBRef helper function `tagRef(name)` that returns `{ "$ref": "tags", "$id": tagsByName[name] }`, print count of resolved tags and warn on any unresolved
- [X] T005 Add skill reference resolution to `scripts/add-blog-posts.js`: query the `skills` collection for skill names needed (Java, Spring Boot, React, MongoDB, Docker, Typescript, Elastic Search, Javascript, Kafka), build a `skillsByName` lookup map of `{ name: ObjectId }`, build DBRef helper function `skillRef(name)` that returns `{ "$ref": "skills", "$id": skillsByName[name] }`, print count of resolved skills and warn on any unresolved

**Checkpoint**: Tags exist in MongoDB, tag and skill ObjectIds are resolved. Blog post insertion can proceed.

---

## Phase 3: User Story 1 - Read the Complete Blog Series (Priority: P1) MVP

**Goal**: All 5 blog posts are published in MongoDB with full markdown content (800+ words each), code examples, featured images, linked tags (DBRef), and linked skills (DBRef).

**Independent Test**: Run `./scripts/seed-blog-posts.sh`, then `curl http://localhost:8080/api/blogs | jq '.[].title'` to verify all 5 posts appear. Open any post detail page and confirm markdown renders with code blocks and syntax highlighting.

### Implementation for User Story 1

- [X] T006 [P] [US1] Create featured image placeholder files in `specs/010-blog-posts/attachments/`: create 5 PNG images named `blog-rebuild-1-specification.png`, `blog-rebuild-2-foundation.png`, `blog-rebuild-3-parallel.png`, `blog-rebuild-4-migration.png`, `blog-rebuild-5-lessons.png`. These should be simple branded placeholder images (can be solid colour with text overlay or minimal graphics). The shell wrapper (T001) copies these to `backend/uploads/` at runtime.

- [X] T007 [P] [US1] Write blog post 1 content in `scripts/add-blog-posts.js` — "From Zero to Specification: How I Used AI to Plan My Entire Website Rebuild". Title: as stated. Short description: "How SpecKit and spec-driven development helped plan 9 features for simonrowe.dev before writing a single line of code." Content (800+ words, markdown): introduce the problem (rebuilding a personal website), explain spec-driven development philosophy, describe SpecKit (link to https://github.com/github/spec-kit) and its slash commands (`/speckit.specify`, `/speckit.plan`, `/speckit.tasks`, `/speckit.implement`), walk through creating the 9 feature specs, include code examples: (1) the specs directory tree structure, (2) an excerpt from the spec template showing acceptance scenarios, (3) an example user story from one of the specs. Explain why planning first with AI beats "vibe coding". Reference PR #1. Tags: SpecKit, Spec-Driven Development, AI. Skills: none. Featured image: `/uploads/blog-rebuild-1-specification.png`. CreatedDate: `ISODate("2026-02-27T08:00:00Z")`.

- [X] T008 [P] [US1] Write blog post 2 content in `scripts/add-blog-posts.js` — "Building the Foundation: Infrastructure and First Features in a Weekend". Title: as stated. Short description: "Setting up a Spring Boot + React monorepo with MongoDB, building a profile homepage and blog system in two days using Claude Code and Conductor." Content (800+ words, markdown): describe the monorepo structure decision (backend/ + frontend/), introduce Conductor (link to https://www.conductor.build/) for parallel agent workspaces, walk through building the infrastructure skeleton (PR #2), profile homepage (PR #3), and blog system (PR #5) over Feb 23-24. Include code examples: (1) the Blog entity record from `Blog.java` showing `@Document`, `@DBRef`, `@CompoundIndex`, (2) a Docker Compose service definition snippet for the monorepo services, (3) a React component snippet (BlogCard or Sidebar). Explain how Conductor's isolated workspaces enabled parallel work. Tags: Spring Boot, React, MongoDB, Conductor, AI. Skills: Java, Spring Boot, React, MongoDB, Docker, Typescript. Featured image: `/uploads/blog-rebuild-2-foundation.png`. CreatedDate: `ISODate("2026-02-27T09:00:00Z")`.

- [X] T009 [P] [US1] Write blog post 3 content in `scripts/add-blog-posts.js` — "Shipping Six Features in a Day: Parallel AI Agents with Conductor". Title: as stated. Short description: "How Conductor's parallel workspace model enabled shipping skills, blog system, site search, and contact form features all in a single day." Content (800+ words, markdown): describe the Feb 24 sprint where PRs #4-#8 all landed (skills & employment, blog system, site search, contact form, plus a test chore), explain Conductor's parallel workspace model with isolated git worktrees, discuss the review and merge workflow for AI-generated PRs, walk through the Elasticsearch integration for site search. Include code examples: (1) the Elasticsearch blog index settings JSON (`blog-index-settings.json`), (2) the IndexService `blogToBlogDocument` mapping method, (3) a contact form component snippet with React Hook Form + Zod validation. Discuss merge conflict management and when to run agents in parallel vs sequentially. Tags: Conductor, AI Productivity, Parallel Development. Skills: React, Elastic Search, Java, Spring Boot, MongoDB. Featured image: `/uploads/blog-rebuild-3-parallel.png`. CreatedDate: `ISODate("2026-02-27T10:00:00Z")`.

- [X] T010 [P] [US1] Write blog post 4 content in `scripts/add-blog-posts.js` — "Interactive Tours, Data Migration, and the Finishing Touches". Title: as stated. Short description: "Migrating 18 blog posts from Strapi, building an interactive tour system, and solving real-world integration challenges with AI agents." Content (800+ words, markdown): describe the interactive tour feature (PR #9), the CORS/API base URL fix (PR #10), the Strapi data migration, and the global job position with AI skills (PR #12). Walk through the migration from Strapi CMS to the new Spring Boot stack. Include code examples: (1) an excerpt from `migrate-strapi-data.js` showing the blog transformation with DBRef tag mapping, (2) a CORS configuration snippet, (3) the tour step data model showing `targetSelector`, `title`, `description`, `position`. Discuss real-world challenges when AI agents generate code that needs integration fixes. Tags: Data Migration, AI. Skills: MongoDB, Docker, React, Javascript. Featured image: `/uploads/blog-rebuild-4-migration.png`. CreatedDate: `ISODate("2026-02-27T11:00:00Z")`.

- [X] T011 [P] [US1] Write blog post 5 content in `scripts/add-blog-posts.js` — "Lessons Learned: What Worked, What Didn't, and What's Next". Title: as stated. Short description: "Honest retrospective on rebuilding a personal website with AI coding agents — the wins, the struggles, and the roadmap ahead." Content (800+ words, markdown): honest retrospective covering what worked well (AI excels at boilerplate, scaffolding, data models, test generation; spec-first workflow keeps agents focused; Conductor enables massive parallelism), what didn't work well (complex integration bugs, CORS issues, occasional hallucinated imports, merge conflicts from parallel agents), and what's planned next (content management system via spec 007, Auth0 integration, AI chat with MCP tools). Include code examples: (1) project stats summary showing lines of code, test count, feature count, PR count, (2) an example Testcontainers integration test snippet, (3) a GitHub Actions CI workflow excerpt. Provide practical tips for anyone wanting to try this approach. Tags: SpecKit, Conductor, Retrospective, AI Productivity. Skills: Java, Spring Boot, React, MongoDB, Elastic Search, Docker. Featured image: `/uploads/blog-rebuild-5-lessons.png`. CreatedDate: `ISODate("2026-02-27T12:00:00Z")`.

- [X] T012 [US1] Add blog post insertion logic to `scripts/add-blog-posts.js`: after the content variables from T007-T011 are defined, insert all 5 blog posts into the `blogs` collection using `insertOne()` for each. Each document must include: `_id: ObjectId()`, `title`, `shortDescription`, `content` (the full markdown string), `published: true`, `featuredImageUrl` (e.g., `/uploads/blog-rebuild-1-specification.png`), `createdDate` (staggered ISODate from T007-T011), `updatedDate` (same as createdDate), `tags` (array of DBRef objects using `tagRef()` helper from T004), `skills` (array of DBRef objects using `skillRef()` helper from T005, empty array for post 1). Print each inserted title. Add summary at end: total blogs count, total tags count.

**Checkpoint**: All 5 blog posts exist in MongoDB with `published: true`, proper DBRef tags and skills, featured images, and full markdown content. Run `./scripts/seed-blog-posts.sh` and verify via `curl http://localhost:8080/api/blogs`.

---

## Phase 4: User Story 2 - Discover from Homepage (Priority: P2)

**Goal**: Blog posts from the rebuild series appear in the homepage latest posts preview section.

**Independent Test**: Load the homepage, verify at least one rebuild series post appears in the latest blogs section, click through to confirm full post renders correctly.

### Implementation for User Story 2

- [X] T013 [US2] Verify homepage integration by running `./scripts/seed-blog-posts.sh`, restarting the backend, and confirming the latest blogs API returns rebuild series posts: `curl 'http://localhost:8080/api/blogs/latest?limit=3' | jq '.[].title'`. The posts should appear automatically because they have `published: true` and recent `createdDate` values. No code changes needed — this is a verification-only task. Document the verification results in the task notes.

**Checkpoint**: Homepage shows rebuild series blog posts in the latest preview section.

---

## Phase 5: User Story 3 - Find Series Posts via Search (Priority: P3)

**Goal**: Visitors can find blog posts by searching for "Claude Code", "Conductor", or "SpecKit" using site search.

**Independent Test**: Use site search to search for "Claude Code", "Conductor", and "SpecKit" and verify relevant posts appear.

### Implementation for User Story 3

- [X] T014 [US3] Verify search integration by restarting the backend to trigger `IndexService.fullSyncBlogIndex()` and `IndexService.fullSyncSiteIndex()`, then confirm searches return rebuild series posts: `curl 'http://localhost:8080/api/search/blogs?q=Claude+Code'`, `curl 'http://localhost:8080/api/search/blogs?q=SpecKit'`, `curl 'http://localhost:8080/api/search/blogs?q=Conductor'`. Posts should appear automatically because the IndexService reads all published blogs from MongoDB and indexes them in Elasticsearch. No code changes needed — this is a verification-only task. Document the verification results.

**Checkpoint**: Site search returns rebuild series posts for key terms.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final verification, idempotency validation, and documentation

- [X] T015 Run idempotency test: execute `./scripts/seed-blog-posts.sh` twice and verify no duplicate blog posts or tags are created. Check blog count remains at expected total (existing + 5 new). Check tag count remains stable.
- [X] T016 Run full frontend verification per `specs/010-blog-posts/quickstart.md` section 7: open `http://localhost:3000`, navigate to `/blogs` to verify all 5 posts appear with images and tags, click each post to verify markdown renders with code blocks and syntax highlighting, use site search for "SpecKit" to verify results, check homepage for latest blog preview.
- [X] T017 Verify each blog post is at least 800 words: query MongoDB for all 5 posts and count words in the `content` field. All must meet the 800-word minimum per SC-002.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on T002 (migration script skeleton exists)
- **User Story 1 (Phase 3)**: Depends on Phase 2 (tags/skills resolved). T006-T011 can run in parallel. T012 depends on T007-T011 (content written) and T004-T005 (references resolved)
- **User Story 2 (Phase 4)**: Depends on Phase 3 (blog posts inserted and backend restarted)
- **User Story 3 (Phase 5)**: Depends on Phase 3 (blog posts inserted and backend restarted with Elasticsearch reindex)
- **Polish (Phase 6)**: Depends on Phases 3-5

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) — no dependencies on other stories
- **User Story 2 (P2)**: Depends on US1 completion (posts must exist to appear on homepage)
- **User Story 3 (P3)**: Depends on US1 completion (posts must exist to be indexed for search)

### Within User Story 1

- T006 (images), T007-T011 (blog content) can ALL run in parallel — they write to different content sections
- T012 (insertion logic) MUST wait for T007-T011 (content must exist before insertion)

### Parallel Opportunities

- T001 and T002 can run in parallel (different files)
- T003, T004, T005 can run sequentially within the same file (same section of `add-blog-posts.js`)
- T006 through T011 can all run in parallel (different content, same file but different sections)
- T013 and T014 can run in parallel (different verification targets)
- T015, T016, T017 can run in parallel (independent verification tasks)

---

## Parallel Example: User Story 1 Content Writing

```bash
# Launch all 5 blog post content tasks in parallel (different content sections):
Task: "T007 Write blog post 1 — From Zero to Specification"
Task: "T008 Write blog post 2 — Building the Foundation"
Task: "T009 Write blog post 3 — Shipping Six Features in a Day"
Task: "T010 Write blog post 4 — Interactive Tours & Migration"
Task: "T011 Write blog post 5 — Lessons Learned"

# Then run insertion logic (depends on all 5 above):
Task: "T012 Add blog post insertion logic"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (shell wrapper + script skeleton)
2. Complete Phase 2: Foundational (tags + reference resolution)
3. Complete Phase 3: User Story 1 (all 5 blog posts with content, images, tags, skills)
4. **STOP and VALIDATE**: Run `./scripts/seed-blog-posts.sh`, verify posts via API and frontend
5. Deploy if ready — posts are live

### Incremental Delivery

1. Setup + Foundational → Script infrastructure ready
2. User Story 1 → 5 blog posts live, readable, with code examples and images → MVP!
3. User Story 2 → Homepage shows rebuild posts → Verification only
4. User Story 3 → Search finds rebuild posts → Verification only (Elasticsearch auto-indexes)
5. Polish → Idempotency, word count, and full frontend verification

### Suggested MVP Scope

User Story 1 is the MVP. Once the 5 blog posts are in MongoDB with `published: true`, the existing blog system automatically handles listing, detail pages, homepage preview, and search indexing. User Stories 2 and 3 are verification-only tasks confirming the existing system works with the new data.

---

## Notes

- [P] tasks = different files or different content sections, no dependencies
- [Story] label maps task to specific user story for traceability
- No backend or frontend code changes are needed — this is purely data/content
- The migration script MUST be idempotent (safe to run multiple times)
- Blog posts use DBRef format `{ "$ref": "tags", "$id": ObjectId("...") }` for tags and skills
- The `skills` collection (flat `{_id, name}`) is separate from `skill_groups.skills[]` (embedded)
- Elasticsearch indexing happens automatically via `IndexService.fullSyncBlogIndex()` on backend restart
- All code examples in blog posts should be drawn from actual project source code
- External links: Conductor (https://www.conductor.build/), SpecKit (https://github.com/github/spec-kit)
