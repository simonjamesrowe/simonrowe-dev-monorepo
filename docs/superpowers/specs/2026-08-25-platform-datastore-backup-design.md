# Platform datastore backup (Postgres + ClickHouse)

**Date:** 2026-08-25
**Status:** Approved — ready for implementation plan

## Problem

The nightly backup covers the application's own data only: the MongoDB
`simonrowe` database, the `backend-uploads` media volume, and the Elasticsearch
content embeddings. Everything the platform has grown around it is unprotected.

Since that backup was designed, prod has acquired Langfuse, Dependency-Track and
Temporal. Their data lives in two stores that nothing backs up:

- **`langfuse-db`** (Postgres 15) hosts *four* databases — `langfuse`, `dtrack`,
  `temporal` and `temporal_visibility`.
- **`langfuse-clickhouse`** holds Langfuse's traces, observations and scores.

A disk failure today loses every Dependency-Track finding, every Langfuse
prompt, dataset and evaluator config, and the entire LLM trace history.

## Goals

1. Back up all four Postgres databases and the ClickHouse `default` database
   **nightly**, and **on demand** from the admin Data Operations page.
2. Upload to Google Drive, retaining the newest **7**.
3. Provide a **restore path** for a new or rebuilt host.
4. Do not disturb the existing Mongo backup's behaviour or retention.

## Non-goals

- Backing up MinIO, Redis, Kafka, the Portainer volume, or the Dependency-Track
  Lucene index. See "Accepted data loss" below.
- An in-application restore. Restore is a host shell script — see ADR 2.
- Changing the Mongo backup, its schedule, its folder or its retention.
- Configuring a ClickHouse TTL on trace tables. Likely wanted, but separate.

## Inventory: what prod persists

| Store | Contents | Covered today | Covered after this |
|---|---|---|---|
| MongoDB `simonrowe` | site content, 15 collections | yes | unchanged |
| `backend-uploads` | media + narration audio | yes | unchanged |
| Elasticsearch | content embeddings | yes | unchanged |
| `langfuse-db` → `langfuse` | org/projects/users/API keys, prompts, datasets, evaluator configs | **no** | **yes** |
| `langfuse-db` → `dtrack` | DT projects, components, findings, policies, users | **no** | **yes** |
| `langfuse-db` → `temporal`, `temporal_visibility` | workflow history | **no** | **yes** |
| `langfuse-clickhouse` | LF traces, observations, scores | **no** | **yes** |
| `langfuse-minio` | LF raw event blobs, large payload bodies | no | no |
| `langfuse-redis` | BullMQ queues, cache | no | no — disposable |
| `kafka-data` | topic logs | no | no — disposable |
| `portainer-data` | Portainer users/settings | no | no — cheap to recreate |
| `dependencytrack-data` | Lucene indexes | no | no — self-rebuilding |
| `software-factory-workspace` | PR git clones | no | no — disposable |

## Architecture decisions

### ADR 1 — Backup runs in the backend, not a host script

The backend container already runs as `user: "0:0"` with `/var/run/docker.sock`
and the docker CLI plus compose plugin bind-mounted; `RedeployService` uses
exactly this to orchestrate containers. So a Java service can
`docker exec langfuse-db pg_dump` with no new host prerequisites.

Running the backup there reuses, rather than reimplements:

- `GoogleDriveService`'s resumable chunked upload over the tuned Apache
  transport. `GoogleDriveConfig` documents that the default transport capped at
  ~5 KB/s on this uplink until the socket buffers were tuned; a `curl` upload
  from bash would get neither that throughput nor resumability.
- `DataOperationsService` for the progress SSE stream and the operation mutex.
- `BackupRetentionService` for prune-to-N.
- The admin Data Operations page, giving on-demand triggering for free.

The accepted cost is that a wedged backend means no platform backup that night.
That is already true of the Mongo backup, `monitor-prod.sh` restarts an
unhealthy backend within a minute, and a dead host defeats a container and a
host cron equally.

### ADR 2 — Restore is a host shell script, not an application feature

The scenario that motivates restore is "the Pi died, here is a new one". In that
scenario the backend is the thing being rebuilt, so a restore button inside it is
the wrong tool — a shell script works whether or not the application is up.

It also removes the riskiest code in the design. Restoring Postgres means
dropping a live database, which means terminating its connections, which means
stopping its consumers. That is one `docker compose stop` line in bash and
roughly eighty lines of process orchestration, partial-failure handling and
rollback in Java — for a path that runs perhaps once, under duress.

### ADR 3 — ClickHouse uses its own `BACKUP` command

Options considered:

- **Native `BACKUP`/`RESTORE`** (chosen). Needs an `allowed_backup_paths` config
  entry and a shared volume, so it costs a compose change.
- **Per-table `SHOW CREATE TABLE` + `SELECT * FORMAT Native`.** Zero infra
  change, but hand-rolls schema handling. Langfuse v3's ClickHouse schema has
  materialized views whose data lives in `.inner_id.*` tables, and the schema
  moves with Langfuse versions. A blind spot there yields a backup that restores
  looking correct and is quietly missing an MV.
- **Cold tar of the volume.** Byte-exact and trivial, but nightly Langfuse
  downtime, and tarring a running MergeTree is not consistent.

Restore correctness decided it. Letting ClickHouse serialise its own state keeps
the backup correct across Langfuse version bumps with nobody maintaining a table
list. The one-time compose change buys that permanently.

### ADR 4 — Per-database `pg_dump`, not one `pg_dumpall`

`pg_dumpall` is marginally simpler but forces all-or-nothing restore. Separate
per-database dumps plus `pg_dumpall --roles-only` cost the same and let the
restore script target one tool without touching the others.

### ADR 5 — Separate Drive folder

Platform backups go to `simonrowe-platform-backups`, not the existing
`simonrowe-backups`. This is forced, not cosmetic: `GoogleDriveService
.listBackups(folderId)` returns every `.zip` in a folder and
`BackupRetentionService` deletes everything past the newest 7. Sharing a folder
would make the two backup types evict each other, silently degrading today's
"last 7 days" guarantee to roughly "last 3 days" of each.

### ADR 6 — No cross-store quiescing

Postgres is captured a minute or two before ClickHouse. In the worst case a
handful of traces reference a project row created in that window, and project
rows essentially never change. Pausing Langfuse ingestion to close that gap is
not worth the complexity.

## The secrets problem

`SALT`, `ENCRYPTION_KEY` and `NEXTAUTH_SECRET` are not in the compose
`environment:` block — Langfuse receives them via `env_file: .env`.
Dependency-Track receives `DT_SECRET_MANAGEMENT_DATABASE_KEK` from
`${DEPENDENCYTRACK_KEK}` in the same file.

**A restored database is worthless without the `.env` it was encrypted under.**
Langfuse encrypts stored LLM API keys with `ENCRYPTION_KEY` and hashes API keys
with `SALT`; Dependency-Track encrypts its secrets with the KEK. Restoring onto a
host with a freshly generated `.env` produces rows that load without error and
then fail to decrypt — a failure that presents as success.

Mitigation: `manifest.json` records a **SHA-256 fingerprint of each secret**,
never the value. `restore-platform.sh` compares the fingerprints against the
current `.env` and refuses to proceed on mismatch unless `--force` is given.

The `.env` file itself is **not** included in the backup. It is a secret, the
Drive folder is not an appropriate place for it, and it is already reproduced
from `~/workspace/simonjamesrowe/env`.

## Part 1 — Backup

### Archive layout

```
platform-backup-YYYYMMDD-HHMMSS.zip
├── manifest.json
├── postgres/roles.sql
├── postgres/langfuse.sql
├── postgres/dtrack.sql
├── postgres/temporal.sql
├── postgres/temporal_visibility.sql
└── clickhouse/default.zip
```

`manifest.json` records: schema version, `createdAt`, per-database dump byte
counts, ClickHouse per-table row counts, the Langfuse / Dependency-Track /
ClickHouse image tags in use at capture time, and the secret fingerprints.

Image tags matter for restore: a `dtrack` dump taken under Dependency-Track
5.0.3 restored into a later major version may need that version's own schema
migration to run afterwards, and the manifest is what tells you which version
produced the dump.

### `PlatformBackupService`

New service in `com.simonrowe.dataops`. Sole job: build the archive and upload
it. Sequence:

1. Sweep orphaned files from the ClickHouse backup volume — residue from crashed
   prior runs. Without this, a few failed nights silently fill the SD card.
2. `docker exec langfuse-db pg_dumpall --roles-only` → `postgres/roles.sql`.
3. `docker exec langfuse-db pg_dump -d <db>` for each of the four databases,
   streamed into the zip.
4. `docker exec langfuse-clickhouse clickhouse-client --query "BACKUP DATABASE
   default TO File('<name>.zip')"`, then read the result off the shared volume
   into `clickhouse/default.zip`.
5. Write `manifest.json`.
6. Upload to `simonrowe-platform-backups` via `GoogleDriveService`, reporting
   progress through `DataOperationsService` on the same SSE stream the Mongo
   backup uses.
7. In a `finally`: delete the local zip and the ClickHouse volume file.

Credentials — `LANGFUSE_DB_USER`, `LANGFUSE_DB_PASSWORD`, `CLICKHOUSE_PASSWORD`
— are already in the backend's environment via `env_file: .env`. No new secrets
plumbing.

Returns `boolean`, matching `BackupService.performBackup()`: `true` after
`completeOperation`, `false` after `failOperation`. Exceptions are caught and
converted, never propagated.

### `PlatformBackupScheduler`

```java
@Scheduled(
    cron = "${backup.platform.schedule.cron:0 0 2 * * *}",
    zone = "${backup.platform.schedule.zone:Europe/London}")
```

Same shape as the existing `BackupScheduler`:

1. Skip with a warning if Drive is not connected.
2. Skip if `operationsService.tryStartOperation(PLATFORM_BACKUP)` returns
   `null` — another operation holds the mutex.
3. Run the backup; prune only on success.
4. Report a prune failure separately from a backup failure, so a successful
   upload is never logged as if it had lost data.
5. Wrap everything so no exception reaches the scheduler thread.

02:00 is four hours clear of the 22:00 Mongo backup. The gap is deliberate: the
Mongo backup zips all media and uploads it over a residential uplink, so it can
run long, and the shared mutex means an overrun would cost the platform backup
its whole night. On collision it logs and skips with no retry — matching
existing behaviour, visible in the logs, and self-correcting the next night.

### `BackupRetentionService`

Generalised from a hardcoded folder to `pruneToLimit(String folderName, int
maxBackups)`. The existing Mongo caller passes `simonrowe-backups` and
`backup.retention.max-backups`, preserving today's behaviour exactly. Per-file
delete failures continue to be logged without aborting the sweep.

### `OperationType`

Add `PLATFORM_BACKUP`. No restore constant — there is no in-app restore.

### Controller

- `POST /api/admin/data-operations/platform-backup`
- `GET  /api/admin/data-operations/platform-backups`

Both admin-authenticated like their siblings. No restore endpoint.

### Frontend

A "Platform Data" card in `DataOperationsAdmin.tsx`: a "Back Up Now" button and
a list of the retained backups with date and size, plus corresponding methods in
`dataOperationsApi.ts`. Read-only beyond the trigger — its purpose is to make it
obvious at a glance that the nightly job is running.

### Compose changes

- `config/clickhouse/backup-disk.xml` declaring an `allowed_backup_paths` entry.
- A `langfuse-clickhouse-backups` named volume, mounted into both
  `langfuse-clickhouse` and `backend`.

### Config (`application.yml`)

```yaml
backup:
  schedule:
    cron: "0 0 22 * * *"
    zone: "Europe/London"
  retention:
    max-backups: 7
  platform:
    schedule:
      cron: "0 0 2 * * *"
      zone: "Europe/London"
    retention:
      max-backups: 7
```

## Part 2 — Restore

### `scripts/restore-platform.sh`

```
restore-platform.sh --list
restore-platform.sh --target langfuse --latest
restore-platform.sh --target dtrack   --file ./platform-backup-20260825-020000.zip
restore-platform.sh --target temporal --latest --dry-run
```

Flags: `--target <langfuse|dtrack|temporal|all>`, `--latest`, `--file <zip>`,
`--list`, `--dry-run`, `--force`.

| Target | Restores | Stops first |
|---|---|---|
| `langfuse` | `langfuse` PG DB + ClickHouse `default` | `langfuse`, `langfuse-worker` |
| `dtrack` | `dtrack` PG DB | `dependencytrack-apiserver` |
| `temporal` | `temporal` + `temporal_visibility` | `temporal`, `temporal-ui`, `software-factory` |

Per target:

1. Verify the manifest's secret fingerprints against the current `.env`; abort
   on mismatch unless `--force`.
2. Take a local pre-restore `pg_dump` of whatever is about to be overwritten —
   the same safety net `BackupService.createLocalBackup()` gives the Mongo
   restore. Written outside the repo, on the host.
3. `docker compose stop` the consumers.
4. `pg_terminate_backend` any remaining connections to the target databases.
5. Drop and recreate; `psql -f` the dump. Roles are created from `roles.sql`
   only when absent — the `*-db-init` services normally own that.
6. For `langfuse`, also restore ClickHouse.
7. `docker compose up -d` the consumers; poll their healthchecks.

Targets are independent by construction: a failed `dtrack` restore cannot take
Langfuse down with it.

`--latest` fetches from Drive with the `GOOGLE_DRIVE_*` refresh token already in
`.env` — curl for the token exchange and download, python3 for JSON parsing,
matching the style of `scripts/google-drive-auth.sh`.

`--dry-run` prints every command and touches nothing. This follows the
`monitor-prod.sh` precedent, and for the same reason: a script that shells out to
`docker compose` performs real restarts merely by being run, so there must be a
way to read what it would do without doing it.

### `docs/runbooks/platform-backup-restore.md`

Covers: verifying the nightly ran, restoring one tool onto a live host, and
cold-starting a rebuilt host. The cold-start ordering matters and is easy to get
wrong — bring up `langfuse-db`, let the `*-db-init` services create the roles,
restore, then `up -d` the rest.

## Accepted data loss

Restored Langfuse traces keep their metadata, scores and observations, but very
large payload bodies are missing, because those live in MinIO and MinIO is out
of scope. Redis (queues), Kafka (topic logs) and the software-factory workspace
are disposable. The Dependency-Track Lucene index rebuilds itself. Portainer
settings are cheap to recreate by hand.

## Error handling

- The scheduler never throws to the scheduling thread.
- A failed backup does not trigger a prune.
- A prune failure after a successful upload is logged as a prune failure, not as
  a backup failure.
- Drive-not-connected is a clean skip, matching `BackupScheduler`.
- Temp files are removed in a `finally`, and orphans are swept at the start of
  the next run.
- The restore script is `set -euo pipefail` and restarts the consumers it
  stopped even on a failed restore, so a failure leaves services running against
  the pre-restore database rather than stopped.

## Testing

- `PlatformBackupServiceTest` — archive contains the expected entries; a
  `pg_dump` failure fails the operation; temp files are cleaned up on both the
  success and failure paths; the manifest carries fingerprints, not secrets.
- `PlatformBackupSchedulerTest` — skips when Drive is disconnected; skips when
  the mutex is held; prunes on success; does not prune on failure; a prune
  exception does not fail the backup.
- `BackupRetentionServiceTest` — extended for the folder parameter, with the
  existing Mongo-folder assertions retained unchanged.
- Controller tests for the two new endpoints, including admin auth.
- Frontend test for the Platform Data card.
- `restore-platform.sh` is exercised with `--dry-run` against a local stack;
  a real restore is performed once against a local environment to prove the
  ClickHouse path, and the result recorded in the runbook.

## Open questions for implementation

1. **ClickHouse restore-over-existing.** `RESTORE DATABASE default` onto a
   database that already has tables needs either a prior `DROP DATABASE default
   SYNC` or a permissive setting. The shape is known; the exact incantation must
   be verified against the pinned `clickhouse-server:26.7.1.1315` rather than
   assumed.
2. **ClickHouse backup size.** No TTL is configured on the trace tables, so this
   is unbounded. Measuring it on the Pi is the first implementation step. If it
   runs to multiple GB, 7 copies alongside 7 full Mongo-with-media backups may
   strain the Drive quota, and the answer is a ClickHouse TTL — a separate
   change, and probably wanted regardless.

## Deployment note

Deploying this recreates `langfuse-clickhouse` and `backend`, because both gain
volume mounts. If the memory-cgroup reboot described in `CLAUDE.md` is still
pending, do both in the same maintenance window — that reboot recreates roughly
17 containers anyway.
