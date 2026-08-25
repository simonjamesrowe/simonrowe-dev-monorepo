# Implementation Plan: Platform Datastore Backup

**Branch**: `034-platform-datastore-backup` (worked on `simonrowe/postgres-redis-backups`) | **Date**: 2026-08-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/034-platform-datastore-backup/spec.md`

**Design source**: `docs/superpowers/specs/2026-08-25-platform-datastore-backup-design.md` (approved; ADRs 1–6 are settled and not re-litigated here)

## Summary

Nightly and on-demand capture of the five platform datastores nothing currently
backs up — the `langfuse`, `dtrack`, `temporal` and `temporal_visibility`
Postgres databases plus the ClickHouse `default` database — into one dated zip,
uploaded to a **separate** Drive folder with its own retention window of 7. Plus a
host-side restore script that targets one tool at a time and refuses to run
against mismatched secrets.

The capture runs inside the backend (ADR 1): it already runs as `user: "0:0"` with
the docker socket and CLI bind-mounted, so `docker exec langfuse-db pg_dump` needs
no new host prerequisite, and it reuses `GoogleDriveService`'s tuned resumable
upload, `DataOperationsService`'s SSE stream and mutex, and
`BackupRetentionService`'s prune. Restore is bash (ADR 2), because the scenario
that motivates it is a rebuilt host where the backend is the thing being rebuilt.

Two details that reading the code surfaced govern the shape of the work.
First, `GoogleDriveService.findOrCreateFolder()` short-circuits on a configured
folder id, so a naive call from the platform path would land platform backups in
the Mongo folder and make the two retention windows evict each other — the exact
failure ADR 5 exists to prevent, failing silently (research R1). Second, the
ClickHouse backup volume needs a chown init service, and any new one-shot service
must be registered in `monitor-prod.sh`'s `ONESHOT_SERVICES` or the watchdog
reconciles the whole stack every minute forever (research R4).

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.x / React 19 (frontend), bash (restore script)

**Primary Dependencies**: Spring Boot 3.5.x (`@Scheduled`, `@RestController`), Google Drive API client (existing `GoogleDriveService`), `java.util.zip`, `java.lang.ProcessBuilder`. **No new dependencies in any module.**

**Storage**: No application persistence. Reads Postgres 15 (`langfuse-db`) and ClickHouse (`langfuse-clickhouse`) through `docker exec`; writes a zip to Google Drive. One new Docker named volume (`langfuse-clickhouse-backups`) as the ClickHouse↔backend handoff.

**Testing**: JUnit 5 + Mockito + AssertJ (backend, mirroring `BackupSchedulerTest`/`BackupRetentionServiceTest`), Vitest + Testing Library (frontend), `--dry-run` plus one real local restore for the script.

**Target Platform**: Linux ARM64 (Raspberry Pi) under Docker Compose; backend is a GraalVM native image built by `bootBuildImage`.

**Project Type**: Web application — Spring Boot backend + React frontend in a monorepo, plus root-level `scripts/` and `config/`.

**Performance Goals**: Not latency-sensitive. The capture must stream rather than buffer, because dump sizes are unbounded and the backend container is capped at 2 GB. Upload throughput must match the existing Mongo backup (~1 MB/s on this uplink) by reusing the tuned Apache transport rather than a fresh HTTP client.

**Constraints**:
- No new host prerequisite, no new secret, no new dependency (spec NFR-001/002).
- Must not alter the Mongo backup's schedule, folder or retention (FR-014).
- GraalVM native image: no new reflection; `ProcessBuilder` is already proven by `RedeployService`.
- Checkstyle (Google Java Style) and the backend's 0.78 JaCoCo floor both apply.
- ClickHouse image is pinned at `26.7.1.1315` and must not move.

**Scale/Scope**: ~5 new backend classes, 2 new endpoints, 1 frontend card, 1 bash script (~400 lines), 1 runbook, compose + config changes. ClickHouse archive size is **unmeasured and unbounded** — measuring it is a blocking pre-rollout task (spec NFR-004).

## Constitution Check

*GATE: evaluated against `.specify/memory/constitution.md` v1.11.0.*

| Principle | Verdict | Notes |
|---|---|---|
| I — Monorepo, separate containers | **PASS** | No new container. Adds one named volume, one config overlay, and one busybox one-shot (same pattern as the existing `uploads-init`). |
| II — Modern Java & React stack | **PASS** | Java 21, Spring Boot 3.5.x, no new dependency. Native-image safe: `ProcessBuilder` only, no reflection. |
| VIII — Backup & Restore | **PASS** | Extends the principle's own model: Drive via `GoogleDriveService` with OAuth2 from `GOOGLE_DRIVE_*`, triggered from `DataOperationsController`, zip archives in a configurable folder. The existing `backup.sh`/`restore.sh` pair is untouched, as the principle requires. |
| IX — Shell scripting standards | **PASS** | `restore-platform.sh` uses `#!/usr/bin/env bash`, `set -euo pipefail`, `SCRIPT_DIR`/`PROJECT_DIR` via `$(cd "$(dirname "$0")" && pwd)`, validates preconditions with clear errors, cleans up temp files, and interacts with containers via `docker exec`/`docker cp`. |
| Testing (Testcontainers / JaCoCo) | **PASS** | New logic is unit-testable with mocks; the process-spawning boundary is behind an injectable seam so no Testcontainers Postgres is needed. Coverage floor respected. |

**Deviations requiring justification**: none. No entry in Complexity Tracking.

One item deserves flagging rather than justifying: principle VIII describes backup
as "simple shell scripts", and this puts the *capture* in Java. That is ADR 1 in
the approved design, and the principle's own second half already mandates that
Drive backup be driven from `DataOperationsController` via `GoogleDriveService` —
i.e. the principle already describes an application-side Drive backup path. This
extends that path; it does not contradict the principle. The *restore* half stays
in bash, exactly as the principle's spirit prefers.

## Project Structure

### Documentation (this feature)

```text
specs/034-platform-datastore-backup/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 — R1..R10, resolves both design open questions
├── data-model.md        # Phase 1 — manifest schema, archive layout, restore targets
├── quickstart.md        # Phase 1 — how to exercise this locally
├── contracts/
│   └── data-operations-platform.yaml   # OpenAPI for the two new endpoints
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 — /speckit.tasks output
```

### Source code

```text
backend/src/main/java/com/simonrowe/dataops/
├── PlatformBackupService.java        # NEW — builds the archive, uploads it
├── PlatformBackupScheduler.java      # NEW — nightly @Scheduled, mirrors BackupScheduler
├── PlatformBackupProperties.java     # NEW — container names, db list, folder, paths
├── PlatformManifest.java             # NEW — manifest record + JSON serialisation
├── SecretFingerprinter.java          # NEW — domain-separated SHA-256 (research R7)
├── CommandRunner.java                # NEW — injectable ProcessBuilder seam (testability)
├── GoogleDriveService.java           # EDIT — findOrCreateFolderByName + platform folder
├── BackupRetentionService.java       # EDIT — pruneToLimit(folderId, max) generalisation
├── OperationType.java                # EDIT — add PLATFORM_BACKUP
└── DataOperationsController.java     # EDIT — two new endpoints

backend/src/test/java/com/simonrowe/dataops/
├── PlatformBackupServiceTest.java        # NEW
├── PlatformBackupSchedulerTest.java      # NEW
├── SecretFingerprinterTest.java          # NEW
├── PlatformManifestTest.java             # NEW
├── GoogleDriveFolderResolutionTest.java  # NEW — the R1 regression guard
├── BackupRetentionServiceTest.java       # EDIT — folder param, existing asserts kept
└── DataOperationsControllerTest.java     # NEW/EDIT — the two endpoints + admin auth

backend/src/main/resources/application.yml    # EDIT — backup.platform.* block

frontend/src/services/dataOperationsApi.ts    # EDIT — 2 calls, PLATFORM_BACKUP in union
frontend/src/pages/admin/DataOperationsAdmin.tsx  # EDIT — "Platform Data" card
frontend/tests/admin/DataOperationsAdmin.platform.test.tsx  # NEW

scripts/restore-platform.sh                   # NEW — the restore path
scripts/monitor-prod.sh                       # EDIT — ONESHOT_SERVICES (research R4)

config/clickhouse/backup-disk.xml             # NEW — <backups><allowed_path>
docker-compose.prod.yml                       # EDIT — volume, mounts, init service
docs/runbooks/platform-backup-restore.md      # NEW
CLAUDE.md                                     # EDIT — Recent Changes entry
```

**Structure Decision**: Everything backend-side lands in the existing
`com.simonrowe.dataops` package alongside `BackupService`, `BackupScheduler` and
`RedeployService`. That is where the operation mutex, the Drive client, the SSE
stream and the `docker` process-spawning precedent all already live; a new package
would separate the new code from every collaborator it has. The restore script
sits in `scripts/` with the other operational bash. The ClickHouse config overlay
follows `config/searxng/` and `config/temporal/`.

## Design detail

### Archive layout

```text
platform-backup-YYYYMMDD-HHMMSS.zip
├── manifest.json
├── postgres/roles.sql
├── postgres/langfuse.sql
├── postgres/dtrack.sql
├── postgres/temporal.sql
├── postgres/temporal_visibility.sql
└── clickhouse/default.zip
```

Schema and field semantics: [data-model.md](./data-model.md).

### `PlatformBackupService` sequence

Ordered so that the cheapest failures happen first and nothing is uploaded until
everything is captured.

1. **Sweep orphans** from the ClickHouse backup volume — residue from crashed
   prior runs. Skipping this lets a few failed nights fill the SD card silently
   (FR-008).
2. `pg_dumpall --roles-only` → `postgres/roles.sql`.
3. `pg_dump -d <db>` for each of the four databases, **streamed** into the zip
   entry, counting bytes (research R8).
4. `BACKUP DATABASE default TO File('<name>.zip')` via `clickhouse-client`, then
   copy the result off the shared volume into `clickhouse/default.zip`, and query
   `system.tables`/`system.parts` for per-table row counts.
5. Write `manifest.json` last — it records byte counts and row counts only now
   known.
6. Upload to `simonrowe-platform-backups` via `GoogleDriveService`, reporting
   progress on the same SSE stream the Mongo backup uses.
7. `finally`: delete the local zip **and** the ClickHouse volume file.

Returns `boolean`, matching `BackupService.performBackup()`: `true` after
`completeOperation`, `false` after `failOperation`. Exceptions are caught and
converted, never propagated (the controller runs it on a `CompletableFuture` where
a thrown exception would vanish and leave the mutex held).

### Testability seam

Every `docker`/`clickhouse-client` invocation goes through `CommandRunner`, a
thin interface over `ProcessBuilder` exposing "run and stream stdout to an
`OutputStream`" and "run and capture stdout as a string". `PlatformBackupService`
depends on the interface, so `PlatformBackupServiceTest` can assert archive
entries, failure handling, manifest content and temp-file cleanup with a fake —
no Docker, no Postgres, no Testcontainers. Without this seam the service is only
testable in an environment that has the whole prod stack running, which in
practice means untested.

### `PlatformBackupScheduler`

```java
@Scheduled(
    cron = "${backup.platform.schedule.cron:0 0 2 * * *}",
    zone = "${backup.platform.schedule.zone:Europe/London}")
```

Same five-step shape as `BackupScheduler`: skip if Drive is disconnected, skip if
the mutex is held, run, prune **only** on success, report a prune failure
distinctly from a backup failure, and wrap everything so nothing reaches the
scheduler thread. 02:00 is four hours clear of the 22:00 Mongo backup because the
mutex is global and an overlap costs the platform backup its whole night
(research R9).

### `BackupRetentionService` generalisation

`pruneToLimit()` → `pruneToLimit(String folderId, int maxBackups)`, with
`pruneToLimit()` retained as the Mongo-path overload resolving the existing folder
and `backup.retention.max-backups`. Today's behaviour is preserved exactly and the
existing tests stay as they are, which is the point: FR-014 and SC-004 make "the
Mongo backup is unchanged" a verifiable requirement, not a hope.

### Endpoints

| Method | Path | Behaviour |
|---|---|---|
| `POST` | `/api/admin/data-operations/platform-backup` | `202` + `DataOperation`; `409` if the mutex is held; `503` if Drive is down |
| `GET` | `/api/admin/data-operations/platform-backups` | `200` + `BackupMetadata[]` from the platform folder |

Both admin-authenticated exactly like their siblings. No restore endpoint (ADR 2).
Contract: [contracts/data-operations-platform.yaml](./contracts/data-operations-platform.yaml).

### Frontend

A "Platform Data" card in `DataOperationsAdmin.tsx` with a "Back Up Now" button
and a list of retained archives (date + size). Read-only beyond the trigger — its
job is to make a stalled nightly job obvious at a glance (SC-006). Requires
`PLATFORM_BACKUP` in the `DataOperation.type` union or the SSE events are typed as
impossible (research R10).

### Compose and config

- `config/clickhouse/backup-disk.xml` — `<backups><allowed_path>/backups/</allowed_path></backups>`, mounted read-only into `/etc/clickhouse-server/config.d/`.
- `langfuse-clickhouse-backups` named volume → `/backups` in `langfuse-clickhouse`, `/clickhouse-backups` in `backend`.
- `clickhouse-backups-init` busybox one-shot chowning the volume to `101:101`, because a volume at a path absent from the image is created root-owned and ClickHouse runs as `101:101` (research R4).
- `clickhouse-backups-init` added to `monitor-prod.sh`'s `ONESHOT_SERVICES`.

### Config additions (`application.yml`)

```yaml
backup:
  schedule:      { cron: "0 0 22 * * *", zone: "Europe/London" }   # unchanged
  retention:     { max-backups: 7 }                                # unchanged
  platform:
    schedule:    { cron: "0 0 2 * * *", zone: "Europe/London" }
    retention:   { max-backups: 7 }
    postgres-container: langfuse-db
    clickhouse-container: langfuse-clickhouse
    databases: [langfuse, dtrack, temporal, temporal_visibility]
    clickhouse-backup-path: /clickhouse-backups

google:
  drive:
    platform-folder-id: ${GOOGLE_DRIVE_PLATFORM_FOLDER_ID:}
```

### Restore script

`scripts/restore-platform.sh`, flags `--target <langfuse|dtrack|temporal|all>`,
`--latest`, `--file <zip>`, `--list`, `--dry-run`, `--force`.

| Target | Restores | Stops first |
|---|---|---|
| `langfuse` | `langfuse` PG DB + ClickHouse `default` | `langfuse`, `langfuse-worker` |
| `dtrack` | `dtrack` PG DB | `dependencytrack-apiserver` |
| `temporal` | `temporal` + `temporal_visibility` | `temporal`, `temporal-ui`, `software-factory` |

Per target: verify secret fingerprints → local pre-restore `pg_dump` → stop
consumers → `pg_terminate_backend` stragglers → drop, recreate, `psql -f` → for
`langfuse` also restore ClickHouse → restart consumers and poll health. Targets are
independent by construction, and consumers are restarted **in a trap, even on
failure**, so a failed restore leaves services running against the pre-restore
database rather than stopped (FR-035).

`--dry-run` prints every command and touches nothing, following the
`monitor-prod.sh` precedent for the same reason: a script that shells out to
`docker compose` performs real restarts merely by being run.

## Risks

| Risk | Mitigation |
|---|---|
| Platform backups land in the Mongo folder and the two evict each other, silently | Dedicated name-based resolver; a test asserts the two resolvers diverge when `google.drive.folder-id` is set (research R1) |
| ClickHouse `BACKUP` fails on volume permissions | `clickhouse-backups-init` chown, plus a first-run verification task |
| New one-shot service makes the watchdog reconcile the stack every minute | `clickhouse-backups-init` added to `ONESHOT_SERVICES` in the same change (research R4) |
| ClickHouse restore incantation differs on the pinned build | Blocking verification task against `26.7.1.1315` before the runbook is written; two documented fallbacks (research R5) |
| Fingerprint check refuses every legitimate restore over a trailing newline | `printf '%s'` in bash, explicit cross-language test vector (research R7) |
| Archive too large for the Drive quota at 7 copies | Measured on the host before rollout; the answer is a ClickHouse TTL, scoped out (research R6) |
| A truncated dump is archived and reported as success | Exit code checked after stdout reaches EOF, not before; stderr drained on a separate thread (research R8) |
| Deploy recreates `langfuse-clickhouse` and `backend` | Combine with the pending memory-cgroup reboot, which recreates ~17 containers anyway |

## Complexity Tracking

No constitution violations. Table intentionally empty.
