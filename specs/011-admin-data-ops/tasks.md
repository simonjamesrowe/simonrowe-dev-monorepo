# Tasks: Admin Data Operations

**Input**: Design documents from `/specs/011-admin-data-ops/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/api.yaml

**Tests**: Not explicitly requested in the feature specification. Test tasks are excluded.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Add Google Drive API dependencies and create shared DTOs/enums used by all operations

- [X] T001 Add Google Drive API dependencies to backend/build.gradle.kts: `google-api-services-drive:v3-rev20250511-2.0.0`, `google-api-client:2.8.0`, `google-auth-library-oauth2-http:1.35.0`
- [X] T002 [P] Create OperationType enum (BACKUP, RESTORE, CLEAR, REBUILD_INDEX) in backend/src/main/java/com/simonrowe/dataops/OperationType.java
- [X] T003 [P] Create DataOperation record (id, type, status, startedAt, completedAt, progressMessage, progressPercent, errorMessage, resultSummary) in backend/src/main/java/com/simonrowe/dataops/DataOperation.java
- [X] T004 [P] Create BackupMetadata record (fileId, fileName, createdAt, fileSize, fileSizeFormatted) in backend/src/main/java/com/simonrowe/dataops/BackupMetadata.java
- [X] T005 [P] Create DataOperationsStatus record (googleDriveConnected, googleDriveError, operationInProgress, currentOperation, lastOperation) in backend/src/main/java/com/simonrowe/dataops/DataOperationsStatus.java
- [X] T006 [P] Create RestoreRequest record (backupFileId) in backend/src/main/java/com/simonrowe/dataops/RestoreRequest.java
- [X] T007 [P] Create ClearRequest record (confirmationPhrase) in backend/src/main/java/com/simonrowe/dataops/ClearRequest.java

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Google Drive client setup, operation orchestration service with concurrency control and SSE, frontend page shell with routing

**CRITICAL**: No user story work can begin until this phase is complete

- [X] T008 Add `google.drive.credentials` property to backend/src/main/resources/application.yml with env var binding `${GOOGLE_DRIVE_CREDENTIALS:}` (empty default = disabled)
- [X] T009 Create GoogleDriveConfig in backend/src/main/java/com/simonrowe/dataops/GoogleDriveConfig.java — Spring @Configuration that decodes the base64 env var, creates ServiceAccountCredentials scoped to DriveScopes.DRIVE_FILE, and builds a Drive client bean. If credentials are empty/missing, the bean should be @ConditionalOnProperty and the service should report "not connected"
- [X] T010 Create GoogleDriveService in backend/src/main/java/com/simonrowe/dataops/GoogleDriveService.java — wrapper around Drive API with methods: checkConnection(), findOrCreateFolder(name), uploadFile(folderId, fileName, inputStream), listFiles(folderId), downloadFile(fileId, outputStream), deleteFile(fileId). Handle the fixed "simonrowe-backups" folder name
- [X] T011 Create DataOperationsService in backend/src/main/java/com/simonrowe/dataops/DataOperationsService.java — operation orchestration with AtomicReference<DataOperation> for concurrency control, SseEmitter management for progress streaming, methods: tryStartOperation(type), updateProgress(message, percent), completeOperation(summary), failOperation(error), getStatus(), streamProgress(). Reject concurrent operations with 409
- [X] T012 Create DataOperationsController in backend/src/main/java/com/simonrowe/dataops/DataOperationsController.java — REST controller at /api/admin/data-operations with endpoints: GET /status, GET /progress (SSE), and stub POST endpoints for /backup, /restore, /clear, /rebuild-index that return 501 Not Implemented (implemented in story phases). Secure with existing OAuth2 resource server config
- [X] T013 [P] Create dataOperationsApi.ts in frontend/src/services/dataOperationsApi.ts — TypeScript API client with functions: getStatus(), startBackup(), listBackups(), startRestore(backupFileId), startClear(confirmationPhrase), startRebuildIndex(), and SSE helper connectProgress(onEvent) using EventSource
- [X] T014 [P] Create DataOperationsAdmin.tsx page shell in frontend/src/pages/admin/DataOperationsAdmin.tsx — page layout with Google Drive connection status indicator, operation status/progress section, and placeholder action buttons for backup, restore, clear, and rebuild. Use Lucide React icons (Database, CloudUpload, CloudDownload, Trash2, RefreshCw). Include SSE progress listener that updates status in real-time
- [X] T015 Add "Data Operations" nav item to sidebar in frontend/src/components/admin/AdminLayout.tsx — add after "Media" using Lucide React Database icon, linking to /admin/data-operations
- [X] T016 Add /admin/data-operations route in frontend/src/App.tsx importing DataOperationsAdmin component
- [X] T017 [P] Add Data Operations page styles to frontend/src/styles.css — BEM classes for status indicators (.data-ops__status, .data-ops__actions, .data-ops__progress, .data-ops__confirmation-dialog), progress bar, action cards layout, backup list table

**Checkpoint**: Foundation ready — admin can navigate to Data Operations page, see Google Drive connection status and a progress area. All operation buttons visible but non-functional.

---

## Phase 3: User Story 1 — Backup Data to Google Drive (Priority: P1) MVP

**Goal**: Admin can trigger a full backup of all MongoDB collections and media files to Google Drive and see real-time progress

**Independent Test**: Navigate to Data Operations, click "Backup to Google Drive", verify ZIP archive appears in Google Drive "simonrowe-backups" folder with all 9 collection JSON files and uploads directory

### Implementation for User Story 1

- [X] T018 [US1] Create BackupService in backend/src/main/java/com/simonrowe/dataops/BackupService.java — implements the full backup workflow: (1) export all 9 MongoDB collections as Extended JSON using MongoTemplate.findAll(Document.class, collectionName) with JsonMode.EXTENDED, (2) create manifest.json with version, timestamp, collection counts, media file count, (3) package collections/ and uploads/ into a ZIP archive using java.util.zip.ZipOutputStream, (4) upload ZIP to Google Drive via GoogleDriveService, (5) clean up temp files. Report progress via DataOperationsService.updateProgress() at each step
- [X] T019 [US1] Implement POST /backup endpoint in DataOperationsController — replace 501 stub with: check Google Drive connected (503 if not), call tryStartOperation(BACKUP) (409 if busy), run BackupService.performBackup() asynchronously via @Async or CompletableFuture, return 202 with DataOperation. Handle errors: catch exceptions, call failOperation(), clean up partial Google Drive uploads
- [X] T020 [US1] Wire up backup button in frontend/src/pages/admin/DataOperationsAdmin.tsx — "Backup to Google Drive" button calls startBackup(), connects to SSE progress stream, shows progress bar with message and percentage, displays success with timestamp and file size on completion, shows error message on failure. Disable all operation buttons while any operation is in progress (FR-009)

**Checkpoint**: Admin can perform a full backup to Google Drive with real-time progress. This is the MVP.

---

## Phase 4: User Story 2 — Restore Data from Google Drive (Priority: P2)

**Goal**: Admin can select a backup from Google Drive and restore all data with automatic pre-restore safety backup

**Independent Test**: Create a backup (US1), modify some data, restore from the backup, verify all data matches the backup state and search index is rebuilt

### Implementation for User Story 2

- [X] T021 [US2] Implement GET /backups endpoint in DataOperationsController — list all ZIP files in the "simonrowe-backups" Drive folder via GoogleDriveService.listFiles(), map to BackupMetadata records with fileId, fileName, createdAt (from Drive metadata), fileSize, fileSizeFormatted. Sort by createdAt descending. Return 503 if Drive not connected
- [X] T022 [US2] Create RestoreService in backend/src/main/java/com/simonrowe/dataops/RestoreService.java — implements restore workflow: (1) create local pre-restore backup (call BackupService to create ZIP locally without uploading to Drive), (2) download selected backup ZIP from Google Drive via GoogleDriveService.downloadFile(), (3) validate archive integrity (check manifest.json exists and is valid), (4) drop all 9 MongoDB collections, (5) import collections in dependency order: first tags/skills/profiles/social_medias/tourSteps/media_assets, then skill_groups/jobs, then blogs — parse Extended JSON with Document.parse(), insertMany via MongoTemplate, (6) delete existing uploads and extract uploads/ from ZIP, (7) trigger IndexService.fullSyncSiteIndex() and fullSyncBlogIndex() for search rebuild. Report progress at each step. On failure, report what succeeded and what failed
- [X] T023 [US2] Implement POST /restore endpoint in DataOperationsController — replace 501 stub with: validate RestoreRequest.backupFileId not blank (400 if invalid), check Drive connected (503), call tryStartOperation(RESTORE) (409 if busy), run RestoreService.performRestore() asynchronously, return 202 with DataOperation
- [X] T024 [US2] Add restore UI to frontend/src/pages/admin/DataOperationsAdmin.tsx — "Restore from Google Drive" button opens a backup selection panel: fetch backup list from GET /backups, display as a table with fileName, createdAt, fileSizeFormatted. Selecting a backup shows a confirmation dialog warning "All current data will be replaced" with confirm/cancel. On confirm, call startRestore(backupFileId), show SSE progress, display success/failure result

**Checkpoint**: Admin can list backups, select one, confirm restore, and see data replaced with search index rebuilt.

---

## Phase 5: User Story 3 — Clear All Data (Priority: P3)

**Goal**: Admin can clear all local application data with a typed confirmation safeguard

**Independent Test**: Click "Clear All Data", type "DELETE ALL DATA", confirm, verify all MongoDB collections are empty, uploads directory is empty, and search indices are cleared

### Implementation for User Story 3

- [X] T025 [US3] Create ClearService in backend/src/main/java/com/simonrowe/dataops/ClearService.java — implements clear workflow: (1) drop all 9 MongoDB collections via MongoTemplate, (2) delete all files in the uploads directory (configurable via uploads.path property), (3) delete and recreate Elasticsearch indices via IndexService (or direct ElasticsearchClient calls). Report progress at each step. Google Drive backups are explicitly NOT touched (FR-006)
- [X] T026 [US3] Implement POST /clear endpoint in DataOperationsController — replace 501 stub with: validate ClearRequest.confirmationPhrase equals "DELETE ALL DATA" exactly (400 if mismatch), call tryStartOperation(CLEAR) (409 if busy), run ClearService.performClear() asynchronously, return 202 with DataOperation
- [X] T027 [US3] Add clear UI to frontend/src/pages/admin/DataOperationsAdmin.tsx — "Clear All Data" button (styled as danger/destructive with Trash2 icon) opens a confirmation dialog: warning text explaining all local data will be permanently deleted and Google Drive backups are unaffected, text input requiring the user to type "DELETE ALL DATA", confirm button disabled until phrase matches. On confirm, call startClear(), show SSE progress, display success/failure result

**Checkpoint**: Admin can clear all local data with typed confirmation. Google Drive backups remain intact.

---

## Phase 6: User Story 4 — Rebuild Search Index (Priority: P4)

**Goal**: Admin can trigger a full search index rebuild from the UI without command-line access

**Independent Test**: Click "Rebuild Search Index", wait for completion, verify search results reflect current MongoDB data

### Implementation for User Story 4

- [X] T028 [US4] Implement POST /rebuild-index endpoint in DataOperationsController — replace 501 stub with: call tryStartOperation(REBUILD_INDEX) (409 if busy), run index rebuild asynchronously: call IndexService.fullSyncSiteIndex() and IndexService.fullSyncBlogIndex(), report progress via DataOperationsService, count documents indexed for result summary. Handle ElasticsearchException and report via failOperation()
- [X] T029 [US4] Add rebuild UI to frontend/src/pages/admin/DataOperationsAdmin.tsx — "Rebuild Search Index" button (RefreshCw icon) with a simple confirmation ("This will rebuild all search indices. Continue?"), calls startRebuildIndex(), shows SSE progress with document count on completion

**Checkpoint**: All four operations are functional. Admin can backup, restore, clear, and rebuild from the UI.

---

## Phase 7: User Story 5 — Google Drive Authentication Setup Guide (Priority: P5)

**Goal**: Provide a step-by-step guide for configuring Google Drive service account authentication

**Independent Test**: A new admin can follow the guide and see "Connected" status on the Data Operations page

### Implementation for User Story 5

- [X] T030 [US5] Create Google Drive setup guide at docs/google-drive-setup.md — step-by-step instructions covering: (1) create a Google Cloud project, (2) enable the Google Drive API, (3) create a service account with no special roles, (4) create and download a JSON key for the service account, (5) base64-encode the JSON key (provide commands for macOS and Linux), (6) set the GOOGLE_DRIVE_CREDENTIALS environment variable, (7) verify connection by checking the Data Operations page shows "Connected", (8) troubleshooting section for common errors (invalid credentials, API not enabled, quota exceeded)

**Checkpoint**: Documentation complete. New admin can follow guide to enable Google Drive backup/restore.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: GraalVM native image support, error handling hardening, final validation

- [ ] T031 [P] Run GraalVM tracing agent during test suite to generate native image reflection configs for Google Drive API client — output to backend/src/main/resources/META-INF/native-image/ (reflect-config.json, resource-config.json, proxy-config.json). Verify native image compiles successfully
- [X] T032 [P] Add structured logging to all data operation services (BackupService, RestoreService, ClearService, GoogleDriveService) — log operation start/complete/fail with operation ID, type, duration, and error details using SLF4J
- [X] T033 Run full backend test suite (cd backend && ../gradlew test) and fix any failures
- [X] T034 Run full frontend test suite (cd frontend && npm test) and fix any failures
- [ ] T035 Run quickstart.md validation — start backend and frontend, navigate to /admin/data-operations, verify all four operations work end-to-end

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **US1 Backup (Phase 3)**: Depends on Foundational — MVP target
- **US2 Restore (Phase 4)**: Depends on Foundational. Uses BackupService from US1 for pre-restore backup, so US1 should complete first
- **US3 Clear (Phase 5)**: Depends on Foundational only — can run in parallel with US1/US2
- **US4 Rebuild Index (Phase 6)**: Depends on Foundational only — can run in parallel with US1/US2/US3
- **US5 Setup Guide (Phase 7)**: No code dependencies — can run in parallel with any phase
- **Polish (Phase 8)**: Depends on all user stories being complete

### User Story Dependencies

```
Phase 1 (Setup) → Phase 2 (Foundational) → US1 (Backup) → US2 (Restore)
                                          → US3 (Clear)   [parallel with US1]
                                          → US4 (Rebuild)  [parallel with US1]
                                          → US5 (Guide)    [parallel with any]
```

- **US1 (P1)**: After Foundational — no other story dependencies
- **US2 (P2)**: After Foundational + US1 (needs BackupService for pre-restore backup)
- **US3 (P3)**: After Foundational — independent of other stories
- **US4 (P4)**: After Foundational — independent of other stories
- **US5 (P5)**: Independent — documentation only, can start anytime

### Parallel Opportunities

**Within Phase 1** (all [P] tasks):
- T002, T003, T004, T005, T006, T007 can all run in parallel (separate files, no dependencies)

**Within Phase 2**:
- T013, T014, T017 can run in parallel (frontend files, independent of backend)
- T008, T009 must be sequential (config → Drive client)
- T010, T011, T012 must be sequential (Drive service → orchestration → controller)

**After Foundational completes**:
- US3 (Clear) and US4 (Rebuild Index) can run in parallel with US1 (Backup)
- US5 (Setup Guide) can run in parallel with anything

---

## Parallel Example: Phase 1 Setup

```bash
# Launch all DTOs/enums in parallel:
Task: "Create OperationType enum in backend/.../dataops/OperationType.java"
Task: "Create DataOperation record in backend/.../dataops/DataOperation.java"
Task: "Create BackupMetadata record in backend/.../dataops/BackupMetadata.java"
Task: "Create DataOperationsStatus record in backend/.../dataops/DataOperationsStatus.java"
Task: "Create RestoreRequest record in backend/.../dataops/RestoreRequest.java"
Task: "Create ClearRequest record in backend/.../dataops/ClearRequest.java"
```

## Parallel Example: After Foundational

```bash
# US1 (Backup), US3 (Clear), US4 (Rebuild), US5 (Guide) can all start simultaneously:
Task: "US1 — BackupService + endpoint + frontend"
Task: "US3 — ClearService + endpoint + frontend"
Task: "US4 — Rebuild index endpoint + frontend"
Task: "US5 — Google Drive setup guide"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T007)
2. Complete Phase 2: Foundational (T008-T017)
3. Complete Phase 3: User Story 1 — Backup (T018-T020)
4. **STOP and VALIDATE**: Test backup end-to-end
5. Deploy/demo if ready — admin can back up data to Google Drive

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. Add US1 (Backup) → Test → Deploy (MVP!)
3. Add US2 (Restore) → Test → Deploy (backup + restore complete)
4. Add US3 (Clear) → Test → Deploy
5. Add US4 (Rebuild Index) → Test → Deploy
6. Add US5 (Setup Guide) → Review → Merge
7. Polish → Final validation → Production ready

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable (except US2 which needs US1's BackupService)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- The existing IndexService.fullSyncSiteIndex() and fullSyncBlogIndex() are reused — no new search indexing code needed
- MongoDB collections are exported as Extended JSON (JsonMode.EXTENDED) for lossless DBRef round-trip
- All endpoints follow the existing admin controller pattern at /api/admin/data-operations/*
