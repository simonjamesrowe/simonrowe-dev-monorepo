# Tasks: CoParent Assistant Action Proposals

**Input**: Design documents from `/specs/049-coparent-assistant/`

**Tests**: The feature plan explicitly requires backend, Testcontainers, frontend, and Playwright coverage.

## Phase 1: Setup

- [x] T001 Add assistant feature/model configuration in `backend/src/main/java/com/simonrowe/coparent/config/CoparentProperties.java` and `backend/src/main/resources/application.yml`
- [x] T002 Create the assistant package and typed batch/action/payload DTO model under `backend/src/main/java/com/simonrowe/coparent/assistant/`

## Phase 2: Foundational

- [x] T003 Add `V045CreateCoparentAssistantSchema` with ownership and TTL indexes and restore-time recreation
- [x] T004 Add proposal repository ownership/CAS operations and migration integration tests
- [x] T005 Add assistant-only content suppression to `LangfuseContentObservationFilter` with tests
- [x] T006 Add internal assistant action markers to mutable CoParent domain records without changing public DTOs

## Phase 3: User Story 1 - Safe proposal generation (P1)

**Goal**: Convert bounded text/image input into private typed proposal cards without domain mutations.

**Independent Test**: Submit multi-action content and confirm proposals persist privately while domain collections remain unchanged.

- [x] T007 [US1] Implement text/image magic-byte validation and submission bounds with unit tests
- [x] T008 [US1] Implement bounded family-context assembly with timezone and 500-event tests
- [x] T009 [US1] Implement strict inert tool definitions and direct `ChatModel` inference with malformed/no-action/multiple-call tests
- [x] T010 [US1] Implement proposal normalization, ID allowlisting, uncertainty blocking, and action count limits
- [x] T011 [US1] Implement config/create/list/get endpoints with feature-off and ownership integration tests

## Phase 4: User Story 2 - Individual review and decisions (P1)

**Goal**: Edit, approve, and reject every supported action independently with full revalidation.

**Independent Test**: Edit and approve one card, reject another, and verify only the approved domain mutation occurs.

- [x] T012 [US2] Implement typed replacement editing with complete validation and optimistic revisions
- [x] T013 [US2] Implement event/category create, update, and delete executors with full-resource merge and stale-target checks
- [x] T014 [US2] Implement schedule create/withdraw executors with requester/pending checks
- [x] T015 [US2] Implement conversation/message/permission executors with immediate delivery semantics
- [x] T016 [US2] Implement approve/reject endpoints and redacted audit receipts
- [x] T017 [US2] Add integration coverage for every action type, terminal state, stale revision, blocked target, and domain validation

## Phase 5: User Story 3 - Retry and recovery (P2)

**Goal**: Make approvals exactly-once across retries and interruption windows.

**Independent Test**: Simulate failure after mutation and confirm retry reconciles one result.

- [x] T018 [US3] Add atomic claim/finalize/reconcile operations with preallocated IDs
- [x] T019 [US3] Add duplicate approval and simulated crash-window integration tests
- [x] T020 [US3] Verify seven-day TTL and explicit backup exclusion/index restore behavior

## Phase 6: User Story 4 - Responsive Quick add UI (P2)

**Goal**: Provide desktop/mobile capture and a proposal ledger with type-specific editing and decisions.

**Independent Test**: Use the launcher at both breakpoints, analyse, edit, approve/reject, and verify query invalidation and source cleanup.

- [x] T021 [US4] Add assistant TypeScript union, API hooks, multipart submission, and query keys
- [x] T022 [US4] Build the responsive Quick add drawer, family selector, input/image validation, preview cleanup, disclosure, and offline state
- [x] T023 [US4] Build expandable proposal cards with React Hook Form/Zod editors, individual decisions, result links, and retry errors
- [x] T024 [US4] Mount launchers in desktop shell/mobile header and invalidate affected domain queries after apply
- [x] T025 [US4] Add frontend tests for responsive launch, validation, privacy list, editing, blocked/failed/offline states, decisions, and invalidation

## Phase 7: Verification

- [ ] T026 Add Playwright acceptance fixtures for multi-card text, flyer image, ambiguity/injection, and second-parent privacy
- [x] T027 Run backend tests, checkstyle, and coverage; resolve findings
- [x] T028 Run frontend tests, lint, and build; resolve findings
- [ ] T029 Run feature-flagged local smoke and verify raw input absence from Mongo/logging/observations

## Dependencies

- Setup precedes Foundational; Foundational precedes all user stories.
- Proposal generation (US1) precedes decision execution (US2).
- Retry/recovery (US3) extends the US2 state machine.
- The UI (US4) consumes the stable contracts from US1/US2 but can be tested with request mocks.
- Verification follows all implementation phases.
