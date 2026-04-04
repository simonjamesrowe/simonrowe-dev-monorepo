# Tasks: Blog Post Series Phase 2

**Input**: Design documents from `/specs/013-blog-posts-phase-2/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, quickstart.md

**Tests**: Not requested — no test tasks included.

**Organization**: Tasks are grouped by user story. US1 (content creation) is the MVP. US2 and US3 depend on US1 completion since they verify homepage and search integration.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Phase 1: Setup

**Purpose**: Create the migration script infrastructure and prepare image assets

- [x] T001 Create shell wrapper script at scripts/seed-blog-posts-phase-2.sh following the pattern in scripts/run-content-update.sh — must include: shebang (#!/usr/bin/env bash), set -euo pipefail, SCRIPT_DIR resolution, MongoDB container discovery (docker ps -q --filter "ancestor=mongo:8"), image copy from specs/013-blog-posts-phase-2/attachments/ to backend/uploads/, docker cp of migration script into container, docker exec mongosh execution, cleanup of temp files in container
- [x] T002 Create empty migration script skeleton at scripts/add-blog-posts-phase-2.js with: db.getSiblingDB('simonrowe') connection, idempotency check (look for existing blog by title), helper function to find-or-create a tag by name, helper function to find a skill by name, print statements for progress logging

---

## Phase 2: Foundational (Tag & Skill Resolution)

**Purpose**: Create the 9 new tags and resolve all tag/skill ObjectIds needed by blog posts

**⚠️ CRITICAL**: Blog post insertion depends on tags existing first

- [x] T003 Add tag creation logic to scripts/add-blog-posts-phase-2.js — insert 9 new tags (Auth0, Content Management, Spring AI, MCP (Model Context Protocol), Chatbot, Nginx, Grafana, Observability, DevOps) using the find-or-create helper. Each tag document must include: name, createdAt (new Date()), updatedAt (new Date()), _class ("com.simonrowe.admin.Tag"). Store all tag ObjectIds (new + existing: React, Spring Boot, AI, Docker, Elasticsearch) in a lookup map for DBRef construction
- [x] T004 Add skill lookup logic to scripts/add-blog-posts-phase-2.js — look up existing skills by name (Java, Spring Boot, React, TypeScript, MongoDB, Docker, Nginx, Grafana, Kafka, Elasticsearch) and store their ObjectIds in a lookup map. Skills that don't exist should be logged as warnings and skipped (no creation). Build a helper function that constructs a DBRef array from a list of skill names using the lookup map

**Checkpoint**: Running the migration script at this point should create 9 tags and log found/missing skills

---

## Phase 3: User Story 1 — Read the Phase 2 Blog Series (Priority: P1) 🎯 MVP

**Goal**: 3 published blog posts with full markdown content, tags, skills, and featured images accessible from the blog listing page

**Independent Test**: Navigate to /blogs, find the 3 new posts, open each one, verify content renders with markdown formatting and code blocks

### Implementation for User Story 1

- [x] T005 [P] [US1] Write full markdown content for Post 6 ("Building a CMS from Scratch: Auth0, MDXEditor, and a Media Library") — 800+ words, hybrid voice (first-person narrative + tutorial code walkthroughs). Include full working code examples from the actual codebase: Auth0 Spring Security config, AdminBlogController create endpoint, MDXEditor React setup, media upload/resize logic. Add to scripts/add-blog-posts-phase-2.js as a const string variable (BLOG_6_CONTENT). Reference actual files: backend/src/main/java/com/simonrowe/admin/AdminBlogController.java, frontend/src/pages/admin/BlogEditor.tsx
- [x] T006 [P] [US1] Write full markdown content for Post 7 ("Adding AI Chat to My Portfolio: Spring AI, Gemini, and MCP Tools") — 800+ words, hybrid voice. Include full working code examples: Spring AI/Gemini config, MCP tool endpoint definitions, WebSocket/STOMP streaming setup, Bucket4j rate limiting config, React chat component. Add to scripts/add-blog-posts-phase-2.js as BLOG_7_CONTENT. Reference actual files: backend/src/main/java/com/simonrowe/chat/, frontend/src/components/chat/
- [x] T007 [P] [US1] Write full markdown content for Post 8 ("Production-Ready: Docker Compose, Backups, and Observability") — 800+ words, hybrid voice. Include full working code examples: docker-compose.prod.yml service definitions, nginx.conf reverse proxy config, Google Drive backup/restore Java service, Grafana Alloy config, named volume setup. Add to scripts/add-blog-posts-phase-2.js as BLOG_8_CONTENT. Reference actual files: docker-compose.prod.yml, docker/nginx/nginx.conf, docker/grafana-alloy/config.yaml, backend/src/main/java/com/simonrowe/dataops/
- [x] T008 [US1] Add blog document insertion logic to scripts/add-blog-posts-phase-2.js — for each of the 3 posts, check if a blog with the same title already exists (idempotency). If not, insert a document with: title, shortDescription, content (from BLOG_X_CONTENT vars), published: true, featuredImageUrl (/uploads/blog-phase2-6-cms.jpg, /uploads/blog-phase2-7-ai-chat.jpg, /uploads/blog-phase2-8-production.jpg), tags (DBRef array using tag lookup map), skills (DBRef array using skill lookup map), createdDate (Post 6: 2026-03-20, Post 7: 2026-03-27, Post 8: 2026-04-05), updatedDate (same as createdDate), _class ("com.simonrowe.admin.Blog"). Print confirmation with post title and ID after each insert
- [x] T009 [US1] Generate 3 featured images using the ChatGPT/DALL-E prompts from spec.md and save to specs/013-blog-posts-phase-2/attachments/ as: blog-phase2-6-cms.jpg, blog-phase2-7-ai-chat.jpg, blog-phase2-8-production.jpg. Style must match existing images: clean white background, flat navy blue (#1a365d) and teal illustrations, space for text overlay on right side

**Checkpoint**: Running ./scripts/seed-blog-posts-phase-2.sh should create 9 tags and 3 blog posts. Starting the backend and visiting /blogs should show all 3 new posts with rendered markdown content.

---

## Phase 4: User Story 2 — Discover Posts from the Homepage (Priority: P2)

**Goal**: The 3 new blog posts appear in the homepage latest blogs preview section

**Independent Test**: Load the homepage, verify at least one Phase 2 post appears in the latest blogs section, click through to verify it loads

### Implementation for User Story 2

- [x] T010 [US2] Verify that the blog posts appear in the homepage latest blogs section by confirming the GET /api/blogs/latest?limit=3 endpoint returns the newest Phase 2 posts. If the creation dates (Mar 20, Mar 27, Apr 5) are newer than all existing posts, the 3 Phase 2 posts should be the ones shown. If existing posts have newer dates, adjust the Phase 2 creation dates in scripts/add-blog-posts-phase-2.js to ensure at least one Phase 2 post appears in the top 3. Document the final dates in this task.

**Checkpoint**: Homepage shows at least one Phase 2 blog post in the latest section.

---

## Phase 5: User Story 3 — Find Posts via Search (Priority: P3)

**Goal**: Blog posts are discoverable via site search for key topics

**Independent Test**: Use the site search to search for "Auth0", "Spring AI", and "Docker Compose" — verify relevant posts appear in results

### Implementation for User Story 3

- [x] T011 [US3] Verify search indexing works by restarting the backend (which triggers IndexService full sync) and testing the GET /api/search/blogs?q=Auth0, GET /api/search/blogs?q=Spring+AI, and GET /api/search/blogs?q=Docker+Compose endpoints. If posts don't appear in search results, check that the blog content and tags contain the search terms. No code changes should be needed — the existing search infrastructure handles this automatically.

**Checkpoint**: All 3 blog posts are discoverable via site search.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final validation and cleanup

- [x] T012 Review all 3 blog posts for content quality: check word count (800+ each), verify code examples are syntactically correct, confirm links to referenced files/PRs are accurate, ensure consistent tone (hybrid first-person + tutorial)
- [x] T013 Run quickstart.md validation: execute the full seed workflow from scratch (./scripts/seed-blog-posts-phase-2.sh), verify idempotency by running it a second time (should skip all inserts), verify blog listing/detail/search all work

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on T001-T002 (script skeleton)
- **User Story 1 (Phase 3)**: Depends on Phase 2 (tags/skills must exist for DBRef construction)
  - T005, T006, T007 can run in parallel (3 independent blog content files)
  - T008 depends on T005-T007 (needs content variables)
  - T009 is independent (image generation)
- **User Story 2 (Phase 4)**: Depends on US1 (posts must exist)
- **User Story 3 (Phase 5)**: Depends on US1 (posts must be indexed)
- **Polish (Phase 6)**: Depends on all user stories

### User Story Dependencies

- **User Story 1 (P1)**: Depends on Foundational (Phase 2) — no dependencies on other stories
- **User Story 2 (P2)**: Depends on US1 (blog posts must be inserted to appear on homepage)
- **User Story 3 (P3)**: Depends on US1 (blog posts must be inserted to be indexed for search)

### Parallel Opportunities

- T005, T006, T007: All 3 blog content writing tasks can run in parallel (different content, same file but different variables)
- T009 (image generation) can run in parallel with T005-T008
- T001 and T002 can be developed in parallel (shell wrapper vs JS skeleton)

---

## Parallel Example: User Story 1

```bash
# Launch all blog content writing tasks together:
Task: "Write Post 6 content (CMS) — BLOG_6_CONTENT in scripts/add-blog-posts-phase-2.js"
Task: "Write Post 7 content (AI Chat) — BLOG_7_CONTENT in scripts/add-blog-posts-phase-2.js"
Task: "Write Post 8 content (Production) — BLOG_8_CONTENT in scripts/add-blog-posts-phase-2.js"

# Generate images in parallel with content writing:
Task: "Generate 3 featured images using DALL-E prompts"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (script infrastructure)
2. Complete Phase 2: Foundational (tags + skill lookups)
3. Complete Phase 3: User Story 1 (blog content + insertion + images)
4. **STOP and VALIDATE**: Run seed script, check /blogs for 3 new posts
5. Posts are readable and functional

### Incremental Delivery

1. Setup + Foundational → Script infrastructure ready
2. Add User Story 1 → 3 blog posts published (MVP!)
3. Add User Story 2 → Verify homepage integration
4. Add User Story 3 → Verify search integration
5. Polish → Content review and idempotency validation

---

## Notes

- [P] tasks = different files or independent content, no dependencies
- [Story] label maps task to specific user story for traceability
- US2 and US3 are verification-only — no code changes expected, just confirming existing infrastructure handles the new content
- Blog content is the bulk of the work (T005-T007) — these are the largest tasks
- Image generation (T009) is a manual/external step using ChatGPT/DALL-E
- The migration script must be idempotent — safe to run multiple times
