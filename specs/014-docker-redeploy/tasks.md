# Tasks: Docker Redeploy from Admin Console

**Input**: Design documents from `/specs/014-docker-redeploy/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: Not explicitly requested — test tasks omitted.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Phase 1: Setup

**Purpose**: Extend existing data operations infrastructure to support the redeploy operation type

- [x] T001 Add `REDEPLOY` value to `OperationType` enum in `backend/src/main/java/com/simonrowe/dataops/OperationType.java`
- [x] T002 [P] Create `RedeployProperties` configuration properties record in `backend/src/main/java/com/simonrowe/dataops/RedeployProperties.java` with fields: `composeFile` (default `/workspace/docker-compose.prod.yml`), `services` (default `[backend, frontend, nginx]`), `dockerBinary` (default `docker`), `selfRestartDelaySeconds` (default `5`)
- [x] T003 [P] Add redeploy configuration defaults to `backend/src/main/resources/application.yml` under `redeploy.*` properties

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Docker access infrastructure that MUST be complete before any user story can work end-to-end

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T004 Update `docker-compose.prod.yml` to add volume mounts to the backend service: Docker socket (`/var/run/docker.sock:/var/run/docker.sock:ro`), Docker CLI binary (`/usr/local/bin/docker:/usr/local/bin/docker:ro`), Docker Compose plugin (`/usr/local/lib/docker/cli-plugins:/usr/local/lib/docker/cli-plugins:ro`), compose file (`./docker-compose.prod.yml:/workspace/docker-compose.prod.yml:ro`), and env file (`./.env:/workspace/.env:ro`)

**Checkpoint**: Foundation ready — Docker CLI accessible from backend container

---

## Phase 3: User Story 1 — Redeploy Site with Latest Images (Priority: P1) 🎯 MVP

**Goal**: Admin can click a button on Data Operations page to pull latest images and restart application containers

**Independent Test**: Click "Redeploy Site" button, confirm, verify containers restart with latest images and site comes back online

### Implementation for User Story 1

- [x] T005 [US1] Create `RedeployService` in `backend/src/main/java/com/simonrowe/dataops/RedeployService.java` — inject `DataOperationsService` and `RedeployProperties`; implement `performRedeploy()` method that uses `ProcessBuilder` to run `docker compose -f <composeFile> pull <services>` then `docker compose -f <composeFile> up -d <services>`; parse stdout line-by-line and call `operationsService.updateProgress()` with estimated percentages (pull: 0-50%, restart: 50-100%); call `operationsService.completeOperation()` on success or `operationsService.failOperation()` on non-zero exit code
- [x] T006 [US1] Add `POST /api/admin/data-operations/redeploy` endpoint to `backend/src/main/java/com/simonrowe/dataops/DataOperationsController.java` — follow the existing async pattern: call `requireNoOperationInProgress(REDEPLOY)`, return 409 if blocked, otherwise `CompletableFuture.runAsync(redeployService::performRedeploy)` and return 202 with the initial `DataOperation`
- [x] T007 [P] [US1] Add `startRedeploy()` function to `frontend/src/services/dataOperationsApi.ts` — POST to `/api/admin/data-operations/redeploy` with auth token, return `Promise<DataOperation>`
- [x] T008 [US1] Add "Redeploy Site" action card to `frontend/src/pages/admin/DataOperationsAdmin.tsx` — use `Rocket` icon from lucide-react (or `RefreshCcw`); include card title "Redeploy Site", description "Pull latest container images and restart the application", button "Start Redeploy" disabled when `operationInProgress`; add confirmation dialog explaining that containers will be pulled and restarted and the site will be briefly unavailable; wire button to call `startRedeploy()` and connect to existing SSE progress stream

**Checkpoint**: Admin can trigger a redeploy and see progress. All containers restart with latest images.

---

## Phase 4: User Story 2 — Graceful Self-Restart of Backend (Priority: P1)

**Goal**: Backend persists operation status before restarting itself so the admin sees the result after refresh

**Independent Test**: Trigger redeploy, wait for backend to restart, refresh page, verify last operation shows "Completed"

### Implementation for User Story 2

- [x] T009 [US2] Refactor `RedeployService.performRedeploy()` in `backend/src/main/java/com/simonrowe/dataops/RedeployService.java` to implement ordered restart strategy: (1) pull all images first via `docker compose pull`, (2) restart frontend and nginx via `docker compose up -d frontend nginx`, (3) persist COMPLETED status to MongoDB via `operationsService.completeOperation()` with result summary, (4) schedule backend self-restart after configurable delay via `docker compose up -d backend` using a separate `ProcessBuilder` with `inheritIO()` (fire-and-forget since the process will kill the backend)
- [x] T010 [US2] Add SSE reconnection logic to `frontend/src/pages/admin/DataOperationsAdmin.tsx` — when the SSE `connectProgress` connection drops during a REDEPLOY operation, show a "Reconnecting…" message with a spinner; poll `GET /api/admin/data-operations/status` every 3 seconds until the backend responds; once reconnected, display the last operation result from the status response; clear the reconnecting state

**Checkpoint**: Backend restarts itself cleanly. Admin sees "Completed" status after refresh or automatic reconnection.

---

## Phase 5: User Story 3 — Redeploy Failure Handling (Priority: P2)

**Goal**: Clear error messages when redeployment fails due to Docker issues

**Independent Test**: Simulate Docker unavailability, trigger redeploy, verify error message is shown

### Implementation for User Story 3

- [x] T011 [US3] Add Docker availability check method to `backend/src/main/java/com/simonrowe/dataops/RedeployService.java` — run `docker info` via `ProcessBuilder` and return boolean; expose as a method `isDockerAvailable()` that the controller can call before starting the operation
- [x] T012 [US3] Update `POST /redeploy` endpoint in `backend/src/main/java/com/simonrowe/dataops/DataOperationsController.java` — call `redeployService.isDockerAvailable()` before starting; return 503 with `{"error": "Docker is not accessible. Ensure the Docker socket is mounted."}` if Docker is unavailable
- [x] T013 [US3] Enhance error handling in `RedeployService.performRedeploy()` in `backend/src/main/java/com/simonrowe/dataops/RedeployService.java` — capture stderr from ProcessBuilder; on failure, include the specific error output in the `failOperation()` message (e.g., "Image pull failed: <stderr>", "Container restart failed: <stderr>"); handle process timeout (5 minute max) with a clear timeout error message
- [x] T014 [P] [US3] Add Docker status indicator to `frontend/src/pages/admin/DataOperationsAdmin.tsx` — show the Redeploy card button as disabled with tooltip "Docker not available" if the status endpoint indicates Docker is inaccessible; display error banner when redeploy fails with the server-provided error message

**Checkpoint**: All failure scenarios show clear, actionable error messages.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final validation and cleanup

- [x] T015 Verify redeploy card styling consistency with existing cards in `frontend/src/pages/admin/DataOperationsAdmin.tsx` — ensure BEM class naming, icon sizing, button states, and layout match Backup/Restore/Clear/Rebuild cards
- [x] T016 Run end-to-end validation per `specs/014-docker-redeploy/quickstart.md` — verify Docker socket mount works, redeploy triggers correctly, progress streams via SSE, backend self-restart completes, and final status persists

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Can run in parallel with Phase 1 (different files)
- **User Story 1 (Phase 3)**: Depends on Phase 1 (T001, T002, T003) completion
- **User Story 2 (Phase 4)**: Depends on User Story 1 (Phase 3) completion — refactors RedeployService
- **User Story 3 (Phase 5)**: Depends on User Story 1 (Phase 3) completion — adds error handling to existing code
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Setup (Phase 1) — core redeploy flow
- **User Story 2 (P1)**: Depends on US1 — refactors the restart logic for self-restart
- **User Story 3 (P2)**: Depends on US1 — adds error handling layers; can run in parallel with US2

### Within Each User Story

- Backend service before controller endpoint
- Controller endpoint before frontend API function
- Frontend API function before UI component changes

### Parallel Opportunities

- T002 and T003 can run in parallel (different files)
- T004 can run in parallel with T001-T003 (different file: docker-compose.prod.yml)
- T007 can run in parallel with T005/T006 (frontend vs backend)
- US2 and US3 can run in parallel after US1 completes (US2 modifies RedeployService restart logic, US3 adds error handling — minimal overlap)
- T011 and T014 can run in parallel within US3 (backend vs frontend)

---

## Parallel Example: User Story 1

```bash
# After T005 and T006 complete (backend), T007 can start in parallel:
Task T005: "Create RedeployService in backend/.../RedeployService.java"
Task T006: "Add POST /redeploy endpoint to DataOperationsController.java"
# Meanwhile:
Task T007: "Add startRedeploy() to frontend/.../dataOperationsApi.ts"
# Then T008 depends on T007:
Task T008: "Add Redeploy Site card to DataOperationsAdmin.tsx"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T003)
2. Complete Phase 2: Foundational (T004)
3. Complete Phase 3: User Story 1 (T005-T008)
4. **STOP and VALIDATE**: Test redeploy from admin UI — verify images pull and containers restart
5. Deploy if ready — basic redeploy works

### Incremental Delivery

1. Complete Setup + Foundational → Infrastructure ready
2. Add User Story 1 → Test independently → Deploy (MVP — basic redeploy works)
3. Add User Story 2 → Test independently → Deploy (graceful self-restart with status persistence)
4. Add User Story 3 → Test independently → Deploy (robust error handling)
5. Polish → Final validation

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- No new dependencies required — ProcessBuilder is java.lang, Docker CLI is mounted from host
- The redeploy feature follows the exact same async operation pattern as backup/restore/clear/rebuild
- RedeployService is the only new file with significant logic; everything else extends existing code
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
