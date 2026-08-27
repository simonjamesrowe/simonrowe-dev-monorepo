---
description: "Task list for 034-platform-datastore-backup"
---

# Tasks: Platform Datastore Backup

**Input**: Design documents from `/specs/034-platform-datastore-backup/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Included. **Revision 2** changes their shape: the capture is bash now, so
it is covered by `shellcheck`, `--dry-run` and a real run against a throwaway stack —
the method that found three real bugs in R5 — rather than by a `CommandRunner` seam.
The Temporal activity, workflow and schedule initializer are unit-tested with mocks,
mirroring `PhaseRunnerTest` and `CveFixScheduleInitializerTest`.

**Organization**: Grouped by user story so each is independently implementable and
testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: `[US1]`/`[US2]`/`[US3]` — maps to the user stories in spec.md
- Every task names its exact file path

## Path Conventions

Web app monorepo. **Revision 2** shifts the centre of gravity to root-level
`scripts/` and `software-factory/src/{main,test}/java/com/simonrowe/factory/platformbackup/`;
`backend/` keeps only the read-only Drive listing. Also `frontend/src/`,
`frontend/tests/`, `config/`, `docs/runbooks/`.

---

## Revision 2 — 2026-08-26

**Tasks T001–T050 below are superseded.** Constitution 2.0.0 moved the capture out of
the backend; see plan.md "Revision 2" and research R11–R13. The revised list is
**RT001–RT030** in this section. The old list is kept below, unedited, because a
lot of it is *done and still valid* and the revised tasks reference it by number.

**What survives from the original work, unchanged and already merged-in-branch:**
`scripts/restore-platform.sh` (T037–T044, verified end to end), the ClickHouse
config overlay and volume and chown one-shot (T001–T006), the Drive folder isolation
(T015, T016) and its regression test, the runbook (T047), and the manifest and
fingerprint *formats* (data-model.md §2, §3).

**What is deleted:** T010, T012, T013, T014, T024–T029 and their tests
(T009, T011, T019–T023) — the Java capture path. T032's `POST` half. T007's
`backup.platform.*` block.

---

### Phase R1: Remove the superseded Java capture path

- [X] RT001 Delete `PlatformBackupService`, `PlatformBackupScheduler`, `PlatformBackupProperties`, `PlatformManifest`, `SecretFingerprinter`, `CommandRunner`, `ProcessCommandRunner` from `backend/src/main/java/com/simonrowe/dataops/`, and their five test classes. All of it violates constitution 2.0.0 or becomes dead with the capture moved
- [X] RT002 Remove `PlatformBackupProperties` from `@EnableConfigurationProperties` in `backend/src/main/java/com/simonrowe/WebConfig.java` (note main also removed `RedeployProperties` there — keep both removals)
- [X] RT003 Remove `POST /platform-backup` from `backend/src/main/java/com/simonrowe/dataops/DataOperationsController.java`, keeping `GET /platform-backups`; drop `PLATFORM_BACKUP` from `OperationType` since nothing in the backend runs the operation
- [X] RT004 Remove `prunePlatformToLimit()` and the `platformMaxBackups` field from `BackupRetentionService`, and their tests — the script owns retention now. **Keep every existing application-backup assertion untouched** (FR-014, SC-004)
- [X] RT005 Remove the `backup.platform.*` block from `backend/src/main/resources/application.yml`, keeping `google.drive.platform-folder-id` (the backend still resolves that folder to list it)
- [X] RT006 Remove the `langfuse-clickhouse-backups:/clickhouse-backups` mount from the `backend` service in `docker-compose.prod.yml`
- [X] RT007 Confirm `NoHostProcessLaunchTest` passes — the single check that this phase is complete

**Checkpoint**: `cd backend && ../gradlew test checkstyleMain checkstyleTest` green, including main's `NoHostProcessLaunchTest`.

---

### Phase R2: The capture script (the bulk of the work)

- [X] RT008 Create `scripts/backup-platform.sh` with the house shape: `#!/usr/bin/env bash`, `set -euo pipefail`, `SCRIPT_DIR`/`PROJECT_DIR`, precondition validation, `--dry-run`/`--keep-local`/`--no-upload` flags. Mirror `restore-platform.sh` so the pair reads as one tool
- [X] RT009 [P] Port the `.env` reader and the `sha256`/`fingerprint_of` helpers verbatim from `restore-platform.sh` into a shared idiom. **`printf '%s'`, never `echo`** — the newline trap from R7, now the only place it can occur
- [X] RT010 Implement the orphan sweep of the ClickHouse backup directory before anything else (FR-008)
- [X] RT011 Implement `pg_dumpall --roles-only` and the four `pg_dump` calls via `docker exec -e PGPASSWORD` (bare name, no value — R2 keeps the password out of `argv`), checking each exit code and asserting non-empty output before continuing
- [X] RT012 Implement the ClickHouse capture: `BACKUP DATABASE default TO File(...)`, move the result off the shared volume, and collect `system.parts` row counts **excluding `.inner%`** (R5 — those UUIDs change across a restore)
- [X] RT013 Write `manifest.json` last, to the schema in data-model.md §2, including the four secret fingerprints (§3), the per-dump byte counts and the running image tags
- [X] RT014 Zip the tree to a staging path with owner-only permissions — the archive carries `pg_dumpall` role password hashes
- [X] RT015 Implement Drive OAuth + folder resolution by **name** (`simonrowe-platform-backups`), reusing the token-exchange code already proven in `restore-platform.sh`. It must never fall back to `GOOGLE_DRIVE_FOLDER_ID` (R1)
- [X] RT016 Implement the **resumable** upload: session-URI `POST`, then ranged `PUT` with resume-on-interrupt (R12). This is the one genuinely new, unproven piece
- [X] RT017 Implement retention: list `.zip` in the platform folder, delete past the newest 7, log a per-file failure without aborting the sweep (FR-019). Prune **only after a successful upload** (FR-013)
- [X] RT018 Implement `trap`-based cleanup of the local archive and the ClickHouse volume file on **both** paths (FR-008)
- [X] RT019 `shellcheck scripts/backup-platform.sh` clean; `--dry-run` prints every command and changes nothing

**Checkpoint**: a real capture against a throwaway stack produces an archive whose entries and manifest match data-model.md, and `restore-platform.sh` restores from it.

---

### Phase R3: Temporal orchestration in the deployer

- [X] RT020 [P] Add `PlatformBackupProperties` (`@ConfigurationProperties`) to `software-factory` — script path, enabled flag, task queue — following `DeployProperties`
- [X] RT021 Implement `PlatformBackupActivitiesImpl`, invoking the script through the existing `ProcessRunner` and forwarding output to `Activity.getExecutionContext().heartbeat()` so a long capture cannot trip the heartbeat timeout. Model on `PhaseRunner`; **this is the only Java that touches the script**
- [X] RT022 Implement `PlatformBackupWorkflow`/`Impl` — one activity, an explicit retry policy, a `startToCloseTimeout` sized past the measured capture. Retry is what replaces the client-library upload resumability revision 1 relied on
- [X] RT023 Implement `PlatformBackupScheduleInitializer` — 02:00 Europe/London, declared in code so a deploy reconciles it, **paused by default** behind `FACTORY_PLATFORM_BACKUP_ENABLED`. Copy `CveFixScheduleInitializer` including its posture
- [X] RT024 [P] Tests: `PlatformBackupActivitiesImplTest` (mirroring `PhaseRunnerTest`), `PlatformBackupWorkflowTest` using the Temporal test framework, `PlatformBackupScheduleInitializerTest`. Cover the failure path — a non-zero script exit must fail the activity, not be swallowed
- [X] RT025 Add the deployer's `FACTORY_PLATFORM_BACKUP_*` env and the `langfuse-clickhouse-backups:/backups` mount to `docker-compose.prod.yml`, following the `FACTORY_DEPLOY_SCRIPT` convention

**Checkpoint**: `./gradlew :software-factory:check` green; a workflow run triggered by hand executes the script and reports success.

---

### Phase R4: Frontend, docs, rollout

- [X] RT026 Reduce the "Platform Data" card and `dataOperationsApi.ts` to the read-only listing: drop `startPlatformBackup`, drop `PLATFORM_BACKUP` from the `DataOperation` union, keep the archive list and the error handling that reports a *listing* failure distinctly. Update `DataOperationsAdmin.platform.test.tsx` to match, and add the pointer to `scripts/backup-platform.sh` (FR-025a)
- [X] RT027 Rewrite the capture half of `docs/runbooks/platform-backup-restore.md`: the script, the schedule, how to enable it, **how to assert a live poller on the task queue** (a `healthy` container with no poller is a documented silent-failure mode here), and the on-demand command. The restore half is unchanged
- [X] RT028 Update the `034-platform-datastore-backup` entry in `CLAUDE.md` to describe the deployer/Temporal shape rather than the backend one, and record that the backend deliberately holds no Docker access

---

### Still open from revision 1, unchanged

- [ ] T045 **Blocking for rollout**: measure the ClickHouse archive size on the production host (NFR-004, SC-012)
- [ ] T050 The manual Drive folder isolation check — the two archive lists must be disjoint

### New rollout gates

- [~] RT029 **NOT DONE — needs a real Drive account and a realistically sized archive.** Measure the resumable upload against a realistically sized archive before trusting it (NFR-003, R12) — interrupt it mid-flight and confirm it resumes rather than restarting
- [~] RT030 **NOT DONE — rollout step, needs the production host.** After enabling the schedule, **assert a live poller** on the capture task queue (SC-013). Do not infer it from container health

---

## Superseded task list (revision 1)

> Kept for reference and for the task numbers the revised list cites. Do not work
> from this section.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Config, compose and infrastructure the capture depends on. Nothing
here is Java, and none of it can be discovered later — the ClickHouse path simply
fails without it.

- [X] T001 [P] Create `config/clickhouse/backup-disk.xml` declaring `<clickhouse><backups><allowed_path>/backups/</allowed_path></backups></clickhouse>` — `BACKUP … TO File(…)` is refused unless the target is under an allowed path
- [X] T002 Add the `langfuse-clickhouse-backups` named volume to the `volumes:` block of `docker-compose.prod.yml`, with a comment explaining it is the ClickHouse→backend handoff for `BACKUP`/`RESTORE`
- [X] T003 Mount `config/clickhouse/backup-disk.xml` read-only at `/etc/clickhouse-server/config.d/backup-disk.xml` and `langfuse-clickhouse-backups` at `/backups` in the `langfuse-clickhouse` service in `docker-compose.prod.yml`
- [X] T004 Mount `langfuse-clickhouse-backups` at `/clickhouse-backups` in the `backend` service in `docker-compose.prod.yml`
- [X] T005 Add a `clickhouse-backups-init` one-shot busybox service to `docker-compose.prod.yml` running `chown -R 101:101 /backups` (mirroring `uploads-init`), and declare it as a `service_completed_successfully` dependency of `langfuse-clickhouse` — a volume mounted at a path absent from the image is created **root-owned**, and ClickHouse runs as `101:101`, so without this every `BACKUP` fails on permissions (research R4)
- [X] T006 Add `clickhouse-backups-init` to the `ONESHOT_SERVICES` array in `scripts/monitor-prod.sh` — a one-shot service missing from that list reads as a broken container on every cron tick and makes the watchdog reconcile the whole stack once a minute, forever (research R4)
- [X] T007 [P] Add the `backup.platform.*` block (schedule cron `0 0 2 * * *`, zone `Europe/London`, retention 7, container names, the four database names, `clickhouse-backup-path`) and `google.drive.platform-folder-id: ${GOOGLE_DRIVE_PLATFORM_FOLDER_ID:}` to `backend/src/main/resources/application.yml`, leaving the existing `backup.schedule` and `backup.retention` values untouched

**Checkpoint**: `docker compose config` parses; the ClickHouse backup path is
writable (`docker exec langfuse-clickhouse sh -c 'touch /backups/.probe && rm /backups/.probe'`).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The shared seams every user story needs. All three stories read the
manifest format and the fingerprint scheme, so these cannot be deferred into a
story.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T008 [P] Add `PLATFORM_BACKUP` to `OperationType` in `backend/src/main/java/com/simonrowe/dataops/OperationType.java`
- [X] T009 [P] Write `SecretFingerprinterTest` in `backend/src/test/java/com/simonrowe/dataops/SecretFingerprinterTest.java` asserting a **fixed known-answer vector** — the exact hex for a literal name/value pair — plus: no trailing newline in the digest input, different names over the same value produce different digests, and a null/blank value yields `null` rather than a digest of the empty string
- [X] T010 Implement `SecretFingerprinter` in `backend/src/main/java/com/simonrowe/dataops/SecretFingerprinter.java` as `hex(SHA-256("platform-backup-fingerprint-v1:" + name + ":" + value))`, reading the four keys (`ENCRYPTION_KEY`, `SALT`, `NEXTAUTH_SECRET`, `DEPENDENCYTRACK_KEK`) from the environment and returning an ordered map with explicit `null` for absent values
- [X] T011 [P] Write `PlatformManifestTest` in `backend/src/test/java/com/simonrowe/dataops/PlatformManifestTest.java` asserting the serialised JSON matches the shape in data-model.md §2, that all four fingerprint keys are always present even when null, and — the load-bearing assertion — that **no secret value appears anywhere in the output** when the fingerprinter is fed known secrets
- [X] T012 Implement `PlatformManifest` (plus nested `DumpEntry` and `ClickHouseSection` records) in `backend/src/main/java/com/simonrowe/dataops/PlatformManifest.java` with `schemaVersion = 1`, hand-rolled JSON serialisation matching `BackupService.buildManifest`'s style (no new dependency, native-image safe)
- [X] T013 [P] Implement `CommandRunner` in `backend/src/main/java/com/simonrowe/dataops/CommandRunner.java` — an interface over `ProcessBuilder` exposing "run, stream stdout to an `OutputStream`, return bytes written" and "run, capture stdout as a String", plus a `ProcessCommandRunner` implementation that drains stderr on a separate thread and checks the exit code **after** stdout reaches EOF (research R8; checking early is how a truncated dump gets archived as a success)
- [X] T014 [P] Implement `PlatformBackupProperties` as `@ConfigurationProperties("backup.platform")` in `backend/src/main/java/com/simonrowe/dataops/PlatformBackupProperties.java`, mirroring `RedeployProperties`
- [X] T015 Write `GoogleDriveFolderResolutionTest` in `backend/src/test/java/com/simonrowe/dataops/GoogleDriveFolderResolutionTest.java` asserting that with `google.drive.folder-id` **set**, `findOrCreateFolder()` returns the configured id while the platform resolver returns a *different*, name-resolved folder — the regression guard for the silent-eviction bug in research R1
- [X] T016 Add `findOrCreateFolderByName(String)` and `findOrCreatePlatformFolder()` to `backend/src/main/java/com/simonrowe/dataops/GoogleDriveService.java`, with `PLATFORM_FOLDER_NAME = "simonrowe-platform-backups"` and a `google.drive.platform-folder-id` override; `findOrCreateFolder()` must keep its exact current behaviour (configured id first, else name lookup) so the Mongo path is byte-for-byte unchanged
- [X] T017 Extend `BackupRetentionServiceTest` in `backend/src/test/java/com/simonrowe/dataops/BackupRetentionServiceTest.java` for the new folder-parameterised signature, **keeping every existing Mongo-folder assertion unchanged** — FR-014/SC-004 make "the Mongo backup is unaffected" a verifiable requirement, and these are the assertions that verify it
- [X] T018 Generalise `BackupRetentionService` in `backend/src/main/java/com/simonrowe/dataops/BackupRetentionService.java` to `pruneToLimit(String folderId, int maxBackups)`, retaining a no-arg `pruneToLimit()` overload that resolves the existing folder and `backup.retention.max-backups`; per-file delete failures continue to be logged without aborting the sweep

**Checkpoint**: `cd backend && ../gradlew test checkstyleMain checkstyleTest` green.
Foundation ready — user stories can now proceed.

---

## Phase 3: User Story 1 — Nightly platform capture (Priority: P1) 🎯 MVP

**Goal**: A dated archive of all five platform datastores is captured and uploaded
to its own Drive folder every night, retaining the newest 7, with no operator
action.

**Independent Test**: Set `backup.platform.schedule.cron` to a near time on an
environment with Drive connected; confirm one archive appears in
`simonrowe-platform-backups` containing all seven expected entries, that the
`simonrowe-backups` folder is untouched, and that an eighth upload evicts only the
oldest platform archive.

**Why this is the MVP**: until the capture exists and the data is off-host, nothing
else in this feature protects anything. Even with no restore tooling at all, this
alone converts permanent loss into recoverable-with-effort.

### Tests for User Story 1

> Write these first; confirm they fail before implementing T024–T026.

- [X] T019 [P] [US1] Write `PlatformBackupServiceTest` in `backend/src/test/java/com/simonrowe/dataops/PlatformBackupServiceTest.java` — happy path: with a fake `CommandRunner`, the archive contains exactly `manifest.json`, `postgres/roles.sql`, the four `postgres/<db>.sql` entries and `clickhouse/default.zip`, and the manifest's per-database `bytes` match what was streamed
- [X] T020 [P] [US1] Add to `PlatformBackupServiceTest`: a `pg_dump` non-zero exit **fails** the operation (`failOperation` called, `completeOperation` not, returns `false`), and **nothing is uploaded** — a partial archive must never reach Drive
- [X] T021 [P] [US1] Add to `PlatformBackupServiceTest`: temp files and the ClickHouse volume file are deleted on **both** the success and failure paths, and pre-existing orphan files in the ClickHouse backup directory are swept at the start of a run (FR-008)
- [X] T022 [P] [US1] Add to `PlatformBackupServiceTest`: the manifest carries the four secret fingerprints and **no secret values**, and the `.env` file is never added as an archive entry (FR-006, FR-007)
- [X] T023 [P] [US1] Write `PlatformBackupSchedulerTest` in `backend/src/test/java/com/simonrowe/dataops/PlatformBackupSchedulerTest.java` mirroring `BackupSchedulerTest`: skips when Drive is disconnected; skips when the mutex is held; prunes on success; does **not** prune on failure; a prune exception does not fail the backup and does not escape the scheduler thread

### Implementation for User Story 1

- [X] T024 [US1] Implement `PlatformBackupService` in `backend/src/main/java/com/simonrowe/dataops/PlatformBackupService.java`: sweep orphans → `docker exec -e PGPASSWORD langfuse-db pg_dumpall --roles-only` → `pg_dump -d <db>` streamed per database → ClickHouse `BACKUP DATABASE default TO File(…)` then copy off the shared volume → write `manifest.json` last → upload via `GoogleDriveService.findOrCreatePlatformFolder()` reporting progress through `DataOperationsService` → delete local zip and volume file in a `finally`. Returns `boolean` matching `BackupService.performBackup()`; exceptions are caught and converted, never propagated
- [X] T025 [US1] In `PlatformBackupService`, pass Postgres credentials as `docker exec -e PGPASSWORD` (bare name, no value) with `PGPASSWORD` set in the `ProcessBuilder` environment, so the password never appears in any process's `argv` (research R2)
- [X] T026 [US1] In `PlatformBackupService`, collect ClickHouse per-table row counts from `system.parts` (active parts, `database='default'`) into the manifest's `clickhouse.tables` — the only practical way to verify a ClickHouse restore landed everything, since the archive itself is opaque
- [X] T027 [US1] In `PlatformBackupService`, read the running image tags for `langfuse`, `dependencytrack-apiserver` and `langfuse-clickhouse` via `docker inspect --format '{{.Config.Image}}'` into the manifest's `images` map — a `dtrack` dump restored into a later major may need that version's own migration, and this is what records which version produced it
- [X] T028 [US1] Implement `PlatformBackupScheduler` in `backend/src/main/java/com/simonrowe/dataops/PlatformBackupScheduler.java` with `@Scheduled(cron = "${backup.platform.schedule.cron:0 0 2 * * *}", zone = "${backup.platform.schedule.zone:Europe/London}")`, following `BackupScheduler`'s five-step shape and logging a prune failure distinctly from a backup failure
- [X] T029 [US1] Register `PlatformBackupProperties` via `@EnableConfigurationProperties` (or `@ConfigurationPropertiesScan`) wherever the existing `RedeployProperties` is registered, so the new config binds

**Checkpoint**: User Story 1 is fully functional. A nightly (or cron-overridden)
run produces a complete archive in the platform folder and prunes to 7, with the
Mongo folder untouched.

---

## Phase 4: User Story 2 — On-demand capture from the admin UI (Priority: P2)

**Goal**: An administrator can trigger a platform capture immediately before a
risky change, watch it progress, and see the retained archives at a glance.

**Independent Test**: From the Data Operations page, trigger a platform backup,
watch live progress to completion, and confirm the new archive appears in the
listed platform backups with date and size.

**Depends on**: Phase 3 (it triggers the same service). Otherwise independent.

### Tests for User Story 2

- [X] T030 [P] [US2] Write controller tests in `backend/src/test/java/com/simonrowe/dataops/DataOperationsControllerPlatformTest.java`: `POST /platform-backup` returns `202` with the operation, `409` when the mutex is held, `503` when Drive is disconnected; `GET /platform-backups` returns the platform folder's archives; **both reject unauthenticated and non-admin callers** (FR-022)
- [X] T031 [P] [US2] Write `frontend/tests/admin/DataOperationsAdmin.platform.test.tsx` asserting the "Platform Data" card renders, the button calls `startPlatformBackup`, the archive list renders date and size, the button is disabled while an operation is in progress or Drive is disconnected, and a `PLATFORM_BACKUP` progress event renders as "PLATFORM BACKUP"

### Implementation for User Story 2

- [X] T032 [US2] Add `POST /platform-backup` and `GET /platform-backups` to `backend/src/main/java/com/simonrowe/dataops/DataOperationsController.java`, reusing `requireDriveConnected()` and `requireNoOperationInProgress(OperationType.PLATFORM_BACKUP)` and running the capture on a `CompletableFuture`, exactly as the sibling `/backup` endpoint does
- [X] T033 [P] [US2] Add `startPlatformBackup` and `fetchPlatformBackups` to `frontend/src/services/dataOperationsApi.ts`, and widen the `DataOperation.type` union with `PLATFORM_BACKUP` **and** the already-missing `REEMBED_CONTENT` — without the union change, SSE progress events for a platform backup are typed as never occurring (research R10)
- [X] T034 [US2] Add the "Platform Data" card to `frontend/src/pages/admin/DataOperationsAdmin.tsx`: a "Back Up Now" button plus a list of retained archives with date and size, read-only beyond the trigger, its purpose being to make a stalled nightly job obvious at a glance (SC-006)
- [X] T035 [P] [US2] Add any needed BEM classes for the Platform Data card to the single `frontend/src/styles.css`, reusing the existing `data-ops__card` / `admin-table` classes wherever possible rather than inventing parallel ones

**Checkpoint**: User Stories 1 and 2 both work. The nightly job runs unattended and
the operator can trigger and inspect it from the browser.

---

## Phase 5: User Story 3 — Restore one tool onto a live or rebuilt host (Priority: P2)

**Goal**: The operator can restore a chosen tool's data from a chosen archive, one
target at a time, and is stopped before doing something irreversible and wrong.

**Independent Test**: `--list` the archives, `--dry-run` a restore and confirm
nothing changed, then really restore one target and confirm that tool's data is
present while the other tools are untouched and still running.

**Depends on**: Phase 3 (it needs archives to restore). Independent of Phase 4.

### Verification before implementation

- [X] T036 [US3] **Blocking**: determine the working ClickHouse restore-over-existing incantation against the pinned `clickhouse/clickhouse-server:26.7.1.1315` on a local stack, trying `DROP DATABASE IF EXISTS default SYNC` + `RESTORE DATABASE default FROM File(…)` first, then the two documented fallbacks; record the verified commands in `specs/034-platform-datastore-backup/research.md` under R5. Do **not** use `allow_non_empty_tables` — it appends rather than replaces and would silently duplicate every trace row (FR-037)

### Implementation for User Story 3

- [X] T037 [US3] Create `scripts/restore-platform.sh` with `#!/usr/bin/env bash`, `set -euo pipefail`, `SCRIPT_DIR`/`PROJECT_DIR` via `$(cd "$(dirname "$0")" && pwd)`, and argument parsing for `--target <langfuse|dtrack|temporal|all>`, `--latest`, `--file <zip>`, `--list`, `--dry-run`, `--force`, with precondition validation and clear error messages (constitution IX)
- [X] T038 [US3] Implement `--list` and `--latest` in `scripts/restore-platform.sh`: OAuth token exchange and file download against Drive using the `GOOGLE_DRIVE_*` values already in `.env`, via `curl` plus `python3` for JSON parsing, matching the style of `scripts/google-drive-auth.sh`
- [X] T039 [US3] Implement the fingerprint gate in `scripts/restore-platform.sh`: recompute `sha256` over `printf '%s' "platform-backup-fingerprint-v1:$name:$value"` — **`printf '%s'`, never `echo`**, or the trailing newline makes every legitimate restore fail — compare against `manifest.json`, and abort naming the mismatched key unless `--force`. Treat a `null` manifest fingerprint as unverifiable, not as a match (FR-031)
- [X] T040 [US3] Implement the pre-restore safety dump in `scripts/restore-platform.sh`: `pg_dump` whatever is about to be overwritten, written outside the repo on the host, before anything is stopped or dropped (FR-032)
- [X] T041 [US3] Implement the per-target restore body in `scripts/restore-platform.sh`: `docker compose stop` the target's consumers → `pg_terminate_backend` remaining connections → drop, recreate with the correct owner, `psql -f` the dump → create roles from `roles.sql` **only when absent** → for `langfuse` also restore ClickHouse using the T036-verified commands. Never stop `langfuse-db` itself — dropping databases within a running server is what keeps the targets independent (data-model.md §4)
- [X] T042 [US3] Implement consumer restart in `scripts/restore-platform.sh` inside a `trap` so it runs on the failure path too, then poll each restarted service's healthcheck; a failed restore must leave services running against the pre-restore database rather than stopped (FR-035, FR-036, SC-009)
- [X] T043 [US3] Implement `--dry-run` in `scripts/restore-platform.sh` so every command is printed and nothing is executed, following the `monitor-prod.sh` precedent — a script that shells out to `docker compose` performs real restarts merely by being run (FR-030)
- [X] T044 [US3] `shellcheck scripts/restore-platform.sh` clean, then exercise `--list`, `--dry-run` for all four targets, and a **real** `--target dtrack` restore against a local stack

**Checkpoint**: All three user stories are independently functional.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [~] T045 **Blocking for rollout — NOT DONE, needs the production host**: measure the ClickHouse archive size on the production host using the `system.parts` queries in quickstart.md, and record the total plus per-table breakdown in the runbook. Emit a single copy-paste command block for the operator — there is no SSH from this workspace. If it runs to multiple GB, note that 7 platform archives alongside 7 full Mongo-with-media archives may strain the Drive quota and that the answer is a ClickHouse TTL, deliberately out of scope here (NFR-004, SC-012)
- [X] T046 [US3] Perform one **real** `--target langfuse` restore against a local environment to prove the ClickHouse path end to end, and record the outcome in the runbook — this is the only target exercising the part of the design that was an open question rather than a known quantity (SC-007)
- [X] T047 [P] Write `docs/runbooks/platform-backup-restore.md` covering: verifying the nightly ran; restoring one tool onto a live host; cold-starting a rebuilt host (bring up `langfuse-db`, let the `*-db-init` services create the roles, restore, then `up -d` the rest — the ordering matters and is easy to get wrong); the measured archive size from T045; the verified ClickHouse commands from T036; and explicitly that restored traces lose very large payload bodies because those live in MinIO, so it is not discovered mid-incident
- [X] T048 [P] Add a `034-platform-datastore-backup` entry to the `## Recent Changes` section of `CLAUDE.md` recording: the separate Drive folder and why sharing one would halve both retention windows; the `clickhouse-backups-init` chown requirement and its `ONESHOT_SERVICES` registration; that the deploy recreates `langfuse-clickhouse` and `backend` and should share the memory-cgroup reboot window; and that restore is `scripts/restore-platform.sh`, not an in-app operation
- [X] T049 Run the full local gate: `cd backend && ../gradlew test checkstyleMain checkstyleTest jacocoTestCoverageVerification`, `cd frontend && npm test && npm run lint`, and `docker compose -f docker-compose.prod.yml config` — all green before the PR
- [~] T050 **Partially done — the Drive-isolation check needs a real Drive account**. Walk `specs/034-platform-datastore-backup/quickstart.md` end to end, including the **manual Drive folder isolation check** (the two archive lists must be disjoint) — the highest-risk detail in the feature and the one that fails silently

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1 (Setup)** — no dependencies; start immediately.
- **Phase 2 (Foundational)** — depends on Phase 1 (T007's config keys). **Blocks all user stories.**
- **Phase 3 (US1)** — depends on Phase 2.
- **Phase 4 (US2)** — depends on Phase 3 (it triggers the same service).
- **Phase 5 (US3)** — depends on Phase 3 (it needs archives to restore). Independent of Phase 4.
- **Phase 6 (Polish)** — depends on Phases 3 and 5; T045 gates *rollout*, not implementation.

### Within-phase dependencies

- T002 → T003, T004 (the volume must exist before it is mounted)
- T003 → T005 (the mount must exist before the chown service targets it)
- T009 → T010, T011 → T012, T015 → T016, T017 → T018 (test before implementation)
- T013, T014 → T024 (the seam and properties before the service that consumes them)
- T024 → T025, T026, T027 (all edit the same file, so sequential)
- T024 → T028 (the scheduler calls the service)
- T036 → T041 (the verified incantation before the code that uses it)
- T037 → T038…T043 (all edit the same script, so sequential)
- T036, T041, T042 → T046
- Everything → T049, T050

### Story independence

- **US1** is fully standalone and is the MVP.
- **US2** adds a trigger and a view over US1's service; US1 remains complete without it.
- **US3** consumes US1's output but shares no code with it — it is bash against the host.
- US2 and US3 touch disjoint files (frontend + controller vs. `scripts/`) and can be built in parallel by different people once US1 lands.

---

## Parallel Opportunities

```bash
# Phase 1 — independent files
T001  config/clickhouse/backup-disk.xml
T007  backend/src/main/resources/application.yml

# Phase 2 — tests, all different files
T009  SecretFingerprinterTest.java
T011  PlatformManifestTest.java
T015  GoogleDriveFolderResolutionTest.java
T017  BackupRetentionServiceTest.java

# Phase 2 — independent implementations
T008  OperationType.java
T013  CommandRunner.java
T014  PlatformBackupProperties.java

# Phase 3 — all US1 tests before any US1 implementation
T019 T020 T021 T022   PlatformBackupServiceTest.java (same file: write as one pass)
T023                  PlatformBackupSchedulerTest.java

# Phase 4 — backend and frontend are disjoint
T030  DataOperationsControllerPlatformTest.java
T031  DataOperationsAdmin.platform.test.tsx

# Phase 6 — documentation
T047  docs/runbooks/platform-backup-restore.md
T048  CLAUDE.md
```

---

## Implementation Strategy

### MVP first (User Story 1 only)

1. Phase 1 — Setup (compose, config, the ClickHouse backup path).
2. Phase 2 — Foundational (**critical**: blocks everything).
3. Phase 3 — US1.
4. **Stop and validate**: a capture produces a complete archive in its own folder, prunes to 7, and the Mongo folder is untouched.
5. This alone is deployable and is the whole protective value of the feature.

### Incremental delivery

1. Setup + Foundational → foundation ready.
2. US1 → validate → **deployable MVP**: platform data is off-host nightly.
3. US2 → validate → the operator gains on-demand control and visibility.
4. US3 → validate → the archives become restorable, not just stored.
5. Polish → the two measurement/verification gates and the runbook.

Note the ordering rationale: US3 is genuinely essential — a backup nobody can
restore is not a backup — but it ranks after US1 because data captured today can be
restored by a script written tomorrow, whereas data never captured is simply gone.

---

## Notes

- `[P]` = different files, no dependencies on incomplete tasks.
- Every new backend test uses mocks and the `CommandRunner` fake — no Docker, no Testcontainers. That is why the seam exists.
- T036 and T045 are **verification** tasks with no code output. Neither may be assumed away: T036 is the design's own open question about the ClickHouse restore incantation, and T045 is the unmeasured, unbounded archive size that gates rollout.
- Never use `allow_non_empty_tables` on the ClickHouse restore: it appends rather than replaces, and duplicating trace data is worse than a loud failure.
- Never use `echo` to compute a fingerprint in bash: the trailing newline makes every legitimate restore fail.
- Commit after each task or logical group; stop at any checkpoint to validate a story independently.
