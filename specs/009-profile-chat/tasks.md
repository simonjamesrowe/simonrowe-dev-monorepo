# Tasks: Profile Chat with MCP Tools

**Input**: Design documents from `/specs/009-profile-chat/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Tests are included as this project has a constitutional requirement of 80% JaCoCo coverage and frontend tests for critical user journeys.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Add Spring AI, WebSocket, and Bucket4j dependencies; configure build tooling

- [x] T001 Add Spring AI 1.1.2, WebSocket, and Bucket4j version entries to gradle/libs.versions.toml
- [x] T002 Add Spring AI BOM, spring-ai-starter-model-openai, spring-ai-starter-mcp-server-webmvc, spring-boot-starter-websocket, and bucket4j-core dependency entries to gradle/libs.versions.toml libraries section
- [x] T003 Add dependencyManagement block importing Spring AI BOM and new implementation dependencies to backend/build.gradle.kts
- [x] T004 Install @stomp/stompjs dependency in frontend/package.json

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Backend configuration and shared DTOs that ALL user stories depend on

**CRITICAL**: No user story work can begin until this phase is complete

- [x] T005 Add Spring AI (Groq), WebSocket, MCP server, chat session, and rate limit configuration properties to backend/src/main/resources/application.yml
- [x] T006 [P] Create WebSocketConfig.java with @EnableWebSocketMessageBroker, STOMP endpoint /ws/chat, app destination prefix /app, topic broker /topic, and CORS allowed origins at backend/src/main/java/com/simonrowe/WebSocketConfig.java
- [x] T007 [P] Create ChatRequest record (sessionId, message) with Jakarta validation annotations at backend/src/main/java/com/simonrowe/chat/ChatRequest.java
- [x] T008 [P] Create ChatResponse record (sessionId, content, type enum, timestamp) at backend/src/main/java/com/simonrowe/chat/ChatResponse.java
- [x] T009 Create ChatConfig.java that builds a ChatClient bean with MessageChatMemoryAdvisor, system prompt describing the profile site, and Groq-backed ChatModel at backend/src/main/java/com/simonrowe/chat/ChatConfig.java
- [x] T010 Create chatService.ts STOMP WebSocket client with connect, disconnect, sendMessage, and onMessage subscription methods at frontend/src/services/chatService.ts

**Checkpoint**: Foundation ready — build compiles, WebSocket endpoint accessible, STOMP client available

---

## Phase 3: User Story 1 — Start a Chat from the Search Bar (Priority: P1)

**Goal**: Pressing Enter in the search bar (with a non-empty query) opens a chat panel seeded with the query. Typeahead click-through behaviour is preserved. A UUID session key is generated per conversation.

**Independent Test**: Type a query in the search bar, press Enter, verify chat panel opens with query as first message and a unique session ID assigned. Click a typeahead result and verify it navigates normally.

### Tests for User Story 1

- [x] T011 [P] [US1] Write ChatPanel.test.tsx verifying: panel renders when open, displays initial message, generates UUID session ID, closes on close button click at frontend/tests/components/chat/ChatPanel.test.tsx
- [x] T012 [P] [US1] Write SiteSearch.test.tsx verifying: Enter key with non-empty query calls onChatStart, Enter key with empty query does nothing, typeahead click still navigates at frontend/tests/components/search/SiteSearch.test.tsx

### Implementation for User Story 1

- [x] T013 [US1] Modify SiteSearch.tsx to add onChatStart prop callback and handleKeyDown handler for Enter key (call onChatStart(query) when query is non-empty, do nothing when empty) at frontend/src/components/search/SiteSearch.tsx
- [x] T014 [P] [US1] Create ChatMessage.tsx component displaying a single message bubble with role-based styling (user right-aligned, assistant left-aligned) and timestamp at frontend/src/components/chat/ChatMessage.tsx
- [x] T015 [P] [US1] Create ChatInput.tsx component with text input (maxLength 500), character counter, send button (lucide-react Send icon), and Enter-to-send behaviour at frontend/src/components/chat/ChatInput.tsx
- [x] T016 [US1] Create ChatPanel.tsx component that: generates UUID session key on mount, displays message list, includes ChatInput, has close button (lucide-react X icon), manages local message state, connects to STOMP WebSocket via chatService.ts at frontend/src/components/chat/ChatPanel.tsx
- [x] T017 [US1] Modify ProfileBanner.tsx to accept onChatStart from SiteSearch, manage chat open/close state, and render ChatPanel when open at frontend/src/components/profile/ProfileBanner.tsx
- [x] T018 [US1] Add chat panel CSS styles (BEM: .chat-panel, .chat-panel__messages, .chat-panel__input, .chat-message, .chat-message--user, .chat-message--assistant) to frontend/src/styles.css

**Checkpoint**: Chat panel opens from search bar with initial message, connects via WebSocket, typeahead unaffected

---

## Phase 4: User Story 2 — Conversational Chat Experience (Priority: P1)

**Goal**: The chat processes messages via Groq LLM with conversation memory, streams responses token-by-token over WebSocket, shows typing indicator, and is fully ephemeral (lost on refresh).

**Independent Test**: Open a chat, send a message, verify streamed AI response appears. Send a follow-up, verify context is retained. Close and reopen — verify conversation is gone. Refresh page — verify session is cleared.

### Tests for User Story 2

- [x] T019 [P] [US2] Write ChatServiceTest.java unit test with mocked ChatModel verifying: processMessage calls ChatClient with correct session ID, streaming response is emitted, memory advisor is invoked at backend/src/test/java/com/simonrowe/chat/ChatServiceTest.java
- [x] T020 [P] [US2] Write ChatControllerTest.java unit test with mocked ChatService and SimpMessagingTemplate verifying: STOMP message flow sends STREAM_START/STREAM_CHUNK/STREAM_END, error handling sends ERROR message at backend/src/test/java/com/simonrowe/chat/ChatControllerTest.java
- [x] T021 [P] [US2] Write ChatSessionCleanupServiceTest.java unit test verifying: sessions inactive > 30 min are evicted, active sessions are preserved at backend/src/test/java/com/simonrowe/chat/ChatSessionCleanupServiceTest.java

### Implementation for User Story 2

- [x] T022 [US2] Create ChatService.java that: accepts a session ID and message, adds user message to ChatMemory, calls ChatClient.prompt().stream() with conversation ID param, returns Flux of streamed content chunks at backend/src/main/java/com/simonrowe/chat/ChatService.java
- [x] T023 [US2] Create ChatController.java with @MessageMapping("chat.send") that: validates ChatRequest, calls ChatService, sends STREAM_START/STREAM_CHUNK/STREAM_END ChatResponse messages to /topic/chat.{sessionId} via SimpMessagingTemplate, handles errors with ERROR type message at backend/src/main/java/com/simonrowe/chat/ChatController.java
- [x] T024 [US2] Create ChatSessionCleanupService.java with @Scheduled cleanup (every 5 min) that evicts sessions inactive for > 30 min from ConcurrentHashMap<String, Instant> session tracker and ChatMemory at backend/src/main/java/com/simonrowe/chat/ChatSessionCleanupService.java
- [x] T025 [P] [US2] Create ChatTypingIndicator.tsx component showing animated dots when assistant is responding at frontend/src/components/chat/ChatTypingIndicator.tsx
- [x] T026 [US2] Update ChatPanel.tsx to: handle STREAM_START (show typing indicator), STREAM_CHUNK (append content), STREAM_END (finalize message, hide indicator), ERROR (show error message with retry option) from WebSocket responses at frontend/src/components/chat/ChatPanel.tsx
- [x] T027 [US2] Add typing indicator and streaming message CSS styles (.chat-typing-indicator, .chat-message--streaming, .chat-panel__error) to frontend/src/styles.css

**Checkpoint**: Full conversational chat working — messages sent, AI responds with streaming, memory retained within session, ephemeral on refresh

---

## Phase 5: User Story 3 — MCP Tool Exposure for External Agents (Priority: P2)

**Goal**: Expose get_profile, search_blogs, get_jobs, get_skills, and search_site as MCP tools via Spring AI MCP Server WebMVC (SSE transport). Both the built-in chat and external MCP clients can invoke these tools.

**Independent Test**: Use an MCP client (or curl to the SSE endpoint) to call each tool and verify structured data is returned matching the contracts in contracts/chat-api.yml.

### Tests for User Story 3

- [x] T028 [P] [US3] Write ProfileMcpToolsTest.java integration test using @SpringBootTest and Testcontainers (MongoDB, Elasticsearch) verifying: each @McpTool method returns expected data structure when called with test data at backend/src/test/java/com/simonrowe/mcp/ProfileMcpToolsTest.java

### Implementation for User Story 3

- [x] T029 [US3] Create ProfileMcpTools.java @Component with @Tool annotated methods: getProfile() delegates to ProfileService, searchBlogs(query) delegates to SearchService, getJobs() delegates to JobService, getSkills() delegates to SkillGroupService, searchSite(query) delegates to SearchService at backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java
- [x] T030 [US3] Register MCP tools in ChatConfig.java via .defaultTools(profileMcpTools) so the ChatClient can invoke them during conversations (tool/function calling via Groq) at backend/src/main/java/com/simonrowe/chat/ChatConfig.java
- [x] T031 [US3] Add GraalVM native image reflection hints (@RegisterReflectionForBinding) for MCP tool parameter and return types if needed for native image compatibility at backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java (SKIPPED: project does not build native images)

**Checkpoint**: MCP tools accessible via SSE endpoint, chat uses tools to answer profile questions with real data

---

## Phase 6: User Story 4 — Rate Limiting (Priority: P2)

**Goal**: Apply per-IP rate limiting to chat WebSocket messages (20/min) and MCP endpoints (60/min). Return clear 429 responses when limits are exceeded.

**Independent Test**: Send > 20 chat messages within 1 minute from the same client and verify subsequent messages are rejected with a rate limit error. Send > 60 MCP requests and verify 429 response.

### Tests for User Story 4

- [x] T032 [P] [US4] Write RateLimitInterceptorTest.java integration test verifying: requests within limit pass through, requests exceeding limit return 429 with Retry-After header, different IPs have independent limits at backend/src/test/java/com/simonrowe/ratelimit/RateLimitInterceptorTest.java

### Implementation for User Story 4

- [x] T033 [P] [US4] Create RateLimitConfig.java @ConfigurationProperties with requests-per-minute for chat and mcp buckets, reading from rate-limit.chat.requests-per-minute and rate-limit.mcp.requests-per-minute at backend/src/main/java/com/simonrowe/ratelimit/RateLimitConfig.java
- [x] T034 [US4] Create RateLimitInterceptor.java HandlerInterceptor using Bucket4j with ConcurrentHashMap<String, Bucket> per IP, applying chat limits and mcp limits to /mcp/**, setting X-RateLimit-Remaining and X-RateLimit-Reset headers, returning 429 with Retry-After when exhausted at backend/src/main/java/com/simonrowe/ratelimit/RateLimitInterceptor.java
- [x] T035 [US4] Register RateLimitInterceptor in WebConfig.java addInterceptors() for /mcp/** paths at backend/src/main/java/com/simonrowe/WebConfig.java
- [x] T036 [US4] Add rate limit exceeded handling to ChatPanel.tsx — display user-friendly message when WebSocket receives ERROR with rate limit content, show retry countdown at frontend/src/components/chat/ChatPanel.tsx

**Checkpoint**: Rate limiting active on chat and MCP endpoints, clear feedback when limits exceeded

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Mobile responsiveness, edge cases, error handling across all stories

- [x] T037 [P] Add mobile responsive CSS for chat panel (full-screen overlay on screens < 768px, .chat-panel--mobile) to frontend/src/styles.css
- [x] T038 [P] Handle edge case: multiple Enter presses reuse existing chat session instead of opening multiple panels in frontend/src/components/chat/ChatPanel.tsx
- [x] T039 [P] Handle edge case: Groq API unavailable — ChatController catches exceptions and sends ERROR type ChatResponse with friendly message at backend/src/main/java/com/simonrowe/chat/ChatController.java
- [x] T040 Add @WithSpan OpenTelemetry tracing annotations to ChatService.processMessage and ProfileMcpTools methods at backend/src/main/java/com/simonrowe/chat/ChatService.java and backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java
- [x] T041 Verify Checkstyle compliance on all new Java files by running ./gradlew checkstyleMain checkstyleTest
- [x] T042 Verify JaCoCo coverage >= 80% on new code by running ./gradlew jacocoTestCoverageVerification
- [x] T043 Run frontend lint and tests with npm run lint && npm test in frontend/

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 completion — BLOCKS all user stories
- **US1 (Phase 3)**: Depends on Phase 2. Frontend-heavy, establishes chat UI.
- **US2 (Phase 4)**: Depends on Phase 2. Backend-heavy. Can run in parallel with US1 for backend tasks, but frontend streaming integration depends on US1's ChatPanel.
- **US3 (Phase 5)**: Depends on Phase 2 only. Fully independent — can run in parallel with US1/US2.
- **US4 (Phase 6)**: Depends on Phase 2 only. Fully independent — can run in parallel with US1/US2/US3.
- **Polish (Phase 7)**: Depends on all user stories being complete.

### User Story Dependencies

- **US1 (P1)**: After Phase 2 — no story dependencies
- **US2 (P1)**: After Phase 2 — backend tasks independent of US1, but frontend streaming UI (T026) integrates with US1's ChatPanel (T016)
- **US3 (P2)**: After Phase 2 — fully independent of US1/US2
- **US4 (P2)**: After Phase 2 — fully independent of US1/US2/US3

### Within Each User Story

- Tests written first (TDD: verify they fail)
- Records/DTOs before services
- Services before controllers
- Backend before frontend integration
- Core implementation before edge cases

### Parallel Opportunities

- T007, T008 (DTOs) can run in parallel
- T011, T012 (US1 tests) can run in parallel
- T014, T015 (ChatMessage, ChatInput components) can run in parallel
- T019, T020, T021 (US2 tests) can run in parallel
- T025 (typing indicator) can run alongside T022-T024 (backend)
- US3 (Phase 5) can run entirely in parallel with US1/US2
- US4 (Phase 6) can run entirely in parallel with US1/US2/US3
- T033 (RateLimitConfig) can run in parallel with T032 (test)

---

## Parallel Example: Maximising Throughput After Phase 2

```text
# After Phase 2 completes, launch all these in parallel:

# Agent A: US1 Frontend
Task: T013 "Modify SiteSearch.tsx Enter key handler"
Task: T014 "Create ChatMessage.tsx"
Task: T015 "Create ChatInput.tsx"
Task: T016 "Create ChatPanel.tsx" (after T014, T015)
Task: T017 "Modify ProfileBanner.tsx" (after T016)

# Agent B: US2 Backend
Task: T022 "Create ChatService.java"
Task: T023 "Create ChatController.java" (after T022)
Task: T024 "Create ChatSessionCleanupService.java"

# Agent C: US3 MCP Tools
Task: T029 "Create ProfileMcpTools.java"
Task: T030 "Register tools in ChatConfig"

# Agent D: US4 Rate Limiting
Task: T033 "Create RateLimitConfig.java"
Task: T034 "Create RateLimitInterceptor.java" (after T033)
Task: T035 "Register interceptor in WebConfig"
```

---

## Implementation Strategy

### MVP First (User Stories 1 + 2)

1. Complete Phase 1: Setup (dependency changes)
2. Complete Phase 2: Foundational (config + DTOs + WebSocket + STOMP client)
3. Complete Phase 3: US1 — Chat opens from search bar
4. Complete Phase 4: US2 — AI responds with streaming
5. **STOP and VALIDATE**: Full chat working end-to-end
6. Deploy/demo MVP

### Incremental Delivery

1. Setup + Foundational → Build compiles, WebSocket connectable
2. US1 → Chat panel opens from search bar (visual demo)
3. US2 → Full conversational AI with memory (functional demo)
4. US3 → MCP tools exposed for external agents (API demo)
5. US4 → Rate limiting active (production-ready)
6. Polish → Mobile, error handling, observability

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story
- Spec does not prescribe TDD, but tests are included for 80% JaCoCo coverage requirement
- Chat panel CSS uses BEM naming in single styles.css per constitution
- All new Java classes must pass Checkstyle (Google style) with 0 warnings
- GROQ_API_KEY environment variable required for US2+ (mock ChatModel in tests)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
