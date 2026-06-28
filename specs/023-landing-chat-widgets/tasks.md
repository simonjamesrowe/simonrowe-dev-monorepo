# Tasks: Landing Chat Widgets

**Input**: Design documents from `/specs/023-landing-chat-widgets/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Included because the specification defines independently testable user stories and the constitution requires frontend tests for critical user journeys.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4)
- Include exact file paths in descriptions

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Prepare shared type and design references without changing behavior.

- [X] T001 Review `designs/README.md`, `designs/design_handoff/README.md`, `designs/Landing Page.html`, `designs/chat.jsx`, and `designs/ui_kits/website/` against current `frontend/src/components/home/`
- [X] T002 Review existing chat flow in `backend/src/main/java/com/simonrowe/chat/`, `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java`, `frontend/src/components/chat/`, and `frontend/src/services/chatService.ts`
- [X] T003 [P] Add frontend chat block and widget payload types in `frontend/src/components/chat/chatTypes.ts`
- [X] T004 [P] Add backend widget payload records under `backend/src/main/java/com/simonrowe/chat/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared stream contract and reducer foundation required before story implementation.

**Critical**: No user story work should begin until this phase is complete.

- [X] T005 Update `backend/src/main/java/com/simonrowe/chat/ChatResponse.java` with `TOOL_START`, `TOOL_END`, `WIDGET`, `toolLabel`, `widgetKind`, and `payload`, and remove `STREAM_RESET`
- [X] T006 [P] Add `ChatStreamPublisher` in `backend/src/main/java/com/simonrowe/chat/ChatStreamPublisher.java`
- [X] T007 [P] Add unit tests for chat event construction in `backend/src/test/java/com/simonrowe/chat/ChatResponseTest.java`
- [X] T008 [P] Add unit tests for stream publishing destinations and payloads in `backend/src/test/java/com/simonrowe/chat/ChatStreamPublisherTest.java`
- [X] T009 Update `frontend/src/services/chatService.ts` to type `TOOL_START`, `TOOL_END`, and `WIDGET` events
- [X] T010 [P] Add pure stream reducer in `frontend/src/components/chat/chatStreamReducer.ts`
- [X] T011 [P] Add reducer tests for text, tool, widget, end, error, timeout-ready finalization, and unknown widget handling in `frontend/src/components/chat/chatStreamReducer.test.ts`
- [X] T012 Update prompt guidance in `backend/src/main/java/com/simonrowe/chat/ChatConfig.java` so card-backed answers use brief framing text without re-listing card data

**Checkpoint**: Shared contracts compile and reducer tests define the expected block behavior.

---

## Phase 3: User Story 1 - Land on a polished chat-first homepage (Priority: P1)

**Goal**: Visitors immediately understand Simon's role, expertise, and AI chat entry point on a responsive refreshed homepage.

**Independent Test**: Open the homepage on desktop and mobile and confirm the hero, navigation, theme controls, inline chat entry, about summary, calls to action, and footer are readable and usable.

### Tests for User Story 1

- [X] T013 [P] [US1] Add HomePage render tests for hero identity, role, AI chat entry, prompt chips, about summary, and calls to action in `frontend/src/pages/HomePage.test.tsx`
- [X] T014 [P] [US1] Add HeroSection interaction tests for prompt chips and chat submit in `frontend/src/components/home/HeroSection.test.tsx`

### Implementation for User Story 1

- [X] T015 [US1] Refactor `frontend/src/components/home/HeroSection.tsx` to match the chat-first hero layout from `designs/Landing Page.html` and `designs/chat.jsx`
- [X] T016 [P] [US1] Refactor `frontend/src/components/home/AboutSection.tsx` to match the supplied portrait, short-copy, skill-chip, CV, experience, and social-link design intent
- [X] T017 [P] [US1] Update `frontend/src/components/layout/TopNav.tsx` and `frontend/src/components/layout/MobileMenu.tsx` to preserve the new landing navigation, search, theme toggle, and mobile menu behavior
- [X] T018 [P] [US1] Update `frontend/src/components/layout/Footer.tsx` for the refreshed public exploration and connection links
- [X] T019 [US1] Update landing, chat, navigation, about, and footer styles in `frontend/src/styles.css` using the `designs/colors_and_type.css`, `designs/site.css`, and `designs/landing.css` intent while preserving the single stylesheet
- [X] T020 [US1] Update `frontend/src/pages/HomePage.tsx` to compose only the refreshed landing sections needed by the spec and preserve contact drawer behavior
- [X] T021 [US1] Verify homepage dark and light theme behavior manually against `specs/023-landing-chat-widgets/quickstart.md`

**Checkpoint**: User Story 1 is functional and testable without backend streaming changes.

---

## Phase 4: User Story 2 - Ask the AI and see progress in real time (Priority: P2)

**Goal**: Chat answers stream progressively and show visible running/completed tool activity.

**Independent Test**: Ask a question that requires a supported lookup and observe streamed text plus running and completed lookup activity.

### Tests for User Story 2

- [X] T022 [P] [US2] Update `backend/src/test/java/com/simonrowe/chat/ChatServiceTest.java` to expect streaming response chunks rather than blocking call output
- [X] T023 [P] [US2] Update `backend/src/test/java/com/simonrowe/chat/ChatControllerTest.java` to remove `STREAM_RESET` expectations and verify streamed chunks, finalization, limits, and errors
- [X] T024 [P] [US2] Add ChatPanel integration tests for `STREAM_START`, `STREAM_CHUNK`, `TOOL_START`, `TOOL_END`, `STREAM_END`, `ERROR`, clear chat, and message limit in `frontend/src/components/chat/ChatPanel.test.tsx`

### Implementation for User Story 2

- [X] T025 [US2] Change `backend/src/main/java/com/simonrowe/chat/ChatService.java` to use the chat client's streaming path while preserving session activity, chat memory, and tool context
- [X] T026 [US2] Simplify `backend/src/main/java/com/simonrowe/chat/ChatController.java` to forward streamed chunks and final events without `toolCallSeen` or `STREAM_RESET`
- [X] T027 [US2] Refactor `frontend/src/components/chat/ChatPanel.tsx` to use block-based assistant messages from `chatStreamReducer.ts` instead of `streamingContent: string`
- [X] T028 [P] [US2] Update `frontend/src/components/chat/ChatMessage.tsx` to render assistant text blocks and preserve user message rendering
- [X] T029 [P] [US2] Add tool activity rendering component in `frontend/src/components/chat/ToolActivityBlock.tsx`
- [X] T030 [US2] Preserve stream timeout, reconnect, close, clear chat, initial query, and message-limit behavior in `frontend/src/components/chat/ChatPanel.tsx`
- [X] T031 [US2] Add or update chat activity styles in `frontend/src/styles.css`

**Checkpoint**: User Stories 1 and 2 work independently: homepage renders and chat streams with tool activity.

---

## Phase 5: User Story 3 - Review rich inline evidence cards (Priority: P3)

**Goal**: Supported skills, employment, code, and blog lookups render structured inline cards in conversation order.

**Independent Test**: Ask one question per supported content category and confirm each response includes the appropriate card when data exists.

### Tests for User Story 3

- [X] T032 [P] [US3] Add ProfileMcpTools widget emission tests for skills, jobs, code examples, recent blogs, searched blogs, and empty results in `backend/src/test/java/com/simonrowe/mcp/ProfileMcpToolsTest.java`
- [X] T033 [P] [US3] Add widget registry and component tests in `frontend/src/components/chat/widgets/ChatWidgetRegistry.test.tsx`
- [X] T034 [P] [US3] Add rendering tests for `SkillsWidget`, `EmploymentWidget`, `CodeExampleWidget`, and `BlogListWidget` under `frontend/src/components/chat/widgets/`

### Implementation for User Story 3

- [X] T035 [US3] Inject `ChatStreamPublisher` into `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java`
- [X] T036 [US3] Add `TOOL_START`, non-empty `skills` widget, and `TOOL_END` emission to `getSkills` in `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java`
- [X] T037 [US3] Add `TOOL_START`, non-empty `employment` widget, and `TOOL_END` emission to `getJobs` in `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java`
- [X] T038 [US3] Add `TOOL_START`, non-empty `code` widget, and `TOOL_END` emission to `getCodeExamples` in `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java`
- [X] T039 [US3] Add `TOOL_START`, non-empty `blogs` widget, and `TOOL_END` emission to `getRecentBlogs` and `searchBlogs` in `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java`
- [X] T040 [P] [US3] Implement widget registry in `frontend/src/components/chat/widgets/ChatWidgetRegistry.tsx`
- [X] T041 [P] [US3] Implement skills card in `frontend/src/components/chat/widgets/SkillsWidget.tsx`
- [X] T042 [P] [US3] Implement employment card in `frontend/src/components/chat/widgets/EmploymentWidget.tsx`
- [X] T043 [P] [US3] Implement code example card with syntax highlighting in `frontend/src/components/chat/widgets/CodeExampleWidget.tsx`
- [X] T044 [P] [US3] Implement blog list card in `frontend/src/components/chat/widgets/BlogListWidget.tsx`
- [X] T045 [US3] Render widget blocks from `frontend/src/components/chat/ChatMessage.tsx` or a dedicated block renderer and skip unknown widget kinds gracefully
- [X] T046 [US3] Remove the `/code-examples/{id}` markdown-link drawer dependency from primary chat card behavior in `frontend/src/components/chat/ChatMessage.tsx` and `frontend/src/components/chat/ChatPanel.tsx`
- [X] T047 [US3] Add widget card styles in `frontend/src/styles.css`

**Checkpoint**: User Stories 1, 2, and 3 work independently: homepage renders, chat streams, and supported cards render inline.

---

## Phase 6: User Story 4 - Continue browsing from the refreshed landing page (Priority: P4)

**Goal**: Visitors can navigate from the refreshed landing page to experience, blog, news, CV, contact, and social destinations without broken behavior.

**Independent Test**: Follow every visible homepage navigation item and call to action and confirm it reaches the intended destination or clear fallback.

### Tests for User Story 4

- [X] T048 [P] [US4] Add navigation and CTA tests for homepage links in `frontend/src/pages/HomePage.test.tsx`
- [X] T049 [P] [US4] Add keyboard navigation coverage for top nav, chat prompts, composer, card links, CTAs, and footer links in `frontend/src/pages/HomePage.test.tsx`

### Implementation for User Story 4

- [X] T050 [US4] Verify homepage route links in `frontend/src/components/layout/TopNav.tsx`, `frontend/src/components/layout/MobileMenu.tsx`, `frontend/src/components/home/HeroSection.tsx`, and `frontend/src/components/layout/Footer.tsx`
- [X] T051 [US4] Ensure unavailable destinations use clear fallback behavior in `frontend/src/components/home/HeroSection.tsx` and `frontend/src/components/home/AboutSection.tsx`
- [X] T052 [US4] Preserve or update tour-relevant selectors in `frontend/src/components/home/HeroSection.tsx`, `frontend/src/components/home/AboutSection.tsx`, and `frontend/src/styles.css`
- [X] T053 [US4] Run manual keyboard and responsive checks from `specs/023-landing-chat-widgets/quickstart.md`

**Checkpoint**: All user stories are independently functional and linked homepage exploration works.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Final verification and cleanup across frontend and backend.

- [X] T054 [P] Update `specs/023-landing-chat-widgets/quickstart.md` if implementation-specific validation steps change
- [X] T055 Run `npm run build` in `frontend/` and fix any frontend type or build errors
- [X] T056 Run `npm test` in `frontend/` and fix failing frontend tests
- [X] T057 Run `npm run lint` in `frontend/` and fix lint issues
- [X] T058 Run `../gradlew test` in `backend/` and fix failing backend tests
- [X] T059 Perform manual visual QA against `designs/screenshots/desktop.png`, `designs/screenshots/chat-test.png`, and representative mobile/tablet viewport screenshots
- [X] T060 Confirm `STREAM_RESET` is absent from backend and frontend chat contracts using `rg "STREAM_RESET" backend/src frontend/src`
- [X] T061 Review changed files for unrelated edits and keep the diff scoped to landing/chat widget work

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 Setup**: No dependencies.
- **Phase 2 Foundational**: Depends on Phase 1 and blocks all user stories.
- **Phase 3 US1**: Depends on Phase 2; can be delivered as MVP landing refresh.
- **Phase 4 US2**: Depends on Phase 2; can proceed in parallel with US1 after shared contracts are stable.
- **Phase 5 US3**: Depends on Phase 2 and benefits from US2 block rendering.
- **Phase 6 US4**: Depends on US1 for refreshed link surfaces.
- **Phase 7 Polish**: Depends on selected user stories being complete.

### User Story Dependencies

- **US1 (P1)**: No dependency on other user stories after Phase 2.
- **US2 (P2)**: No dependency on US1 after Phase 2, but shares chat styling with US1.
- **US3 (P3)**: Depends on US2 block rendering to display cards correctly.
- **US4 (P4)**: Depends on US1 landing surfaces.

### Parallel Opportunities

- T003 and T004 can run in parallel.
- T006, T007, T008, T010, and T011 can run in parallel after T005 shape is understood.
- US1 component tests and individual component refactors can run in parallel where files do not overlap.
- Backend streaming tests, frontend ChatPanel tests, and tool activity component work can run in parallel in US2.
- Widget components T041-T044 can run in parallel after T040 defines the registry contract.
- US4 navigation and keyboard tests can run in parallel with link/fallback review.

## Parallel Example: User Story 3

```bash
# Backend and frontend widget work can split cleanly:
Task: "Add ProfileMcpTools widget emission tests for skills, jobs, code examples, recent blogs, searched blogs, and empty results in backend/src/test/java/com/simonrowe/mcp/ProfileMcpToolsTest.java"
Task: "Implement skills card in frontend/src/components/chat/widgets/SkillsWidget.tsx"
Task: "Implement employment card in frontend/src/components/chat/widgets/EmploymentWidget.tsx"
Task: "Implement code example card with syntax highlighting in frontend/src/components/chat/widgets/CodeExampleWidget.tsx"
Task: "Implement blog list card in frontend/src/components/chat/widgets/BlogListWidget.tsx"
```

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 and Phase 2.
2. Complete Phase 3 for the refreshed chat-first homepage.
3. Validate homepage desktop/mobile/theme behavior.
4. Stop and demo the landing page before deeper streaming/widget work if needed.

### Incremental Delivery

1. Shared contracts and reducer foundation.
2. US1: refreshed landing page.
3. US2: real streaming and visible tool activity.
4. US3: rich inline cards.
5. US4: navigation, accessibility, and route polish.
6. Full build, test, lint, and manual visual QA.

### Notes

- Keep tasks scoped to the files listed unless implementation discovers a direct dependency.
- Do not introduce another realtime transport, CSS framework, icon library, or public chat persistence.
- Preserve the existing message limits and public visitor authentication boundary.
- Verify tests fail before implementing when adding new test coverage.
