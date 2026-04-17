# Tasks: Improve MCP Tools

**Input**: Design documents from `/specs/022-improve-mcp-tools/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/mcp-tools-api.md, quickstart.md

**Tests**: Included — existing test patterns in the codebase demand coverage for all modified tools.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Web app**: `backend/src/main/java/com/simonrowe/`, `backend/src/test/java/com/simonrowe/`

---

## Phase 1: Setup

**Purpose**: No new project structure needed — all changes fit existing packages. This phase covers shared prerequisites.

- [x] T001 Add `searchByType(String query, String type)` method to `backend/src/main/java/com/simonrowe/search/SearchService.java` — performs multi-match query on `site_search` index filtered by `type` field, returns `List<SiteSearchResult>`, catches `IOException` and returns empty list with error logged. Use same field set as `siteSearch` (`name`, `shortDescription`, `longDescription`, `company`) with a term filter on `type`.
- [x] T002 Add `submitFromChat(ContactSubmission submission)` method to `backend/src/main/java/com/simonrowe/contact/ContactService.java` — delegates to `emailService.send(submission)` without reCAPTCHA verification. Log submission source as "AI Chat". Propagate `EmailDeliveryException` to caller.

**Checkpoint**: Shared service methods ready — user story implementation can begin.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Session-level contact tracking infrastructure needed before US1.

**CRITICAL**: US1 (contact tool) depends on this. US2/US3/US4 do not.

- [x] T003 Add `Set<String> contactSubmittedSessions` field (backed by `ConcurrentHashMap.newKeySet()`) to `backend/src/main/java/com/simonrowe/chat/ChatController.java`. Add public methods: `boolean hasContactBeenSubmitted(String sessionId)` and `void markContactSubmitted(String sessionId)`. Expose getter for test access.
- [x] T004 Update `backend/src/main/java/com/simonrowe/chat/ChatSessionCleanupService.java` — in `cleanupStaleSessions()`, after calling `chatService.evictSession()`, also remove the sessionId from `ChatController.contactSubmittedSessions` via the controller reference (inject `ChatController` or extract the set to a shared component).

**Checkpoint**: Foundation ready — user story implementation can now begin.

---

## Phase 3: User Story 1 — Contact Simon via Chat (Priority: P1) MVP

**Goal**: Add a `submitContactForm` MCP tool that lets visitors send a contact message through the AI chat, limited to one successful submission per session.

**Independent Test**: Open chat, ask AI to contact Simon, provide details, verify email received. Second attempt in same session is declined. New session allows fresh submission.

### Implementation for User Story 1

- [x] T005 [US1] Add `submitContactForm` tool method to `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java`. Inject `ContactService` and `ChatController`. Parameters: `firstName`, `lastName`, `email`, `subject`, `message` (all `@ToolParam` with descriptions). The method needs `sessionId` from the chat context — accept it as a `@ToolParam` parameter that the AI provides, or use a `ToolContext` if Spring AI supports it. Implementation: (1) check `chatController.hasContactBeenSubmitted(sessionId)` — if true, return "A contact message has already been sent in this chat session." (2) validate fields are non-blank and email format is valid (3) create `ContactSubmission` with referrer="AI Chat" (4) call `contactService.submitFromChat(submission)` (5) on success: call `chatController.markContactSubmitted(sessionId)` and return success message (6) on `EmailDeliveryException`: return "Failed to send message. Please try again." without marking session. Add `@WithSpan` and `@Tool` annotations with description from contracts.
- [x] T006 [US1] Add unit tests for `submitContactForm` in `backend/src/test/java/com/simonrowe/mcp/ProfileMcpToolsTest.java`: (1) successful submission delegates to contactService and marks session (2) second call in same session returns rejection message without calling contactService (3) failed email delivery returns error message and does NOT mark session as submitted (4) invalid email returns validation error (5) missing required fields returns validation error (6) new session after previous submission allows fresh contact.
- [x] T007 [US1] Update the system prompt in `backend/src/main/resources/application.yml` (the `chat.system-prompt` property) to inform the AI about the new `submitContactForm` tool — describe when to use it, that it requires collecting firstName, lastName, email, subject, message from the visitor before calling, and that it can only be used once per session.

**Checkpoint**: Contact tool functional and tested. Visitors can send messages through chat.

---

## Phase 4: User Story 2 — Search-Based Tool Responses (Priority: P2)

**Goal**: Add optional search query parameters to `getJobs`, `getSkills`, and `getUpcomingEvents` so they return filtered results when a query is provided, and all results when no query is given.

**Independent Test**: Ask AI about a specific technology — verify only relevant jobs/skills/events are returned. Ask a general question — verify all items still returned.

### Implementation for User Story 2

- [x] T008 [P] [US2] Modify `getJobs` in `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java` — add optional `@ToolParam String query` parameter. When query is non-blank: call `searchService.searchByType(query, "job")` and return results. When null/blank: call `jobService.getAllJobs()` as before. Update `@Tool` description to mention optional keyword filtering. Handle search errors by returning error message string.
- [x] T009 [P] [US2] Modify `getSkills` in `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java` — add optional `@ToolParam String query` parameter. When query is non-blank: call `searchService.searchByType(query, "skill")` and return results. When null/blank: call `skillGroupService.getAllSkillGroups()` as before. Update `@Tool` description. Handle search errors.
- [x] T010 [P] [US2] Modify `getUpcomingEvents` in `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java` — add optional `@ToolParam String query` parameter. When query is non-blank: call `searchService.searchByType(query, "event")` and return results. When null/blank: query `eventRepository` for upcoming events as before. Update `@Tool` description. Handle search errors.
- [x] T011 [US2] Update tests in `backend/src/test/java/com/simonrowe/mcp/ProfileMcpToolsTest.java` for modified tools: (1) `getJobs` with null query delegates to `jobService.getAllJobs()` (2) `getJobs` with query delegates to `searchService.searchByType(query, "job")` (3) same pattern for `getSkills` and `getUpcomingEvents` (4) verify backwards compatibility — null/empty query returns full list.

**Checkpoint**: Jobs, skills, and events tools support keyword filtering while maintaining backwards compatibility.

---

## Phase 5: User Story 3 — Deduplicated Blog Search (Priority: P2)

**Goal**: Fix `searchBlogs` to use the blog-specific Elasticsearch index with field-level relevance boosting, differentiating it from `searchSite`.

**Independent Test**: Call `searchBlogs` and `searchSite` with the same query — verify `searchBlogs` returns blog-only results and `searchSite` returns multi-type grouped results.

### Implementation for User Story 3

- [x] T012 [US3] Change `searchBlogs` in `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java` — change delegation from `searchService.siteSearch(query)` to `searchService.blogSearch(query)`. Update return type from `GroupedSearchResponse` to `List<BlogSearchResult>`. Update `@Tool` description to clarify this searches blog content only with relevance ranking by title, tags, and content.
- [x] T013 [US3] Update tests in `backend/src/test/java/com/simonrowe/mcp/ProfileMcpToolsTest.java`: (1) `searchBlogs` now delegates to `searchService.blogSearch()` not `siteSearch()` (2) verify return type is `List<BlogSearchResult>` (3) `searchSite` still delegates to `searchService.siteSearch()` (unchanged).

**Checkpoint**: Blog search and site search return different, correctly-scoped results.

---

## Phase 6: User Story 4 — Elasticsearch-Powered News Search (Priority: P3)

**Goal**: Replace in-memory news article filtering with Elasticsearch `site_search` index queries filtered by type="news".

**Independent Test**: Search for a news topic — verify results are ranked by relevance, not just string containment.

### Implementation for User Story 4

- [x] T014 [US4] Change `searchNews` in `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java` — when query is non-blank: call `searchService.searchByType(query, "news")` and map results to the existing `Map<String, Object>` format. When null/blank: keep existing `articleRepository` query for latest 10 articles. Handle search errors with error message. Remove the in-memory `.filter()` / `.contains()` logic.
- [x] T015 [US4] Update tests in `backend/src/test/java/com/simonrowe/mcp/ProfileMcpToolsTest.java`: (1) `searchNews` with query delegates to `searchService.searchByType(query, "news")` (2) `searchNews` with null/empty query still delegates to `articleRepository` (3) verify in-memory filtering is no longer used.

**Checkpoint**: News search uses Elasticsearch with proper relevance ranking.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Error handling, consistency, and final validation across all modified tools.

- [x] T016 Add Elasticsearch unavailability error handling to all search-based tools in `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java` — wrap search calls in try-catch for `Exception`, return `"Search is temporarily unavailable. Please try again later."` on failure. Applies to: `searchBlogs`, `searchSite`, `getJobs` (with query), `getSkills` (with query), `getUpcomingEvents` (with query), `searchNews` (with query).
- [x] T017 [P] Update `@Tool` descriptions across all modified tools in `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java` to ensure consistency — verify descriptions clearly differentiate `searchBlogs` (blog-only, field-weighted) from `searchSite` (all content types, grouped), and clarify optional vs required parameters.
- [x] T018 Run full backend test suite (`cd backend && ../gradlew test`) and fix any failures introduced by the changes.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS US1 only
- **US1 (Phase 3)**: Depends on Phase 1 (T002) + Phase 2 (T003, T004)
- **US2 (Phase 4)**: Depends on Phase 1 (T001) only — can start in parallel with Phase 2
- **US3 (Phase 5)**: No dependencies on other phases — can start immediately
- **US4 (Phase 6)**: Depends on Phase 1 (T001) only — can start in parallel with Phase 2
- **Polish (Phase 7)**: Depends on all user stories being complete

### User Story Dependencies

- **US1 (Contact Tool)**: Independent — no dependencies on other stories
- **US2 (Search Params)**: Independent — no dependencies on other stories
- **US3 (Blog Search Fix)**: Independent — no dependencies on other stories
- **US4 (News ES Search)**: Independent — shares `searchByType` with US2 but no ordering requirement

### Within Each User Story

- Implementation before tests (tests verify the implementation)
- All tool changes in `ProfileMcpTools.java` — sequential within a story to avoid merge conflicts
- Test updates can be batched per story

### Parallel Opportunities

- T001 and T002 (Setup) can run in parallel
- T003 and T004 (Foundational) are sequential (T004 depends on T003)
- T008, T009, T010 (US2 tool modifications) can run in parallel (different methods, same file — but low conflict risk)
- US2, US3, US4 can all start as soon as T001 is complete (US3 has no dependency on T001)
- US1 must wait for Phase 2 completion

---

## Parallel Example: User Stories 2-4

```bash
# After T001 completes, these can start in parallel:
# Agent A: US3 (T012, T013) — no dependency on T001
# Agent B: US2 (T008, T009, T010, T011) — depends on T001
# Agent C: US4 (T014, T015) — depends on T001

# US1 must wait for T003/T004 (Phase 2) plus T002 (Phase 1)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001, T002)
2. Complete Phase 2: Foundational (T003, T004)
3. Complete Phase 3: US1 Contact Tool (T005, T006, T007)
4. **STOP and VALIDATE**: Test contact tool independently via chat
5. Deploy if ready

### Incremental Delivery

1. Setup + Foundational -> Foundation ready
2. US1 Contact Tool -> Test independently -> Deploy (MVP!)
3. US3 Blog Search Fix -> Test independently (quick win, one-line fix)
4. US2 Search Params -> Test independently
5. US4 News ES Search -> Test independently
6. Polish -> Final validation -> Deploy

### Recommended Order (Single Developer)

1. T001, T002 (Setup — parallel)
2. T003, T004 (Foundational — sequential)
3. T012, T013 (US3 — quickest win, one-line change + test update)
4. T005, T006, T007 (US1 — highest priority, most complex)
5. T008, T009, T010, T011 (US2 — three similar changes)
6. T014, T015 (US4 — similar to US2 pattern)
7. T016, T017, T018 (Polish)

---

## Notes

- All tool changes are in a single file (`ProfileMcpTools.java`) — sequential execution within a story avoids merge conflicts
- [P] tasks target different files or independent methods within the same file
- The `searchByType` method (T001) is a shared dependency for US2 and US4
- US3 (blog search fix) is the simplest change — consider doing it first for quick momentum
- Commit after each story checkpoint for clean git history
