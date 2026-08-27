# Implementation Plan: Platform Datastore Backup

**Branch**: `034-platform-datastore-backup` (worked on `simonrowe/postgres-redis-backups`) | **Date**: 2026-08-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/034-platform-datastore-backup/spec.md`

**Design source**: `docs/superpowers/specs/2026-08-25-platform-datastore-backup-design.md` (approved; ADRs 2–6 stand. **ADR 1 is superseded** — see below)

## Revision 2 — 2026-08-26

> **ADR 1 no longer holds.** `main` merged `036-auto-deploy-on-merge` (#116), which
> deleted `RedeployService`, stripped the Docker socket, Docker CLI, Compose plugin,
> compose file and `.env` from the `backend` container, raised the constitution to
> **2.0.0** prohibiting `ProcessBuilder` anywhere in `backend/src/main/java`, and added
> `NoHostProcessLaunchTest` to enforce it. ADR 1's premise — that the backend already
> held the socket because `RedeployService` needed it — was deliberately removed.
>
> **The capture moves to the `deployer` container, script-first, on a Temporal
> schedule.** Reasoning and rejected alternatives: research **R11**, **R12**, **R13**.
>
> Unchanged by this revision: the archive format, the manifest, the fingerprint scheme,
> the Drive folder isolation, the ClickHouse `BACKUP`/`RESTORE` findings,
> `scripts/restore-platform.sh`, the runbook, and the compose volume + init service +
> watchdog registration. Everything verified in R4/R5 still stands — only *which
> process runs the commands* changes.

## Summary

Nightly and on-demand capture of the five platform datastores nothing currently
backs up — the `langfuse`, `dtrack`, `temporal` and `temporal_visibility`
Postgres databases plus the ClickHouse `default` database — into one dated zip,
uploaded to a **separate** Drive folder with its own retention window of 7. Plus a
host-side restore script that targets one tool at a time and refuses to run
against mismatched secrets.

The capture runs in the **`deployer`** container as `scripts/backup-platform.sh`,
invoked by a Temporal activity and scheduled nightly. That follows `main`'s own
pattern rather than inventing one: `PhaseRunner`'s javadoc states *"this is the whole
of the Java side's relationship with Docker: it never invokes `docker` itself. The
script is the single deploy mechanism, shared with the human who types
`./scripts/restart-prod.sh` by hand."* The capture script is the symmetric sibling of
`scripts/restore-platform.sh`, which is already written and verified end to end.

Restore is bash (ADR 2) and is **unaffected** — it always ran on the host, so the
constitution change does not touch it.

Two details that reading the code surfaced still govern the shape of the work.
First, `GoogleDriveService.findOrCreateFolder()` short-circuits on a configured
folder id, so a naive call from the platform path would land platform backups in
the Mongo folder and make the two retention windows evict each other — the exact
failure ADR 5 exists to prevent, failing silently (research R1). Second, the
ClickHouse backup volume needs a chown init service, and any new one-shot service
must be registered in `monitor-prod.sh`'s `ONESHOT_SERVICES` or the watchdog
reconciles the whole stack every minute forever (research R4).

## Technical Context

**Language/Version**: **bash** (capture *and* restore), Java 21 (`software-factory` Temporal glue; `backend` listing only), TypeScript 5.x / React 19 (frontend)

**Primary Dependencies**: Temporal (`temporal-spring-boot-starter`, already in `software-factory`), `curl` + `python3` in the script, the existing `GoogleDriveService` for the read-only listing. **No new dependency in any module.**

**Storage**: No application persistence. The script reads Postgres 15 (`langfuse-db`) and ClickHouse (`langfuse-clickhouse`) through `docker exec` and uploads a zip to Google Drive. One new Docker named volume (`langfuse-clickhouse-backups`) as the ClickHouse→script handoff.

**Testing**: `shellcheck` + `--dry-run` + a real capture and restore against a throwaway stack (the method that found three real bugs in R5); JUnit 5 + Mockito for the Temporal activity and schedule initializer, mirroring `PhaseRunnerTest` and `CveFixScheduleInitializerTest`; Vitest for the reduced frontend card.

**Target Platform**: Linux ARM64 (Raspberry Pi) under Docker Compose; backend is a GraalVM native image built by `bootBuildImage`.

**Project Type**: Web application — Spring Boot backend + React frontend in a monorepo, plus root-level `scripts/` and `config/`.

**Performance Goals**: Not latency-sensitive. The capture streams each dump straight to disk rather than buffering, because dump sizes are unbounded. Upload must be **resumable** — restarting a multi-GB upload on a residential uplink is expensive — and the resumable path must be measured, not assumed (research R12).

**Constraints**:
- **Constitution 2.0.0**: no `ProcessBuilder` in `backend/src/main/java`, and `backend` holds no Docker socket, CLI, Compose plugin, compose file or `.env`. `NoHostProcessLaunchTest` enforces it.
- No new host prerequisite, no new secret, no new dependency (spec NFR-001/002).
- Must not alter the Mongo backup's schedule, folder or retention (FR-014).
- Checkstyle (Google Java Style) applies to the `software-factory` module too (`:software-factory:check`).
- ClickHouse image is pinned at `26.7.1.1315` and must not move.
- The deployer's `FACTORY_DEPLOY_RECREATABLE` allowlist deliberately excludes `langfuse-clickhouse`. Not a conflict — the capture `docker exec`s into it, never recreates it — but worth stating so nobody widens the allowlist on this feature's account.

**Scale/Scope**: 1 bash script (~350 lines), ~3 small `software-factory` classes (activity, workflow, schedule initializer), 1 read-only backend endpoint retained, 1 reduced frontend card, 1 runbook, compose + config changes. ClickHouse archive size is **unmeasured and unbounded** — measuring it is a blocking pre-rollout task (spec NFR-004).

## Constitution Check

*GATE: re-evaluated against `.specify/memory/constitution.md` **v2.0.0**.*

| Principle | Verdict | Notes |
|---|---|---|
| I — Monorepo, separate containers | **PASS** | No new container: the capture runs in the existing `deployer`. Adds one named volume, one config overlay, and one busybox one-shot (the `uploads-init` pattern). |
| II — Modern Java & React stack, **no host process in the backend** | **PASS** — and this revision is what makes it pass | Revision 1 would have failed: `ProcessCommandRunner` used `ProcessBuilder` in `backend/src/main/java`. All of it is deleted. The backend keeps only a Drive listing, which is a network call. |
| VIII — Backup & Restore | **PASS, more squarely than before** | The principle says backup should be "simple shell scripts". Revision 1 argued around that by putting the capture in Java; revision 2 simply complies. Drive still goes through OAuth2 `GOOGLE_DRIVE_*` credentials, and `backup.sh`/`restore.sh` remain untouched. |
| IX — Shell scripting standards | **PASS** | Both scripts use `#!/usr/bin/env bash`, `set -euo pipefail`, `SCRIPT_DIR`/`PROJECT_DIR` via `$(cd "$(dirname "$0")" && pwd)`, validate preconditions with clear errors, clean up temp files, and reach containers via `docker exec`/`docker cp`. |
| Deploy orchestration (new in 2.0.0) | **PASS** | Host-level container access stays in the `deployer`, which has no ingress. The trigger is a durable Temporal workflow, not an HTTP call. |
| Testing | **PASS** | The activity and schedule initializer are unit-testable with mocks (`PhaseRunnerTest`, `CveFixScheduleInitializerTest` are the models). The script is covered by `shellcheck`, `--dry-run`, and a real end-to-end run. |

**Deviations requiring justification**: none. No entry in Complexity Tracking.

Worth recording: revision 1 needed a paragraph explaining why putting the capture in
Java did not really contradict Principle VIII. Revision 2 does not need that
paragraph, which is a reasonable signal the new shape fits the repo better rather
than merely satisfying it.

## Project Structure

### Documentation (this feature)

```text
specs/034-platform-datastore-backup/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 — R1..R10, plus R11-R13 (the revision-2 decisions)
├── data-model.md        # Phase 1 — manifest schema, archive layout, restore targets
├── quickstart.md        # Phase 1 — how to exercise this locally
├── contracts/
│   └── data-operations-platform.yaml   # OpenAPI — POST now marked deferred
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 — /speckit.tasks output
```

### Source code

```text
scripts/
├── backup-platform.sh                # NEW — capture + manifest + Drive upload + retention.
│                                     #       The whole Docker-touching half of the feature.
├── restore-platform.sh               # UNCHANGED — already written and verified
└── monitor-prod.sh                   # EDIT — ONESHOT_SERVICES (research R4)

software-factory/src/main/java/com/simonrowe/factory/platformbackup/
├── PlatformBackupActivities.java        # NEW — activity interface
├── PlatformBackupActivitiesImpl.java    # NEW — invokes backup-platform.sh via the
│                                        #       existing ProcessRunner, heartbeating
├── PlatformBackupWorkflow.java          # NEW — one activity, a retry policy
├── PlatformBackupWorkflowImpl.java      # NEW
├── PlatformBackupScheduleInitializer.java  # NEW — nightly 02:00 Temporal schedule,
│                                        #       modelled on CveFixScheduleInitializer
└── config/PlatformBackupProperties.java # NEW — script path, enabled flag, task queue

software-factory/src/test/java/com/simonrowe/factory/platformbackup/
├── PlatformBackupActivitiesImplTest.java   # NEW — mirrors PhaseRunnerTest
├── PlatformBackupWorkflowTest.java         # NEW — Temporal test framework
└── PlatformBackupScheduleInitializerTest.java  # NEW

backend/src/main/java/com/simonrowe/dataops/
├── GoogleDriveService.java           # EDIT — findOrCreateFolderByName + platform folder
├── DataOperationsController.java     # EDIT — GET /platform-backups ONLY
└── OperationType.java                # UNCHANGED — PLATFORM_BACKUP no longer needed
                                      #   (nothing in the backend runs the operation)

backend/src/test/java/com/simonrowe/dataops/
└── GoogleDriveFolderResolutionTest.java  # KEEP — the R1 regression guard, unaffected

frontend/src/services/dataOperationsApi.ts    # EDIT — fetchPlatformBackups only
frontend/src/pages/admin/DataOperationsAdmin.tsx  # EDIT — read-only "Platform Data" card
frontend/tests/admin/DataOperationsAdmin.platform.test.tsx  # EDIT — drop trigger tests

config/clickhouse/backup-disk.xml             # UNCHANGED — already written
docker-compose.prod.yml                       # EDIT — volume + clickhouse mounts +
                                              #   clickhouse-backups-init + deployer env
docs/runbooks/platform-backup-restore.md      # EDIT — capture section rewritten
CLAUDE.md                                     # EDIT — Recent Changes entry
```

**Deleted from revision 1** (all of it violated constitution 2.0.0 or became dead):
`PlatformBackupService`, `PlatformBackupScheduler`, `PlatformBackupProperties`,
`PlatformManifest`, `SecretFingerprinter`, `CommandRunner`, `ProcessCommandRunner`,
and their five test classes; the `POST /platform-backup` endpoint; the
`langfuse-clickhouse-backups` mount on `backend`; and `BackupRetentionService`'s
platform overloads (the script owns retention now). The manifest format and the
fingerprint scheme survive as *specifications* — data-model.md §2 and §3 — and are
reimplemented in bash.

**Structure Decision**: the Docker-touching work lives in `scripts/`, and Java only
orchestrates. That is `main`'s established pattern (`PhaseRunner` → `restart-prod.sh`)
rather than a new one, and it means the capture and the restore are siblings in the
same language, reviewed the same way and runnable by hand the same way. The Temporal
glue goes in `software-factory` because that is the module the `deployer` image is
built from and the only one with a Temporal dependency. The backend keeps exactly the
part that needs no host access: reading a Drive folder listing.

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

### `scripts/backup-platform.sh`

Same sequence as revision 1, same ordering rationale — cheapest failures first, and
nothing uploaded until everything is captured. Only the language and the host change.

1. **Sweep orphans** from the ClickHouse backup volume. Residue from crashed prior
   runs; skipping it lets a few failed nights quietly fill the SD card (FR-008).
2. `docker exec -e PGPASSWORD langfuse-db pg_dumpall --roles-only` → `postgres/roles.sql`.
3. `docker exec -e PGPASSWORD langfuse-db pg_dump -d <db>` per database, streamed to
   disk, counting bytes for the manifest.
4. `BACKUP DATABASE default TO File(...)` via `clickhouse-client`, then move the result
   off the shared volume, plus the `system.parts` row counts (excluding `.inner%`,
   research R5).
5. Write `manifest.json` **last** — it records sizes and counts only known by then.
6. `zip` the tree, upload to `simonrowe-platform-backups`, prune to 7.
7. `trap`-based cleanup of the local archive **and** the volume file, on both paths.

Flags mirror `restore-platform.sh` so the pair reads as one tool: `--dry-run`,
`--keep-local`, `--no-upload`. `--dry-run` is not decoration — the script `docker
exec`s into live datastores, so there must be a way to read what it would do.

Credential and `.env` access is identical to the restore script: read off disk from
`$PROJECT_DIR/.env` with a parser, never sourced. Inside the deployer that resolves to
`/workspace/repo/.env`, which the whole-deploy-directory mount provides (research R11).

### Secret fingerprinting moves to bash — and gets simpler

Revision 1 needed `SecretFingerprinter` in Java plus a known-answer test, because the
manifest was *written* in Java and *verified* in bash, and a trailing newline on either
side would have refused every legitimate restore (research R7).

Both sides are now bash, computing
`sha256("platform-backup-fingerprint-v1:" + name + ":" + value)` with the same
`printf '%s'` helper. The cross-language contract disappears, and with it that entire
failure mode. The scheme itself is unchanged, so **archives are format-compatible
across the revision** — data-model.md §3 remains the specification.

### Temporal glue in `software-factory`

Deliberately thin, modelled on `PhaseRunner` / `DeployActivitiesImpl`:

- **`PlatformBackupActivitiesImpl`** — runs the script through the existing
  `ProcessRunner`, forwarding output to `Activity.getExecutionContext().heartbeat()` so
  a long capture does not trip the heartbeat timeout. This is the *only* Java that
  touches the script.
- **`PlatformBackupWorkflow`** — one activity, a retry policy, a
  `startToCloseTimeout` sized well past the measured capture (see the open item in
  Risks). Retry is what replaces the library-level upload resumability revision 1 got
  from `GoogleDriveService`.
- **`PlatformBackupScheduleInitializer`** — declares a 02:00 Europe/London schedule in
  code so a deploy reconciles it, copying `CveFixScheduleInitializer` including its
  **paused-by-default** posture behind `FACTORY_PLATFORM_BACKUP_ENABLED`.

Gated off by default for the same reason the deploy and cve-fix flows are: a container
can be `healthy` with no poller registered, so enabling is a deliberate step with a
poller assertion after it.

### What the backend keeps

`GET /api/admin/data-operations/platform-backups` and
`GoogleDriveService.findOrCreatePlatformFolder()`. Both are network calls to Drive, so
constitution 2.0.0 does not touch them, and `GoogleDriveFolderResolutionTest` — the R1
regression guard against the two backup types evicting each other — stays exactly as
written.

`POST /platform-backup`, `OperationType.PLATFORM_BACKUP`, the SSE progress and the
operation mutex all go. The mutex mattered when both backups shared
`DataOperationsService`; now the platform capture runs in a different container
entirely, and Temporal's own de-duplication (one workflow id per schedule) does that
job. **Note the consequence**: the two backups can now overlap. That is fine and
arguably better — the 22:00/02:00 gap was chosen to dodge a shared mutex that no
longer exists — but the reason for the gap is now different, so it is recorded here
rather than silently inherited.

### Frontend

The "Platform Data" card stays, minus its button: date-and-size list of retained
archives, loaded on mount. That is what satisfies SC-006 — a stalled nightly is
visible at a glance — and it needs no trigger to do it. The card gains one line
pointing at `scripts/backup-platform.sh` for on-demand capture, mirroring how it
already points at `restore-platform.sh`.

### Compose and config

Unchanged from revision 1 except for the mounts:

- `config/clickhouse/backup-disk.xml` — already written, unchanged.
- `langfuse-clickhouse-backups` volume → `/backups` in `langfuse-clickhouse`, and now
  **`/backups` in `deployer`** instead of `/clickhouse-backups` in `backend`.
- `clickhouse-backups-init` busybox one-shot chowning it to `101:101` — unchanged, and
  still required (R4 proved it empirically).
- `clickhouse-backups-init` in `monitor-prod.sh`'s `ONESHOT_SERVICES` — unchanged.
- New deployer env: `FACTORY_PLATFORM_BACKUP_ENABLED`,
  `FACTORY_PLATFORM_BACKUP_SCRIPT: /workspace/repo/scripts/backup-platform.sh`,
  following the existing `FACTORY_DEPLOY_SCRIPT` convention.

### Config additions

`backup.platform.*` moves out of `application.yml` — the backend no longer runs the
capture — and becomes script defaults overridable by environment, matching
`restore-platform.sh`. `google.drive.platform-folder-id` **stays** in
`application.yml`, because the backend still resolves that folder to list it.


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

Carried forward from revision 1, plus the ones this revision introduces.

| Risk | Mitigation | Status |
|---|---|---|
| Platform backups land in the Mongo folder and the two evict each other, silently | Dedicated name-based resolver; `GoogleDriveFolderResolutionTest` asserts the two resolvers diverge when `google.drive.folder-id` is set | Unchanged, already built and tested |
| ClickHouse `BACKUP` fails on volume permissions | `clickhouse-backups-init` chown | Proven empirically in R4 |
| New one-shot makes the watchdog reconcile the stack every minute | Registered in `ONESHOT_SERVICES` | Unchanged, already done |
| ClickHouse restore incantation differs on the pinned build | `DROP … SYNC` + `RESTORE`, verified against `26.7.1.1315` | Closed in R5 |
| Fingerprint check refuses every legitimate restore | Both sides bash now, one shared helper | **Risk reduced** — the cross-language contract is gone |
| Archive too large for the Drive quota at 7 copies | Measure on the host before rollout | **Still open** (NFR-004, task T045) |
| A truncated dump is archived and reported as success | `set -euo pipefail`, explicit exit-code checks after each `docker exec`, non-empty assertions before zipping | Re-implemented in bash; needs re-proving |
| **Resumable Drive upload in bash is unproven** | Implement Google's session-URI + ranged-PUT protocol; **measure against a real multi-GB archive** before trusting it | **NEW, open** (research R12) |
| **A `healthy` deployer with no registered poller means nothing ever backs up, silently** | Paused-by-default flag; assert a live poller on the task queue as an explicit rollout step, as the deploy and cve-fix runbooks already require | **NEW** — this exact failure mode is documented twice already in this repo |
| **The capture and the Mongo backup can now overlap** | Accepted: they touch different datastores and different Drive folders. The 22:00/02:00 gap is kept, but for I/O contention on a 4-core Pi rather than for a mutex | **NEW, accepted** |
| Deploy recreates `langfuse-clickhouse` and `deployer` | Both gain mounts; combine with the pending memory-cgroup reboot | Unchanged |

## Complexity Tracking

No constitution violations. Table intentionally empty.
