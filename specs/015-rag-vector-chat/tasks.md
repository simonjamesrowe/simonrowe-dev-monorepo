# Tasks: RAG-Enhanced AI Chat with Vector Embeddings

**Input**: Design documents from `/specs/015-rag-vector-chat/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Organization**: Tasks grouped by user story. US2 (Content Embedding) is sequenced before US1 (Semantic Chat) because US1 depends on embedded content existing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Dependencies, configuration, and Docker Compose changes to enable the vector store and OpenAI integration.

- [x] T001 Add `spring-ai-starter-vector-store-elasticsearch` dependency to `gradle/libs.versions.toml` and `backend/build.gradle.kts`
- [x] T002 Update `backend/src/main/resources/application.yml` to switch from Groq to OpenAI: replace `GROQ_API_KEY` with `OPENAI_API_KEY`, remove `base-url: https://api.groq.com/openai`, set `chat.options.model: gpt-5.4-nano`, add `embedding.options.model: text-embedding-3-small`, add `spring.ai.vectorstore.elasticsearch` config (index-name: content-embeddings, dimensions: 1536, similarity: cosine)
- [x] T003 [P] Update `docker-compose.yml` elasticsearch service: add `path.repo: /usr/share/elasticsearch/backups` environment variable and `elasticsearch-backups:/usr/share/elasticsearch/backups` volume mount; add `elasticsearch-backups` to named volumes
- [x] T004 [P] Update `docker-compose.prod.yml` elasticsearch service: same `path.repo` environment variable and `elasticsearch-backups` volume mount as T003

**Checkpoint**: Application starts with OpenAI as AI provider and Elasticsearch vector store auto-configured. Chat works with GPT 5.4 Nano.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core embedding infrastructure that all user stories depend on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [x] T005 Add `CODE_EXAMPLE` to `ContentType` enum in `backend/src/main/java/com/simonrowe/events/ContentChangeEvent.java`
- [x] T006 [P] Add `REEMBED_CONTENT` to `OperationType` enum in `backend/src/main/java/com/simonrowe/dataops/OperationType.java`
- [x] T007 Create `backend/src/main/java/com/simonrowe/embedding/EmbeddingConfig.java` — Spring configuration class that defines a `TokenTextSplitter` bean (chunkSize: 500, minChunkSizeChars: 100, minChunkLengthToEmbed: 5, maxNumChunks: 100, keepSeparator: true). Note: `ElasticsearchVectorStore` and `EmbeddingModel` are auto-configured by the starters from application.yml properties.
- [x] T008 Create `backend/src/main/java/com/simonrowe/embedding/EmbeddingService.java` — service that provides: `embedContent(String sourceId, String sourceType, String title, String content, Map<String, String> metadata)` to chunk text via `TokenTextSplitter`, create `Document` objects with metadata (sourceId, sourceType, title, tags, skills, language, url), and add to `VectorStore`; `removeContent(String sourceId)` to delete all chunks matching a sourceId filter; content-type-specific methods: `embedBlog(Blog)`, `embedJob(Job)`, `embedSkill(Skill, SkillGroup)` that build metadata and call `embedContent`; inject `VectorStore` and `TokenTextSplitter`

**Checkpoint**: Foundation ready — EmbeddingService can chunk and store/remove vectors. User story implementation can begin.

---

## Phase 3: User Story 2 — Content Embedding & Ingestion (Priority: P1)

**Goal**: Automatically chunk and embed content into the vector store when created/updated/deleted via admin CMS.

**Independent Test**: Create or update a blog post in admin CMS, then verify embeddings exist in Elasticsearch `content-embeddings` index via `curl http://localhost:9200/content-embeddings/_search`.

### Implementation for User Story 2

- [x] T009 [US2] Create `backend/src/main/java/com/simonrowe/embedding/EmbeddingChangeConsumer.java` — Kafka consumer with `@KafkaListener(topics = "content-changes", groupId = "embedding-indexer")` and `@RetryableTopic` (same retry config as existing `ContentChangeConsumer`). On CREATED/UPDATED: fetch entity from repository by contentId and call appropriate `EmbeddingService.embed*()` method. On DELETED: call `EmbeddingService.removeContent(contentId)`. Handle all four content types (BLOG, JOB, SKILL, CODE_EXAMPLE). Follow the pattern in `backend/src/main/java/com/simonrowe/events/ContentChangeConsumer.java`.
- [x] T010 [US2] Wire up `ContentChangeEvent` publishing from admin controllers. In each admin controller (`AdminBlogController`, `AdminJobController`, `AdminSkillController`, `AdminSkillGroupController`), publish a `ContentChangeEvent` to the `content-changes` Kafka topic after create/update/delete operations using `KafkaTemplate`. Follow the existing `ContentChangeEvent` record structure with appropriate `EventType` and `ContentType`.
- [x] T011 [US2] Add periodic full vector sync to `EmbeddingService` — create a `@Scheduled` method (cron: every 4 hours, matching existing search sync) that iterates all published blogs, all jobs, all skills (with groups), and all code examples, removing stale embeddings and re-embedding any that are missing or outdated. Add `fullVectorSync()` method.
- [x] T012 [US2] Handle unpublished blog removal — in `EmbeddingChangeConsumer`, when a BLOG event arrives and the blog is not found or `published == false`, call `EmbeddingService.removeContent(contentId)` to clean up stale embeddings.

**Checkpoint**: Content changes in admin CMS automatically trigger embedding via Kafka. Periodic sync catches any missed updates.

---

## Phase 4: User Story 1 — Semantic Chat Search (Priority: P1)

**Goal**: Chat responses use semantic vector similarity search to find relevant content, even when visitor wording differs from source content.

**Independent Test**: Ask the chat "What experience does Simon have with event-driven architectures?" and verify the response references relevant blog posts/jobs/skills even if those don't contain the exact phrase.

**Depends on**: Phase 3 (content must be embedded for chat to retrieve it)

### Implementation for User Story 1

- [x] T013 [US1] Modify `backend/src/main/java/com/simonrowe/chat/ChatConfig.java` — add `RetrievalAugmentationAdvisor` as a default advisor on the `ChatClient` bean. Configure `VectorStoreDocumentRetriever` with `similarityThreshold(0.7)` and `topK(5)`. Inject `VectorStore` into the `chatClient` bean method. Add the RAG advisor alongside the existing `MessageChatMemoryAdvisor`.
- [x] T014 [US1] Update the chat system prompt in `backend/src/main/resources/application.yml` (`chat.system-prompt`) to instruct the AI to use the provided context from retrieved documents when answering questions, and to mention source content titles when relevant. Add guidance to respond gracefully when no relevant context is found.
- [x] T015 [US1] Verify that the existing MCP tools (`searchBlogs`, `searchSite` in `ProfileMcpTools`) still work alongside the RAG advisor. If the RAG advisor provides sufficient context, consider marking the keyword search tools as supplementary in their `@Tool` descriptions so the LLM prefers RAG context but can fall back to keyword search.

**Checkpoint**: Chat uses semantic search to find relevant content. Visitors get contextually rich answers even with different wording than source content.

---

## Phase 5: User Story 3 — Code Examples Management (Priority: P2)

**Goal**: Admin CMS for creating/managing code examples linked to skills, embedded for chat retrieval and usable as internal reference library.

**Independent Test**: Create a code example in admin, verify it appears in the listing with filters, then ask the chat a code-related question and verify the example is retrieved.

### Implementation for User Story 3

- [x] T016 [P] [US3] Create `backend/src/main/java/com/simonrowe/admin/CodeExample.java` — MongoDB `@Document(collection = "code_examples")` record with fields: `@Id String id`, `@Indexed(unique = true) String title`, `String description`, `String language`, `String code`, `@DBRef List<Skill> skills`, `Instant createdAt`, `Instant updatedAt`. Add `@Indexed` on `language` field.
- [x] T017 [P] [US3] Create `backend/src/main/java/com/simonrowe/admin/AdminCodeExampleRepository.java` — extends `MongoRepository<CodeExample, String>`. Add query methods: `findByLanguage(String language, Pageable pageable)`, `Page<CodeExample> findByTitleContainingIgnoreCaseOrDescriptionContainingIgnoreCase(String title, String description, Pageable pageable)`, `findBySkillsId(String skillId, Pageable pageable)`.
- [x] T018 [US3] Create `backend/src/main/java/com/simonrowe/admin/AdminCodeExampleController.java` — `@RestController @RequestMapping("/api/admin/code-examples")` following the pattern in `AdminBlogController`. Endpoints per `contracts/admin-code-examples-api.yaml`: GET (list with pagination, filter by skill/language/search query params), POST (create), GET /{id}, PUT /{id} (update), DELETE /{id}. Use `@DBRef` skill resolution pattern (frontend sends skill IDs, controller resolves to Skill entities via `AdminSkillRepository`). Convert back to IDs in response DTO. Validate: title required max 200, description required max 2000, language required max 50, code required. Publish `ContentChangeEvent` with `ContentType.CODE_EXAMPLE` on create/update/delete.
- [x] T019 [US3] Add `embedCodeExample(CodeExample)` method to `backend/src/main/java/com/simonrowe/embedding/EmbeddingService.java` — embed title + description + code as content with metadata (sourceId, sourceType=code_example, title, skills as comma-separated names, language, url=/admin/code-examples/{id}).
- [x] T020 [P] [US3] Add code example API methods to `frontend/src/services/adminApi.ts` — `fetchAdminCodeExamples(params)`, `fetchAdminCodeExampleById(id)`, `createAdminCodeExample(data)`, `updateAdminCodeExample(id, data)`, `deleteAdminCodeExample(id)`. Define `AdminCodeExample` interface with fields: id, title, description, language, code, skills (string[]), createdAt, updatedAt.
- [x] T021 [P] [US3] Create `frontend/src/pages/admin/CodeExamplesAdmin.tsx` — list page following `BlogsAdmin.tsx` pattern. Display table with columns: title, language, skills (as tags), actions (edit/delete icons). Add filter controls: skill dropdown, language dropdown, search text input. Use pagination. Link to create/edit pages. Include Lucide React icons (`Code2` for code, `Pencil` for edit, `Trash2` for delete).
- [x] T022 [US3] Create `frontend/src/pages/admin/CodeExampleEditor.tsx` — create/edit form following `BlogEditor.tsx` pattern. Fields: title (text input), description (textarea), language (select dropdown with common languages: java, typescript, python, go, kotlin, bash, sql, yaml, json, other), code (textarea with monospace font), skills (multi-select using existing skill picker pattern). Use React Hook Form + Zod validation matching backend constraints. On save: POST or PUT to admin API, navigate back to list.
- [x] T023 [US3] Add code example admin route to frontend router — add `/admin/code-examples` (list), `/admin/code-examples/new` (create), `/admin/code-examples/:id` (edit) routes. Add navigation card to `AdminDashboard.tsx` with `Code2` icon linking to `/admin/code-examples`.
- [x] T024 [US3] Add code example styles to `frontend/src/styles.css` — code editor textarea with monospace font (`var(--font-mono, 'Fira Code', monospace)`), syntax-highlighted preview area, language badge styling. Follow existing BEM patterns (`.code-example__editor`, `.code-example__preview`, `.code-example__language-badge`).

**Checkpoint**: Code examples can be created, edited, filtered, and deleted in admin. They are embedded into the vector store and retrievable via chat.

---

## Phase 6: User Story 4 — Elasticsearch Backup & Restore (Priority: P2)

**Goal**: Backup and restore scripts include Elasticsearch vector embedding data alongside MongoDB and uploads.

**Independent Test**: Run `scripts/backup.sh`, verify backup includes ES snapshot data. Restore to clean environment, confirm semantic search works without re-embedding.

### Implementation for User Story 4

- [x] T025 [US4] Extend `scripts/backup.sh` — after MongoDB dump and uploads copy, add Elasticsearch snapshot: register filesystem repository (`blog_backup` at `/usr/share/elasticsearch/backups`) via `curl -X PUT` to ES `_snapshot` API, create named snapshot with `wait_for_completion=true` targeting `content-embeddings` index (and existing indices), copy snapshot data from `elasticsearch-backups` Docker volume into the backup staging directory, include in the tarball. Add ES availability check with graceful skip and warning if ES is not running. Report included ES indices in output.
- [x] T026 [US4] Extend `scripts/restore.sh` — after MongoDB restore and uploads copy, add Elasticsearch restore: extract ES snapshot data from tarball, copy into `elasticsearch-backups` Docker volume, register filesystem repository via ES API, close target indices, restore snapshot with `wait_for_completion=true`, reopen indices. Add ES availability check with graceful skip and warning. Verify restored indices are accessible.

**Checkpoint**: Full backup/restore cycle preserves vector embeddings. Semantic search works immediately after restore.

---

## Phase 7: User Story 5 — Admin Re-embedding Trigger (Priority: P3)

**Goal**: Admin can trigger full re-embedding of all content from the dashboard with progress feedback.

**Independent Test**: Click re-embed button in admin, observe progress updates via SSE, confirm all content types have fresh embeddings after completion.

**Depends on**: Phase 3 (EmbeddingService must exist)

### Implementation for User Story 5

- [x] T027 [US5] Add `/reembed` endpoint to `backend/src/main/java/com/simonrowe/dataops/DataOperationsController.java` — `@PostMapping("/reembed")` following the `rebuild-index` pattern. Use `CompletableFuture.runAsync` to: clear all existing embeddings, iterate all published blogs (update progress 0-25%), all jobs (25-50%), all skills with groups (50-75%), all code examples (75-100%), call `EmbeddingService.embed*()` for each, update progress via `DataOperationsService.updateProgress()` per batch. On success: `completeOperation("Re-embedded N items")`. On failure: `failOperation(error)`. Use `OperationType.REEMBED_CONTENT`.
- [x] T028 [US5] Add re-embed button to the admin Data Operations page in the frontend. Add a "Re-embed Content" action card/button (using `RefreshCw` Lucide icon) to the data operations section that triggers `POST /api/admin/data-operations/reembed`. Reuse existing SSE progress display pattern from the data operations admin page. Show progress percentage and message during re-embedding. Disable button while operation is in progress.

**Checkpoint**: Admin can trigger and monitor full re-embedding. Chat continues working during re-embedding.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Quality, observability, and validation across all stories.

- [x] T029 Add `@WithSpan` OpenTelemetry annotations to key methods in `EmbeddingService.java` and `EmbeddingChangeConsumer.java` for distributed tracing
- [x] T030 Test GraalVM native image compilation with the new `spring-ai-starter-vector-store-elasticsearch` dependency — run `./gradlew bootBuildImage` and fix any reflection hints needed (add `@RegisterReflectionForBinding` or `reflect-config.json` entries as required)
- [x] T031 Run quickstart.md validation — follow all verification steps end-to-end: start app, trigger re-embedding, test semantic chat, create code example, test backup/restore

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 completion — BLOCKS all user stories
- **US2 - Content Embedding (Phase 3)**: Depends on Phase 2
- **US1 - Semantic Chat (Phase 4)**: Depends on Phase 3 (needs embedded content to search)
- **US3 - Code Examples (Phase 5)**: Depends on Phase 2 only — can run in parallel with Phases 3-4
- **US4 - ES Backup/Restore (Phase 6)**: Depends on Phase 1 only (Docker Compose changes) — can run in parallel with Phases 3-5
- **US5 - Admin Re-embedding (Phase 7)**: Depends on Phase 2 (EmbeddingService)
- **Polish (Phase 8)**: Depends on all desired user stories being complete

### User Story Dependencies

- **US2 (P1)**: Foundational only — first story to implement
- **US1 (P1)**: Depends on US2 (content must be embedded for chat to search it)
- **US3 (P2)**: Foundational only — independent of US1/US2 (can run in parallel)
- **US4 (P2)**: Setup only — fully independent (scripts, no Java code dependencies)
- **US5 (P3)**: Foundational only — independent of other stories

### Within Each User Story

- Models/entities before services
- Services before controllers/endpoints
- Backend before frontend
- Core implementation before integration

### Parallel Opportunities

**After Phase 2 (Foundational) completes, these can run in parallel:**

```
┌─ Phase 3: US2 (Content Embedding) ─┐
│  → Phase 4: US1 (Semantic Chat)     │  ← sequential (US1 needs US2)
├─ Phase 5: US3 (Code Examples) ──────┤  ← parallel with US2/US1
├─ Phase 6: US4 (ES Backup/Restore) ──┤  ← parallel with everything
└─ Phase 7: US5 (Admin Re-embed) ─────┘  ← parallel with US3/US4
```

---

## Parallel Example: User Story 3

```bash
# These tasks can run in parallel (different files, no dependencies):
Task T016: "Create CodeExample.java entity"
Task T017: "Create AdminCodeExampleRepository.java"
Task T020: "Add code example API methods to adminApi.ts"
Task T021: "Create CodeExamplesAdmin.tsx list page"

# Then sequentially:
Task T018: "Create AdminCodeExampleController.java" (depends on T016, T017)
Task T022: "Create CodeExampleEditor.tsx" (depends on T020)
Task T019: "Add embedCodeExample to EmbeddingService" (depends on T016)
Task T023: "Add routes to router and dashboard"
Task T024: "Add styles to styles.css"
```

---

## Implementation Strategy

### MVP First (User Stories 1 + 2)

1. Complete Phase 1: Setup (dependencies, config, Docker)
2. Complete Phase 2: Foundational (EmbeddingService, enums)
3. Complete Phase 3: US2 — Content Embedding & Ingestion
4. Complete Phase 4: US1 — Semantic Chat Search
5. **STOP and VALIDATE**: Test semantic chat with embedded content
6. Deploy/demo if ready — visitors immediately get better chat

### Incremental Delivery

1. Setup + Foundational → OpenAI works for chat, vector store configured
2. Add US2 → Content automatically embedded on change
3. Add US1 → Chat uses semantic search (MVP complete!)
4. Add US3 → Code examples enrich chat + internal reference
5. Add US4 → Disaster recovery covers vector data
6. Add US5 → Admin convenience for re-embedding
7. Each story adds value without breaking previous stories

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story
- US2 must complete before US1 (content must be embedded before chat can search it)
- US3, US4, US5 are independent and can be parallelized after Foundational
- Commit after each task or logical group
- Stop at any checkpoint to validate independently
