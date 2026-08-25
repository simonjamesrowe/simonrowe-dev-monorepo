# Phase 0 Research: Platform Datastore Backup

**Feature**: 034-platform-datastore-backup
**Date**: 2026-08-25

The approved design document (`docs/superpowers/specs/2026-08-25-platform-datastore-backup-design.md`)
already settled the six architectural decisions (ADR 1–6). This document resolves
the implementation-level unknowns that reading the actual code surfaced, plus the
two open questions the design left explicitly unresolved.

---

## R1 — The existing Drive folder resolution silently defeats ADR 5

**Decision**: Add a name-based folder resolver that ignores the configured folder
id, and an independent `google.drive.platform-folder-id` override. Keep
`findOrCreateFolder()` byte-for-byte compatible for the Mongo caller.

**Rationale**: ADR 5 says platform backups must live in
`simonrowe-platform-backups` so the two retention windows cannot evict each
other. But `GoogleDriveService.findOrCreateFolder()`
(`backend/src/main/java/com/simonrowe/dataops/GoogleDriveService.java:71`) short-circuits:

```java
if (configuredFolderId != null && !configuredFolderId.isBlank()) {
  return configuredFolderId;   // FOLDER_NAME is never consulted
}
```

`google.drive.folder-id` comes from `GOOGLE_DRIVE_FOLDER_ID`, which is supplied
via `env_file: .env` (it appears in `application.yml:162` but in no
`environment:` block). So if it is set in production — and it almost certainly is
— then a naive `findOrCreateFolder()` call from the platform path returns **the
Mongo backup folder**, and the two backup types would evict each other. That is
precisely the failure ADR 5 exists to prevent, and it would present as the Mongo
retention window quietly shrinking from 7 days to ~3.

This is the single highest-risk detail in the whole feature, because it fails
silently and only becomes visible when someone needs a six-day-old backup.

**Implementation**:

- Extract the existing body into `findOrCreateFolderByName(String folderName)`,
  which searches by name and creates on absence — no configured-id short-circuit.
- `findOrCreateFolder()` keeps its exact current behaviour: configured id if set,
  else `findOrCreateFolderByName(FOLDER_NAME)`.
- Add `findOrCreatePlatformFolder()`: `google.drive.platform-folder-id` if set,
  else `findOrCreateFolderByName(PLATFORM_FOLDER_NAME)`.
- A test asserts the two resolvers return different folders when
  `google.drive.folder-id` is set — the regression guard for this whole class of
  bug.

**Alternatives considered**:

- *Reuse `findOrCreateFolder()` and rely on `GOOGLE_DRIVE_FOLDER_ID` being
  unset.* Rejected: correctness would depend on an unversioned host file, and the
  failure is silent.
- *Prefix-filter within one folder.* Rejected: it would require changing
  `listBackups`/`pruneToLimit` semantics for the Mongo path, i.e. touching the
  thing the design says not to touch.

---

## R2 — Passing Postgres credentials to `docker exec` without leaking them

**Decision**: `docker exec -e PGPASSWORD <container> pg_dump …` — the flag with a
**bare variable name, no value** — and set `PGPASSWORD` in the `ProcessBuilder`
environment of the docker CLI process.

**Rationale**: `docker exec -e NAME` (no `=value`) tells the Docker CLI to forward
the value from its own environment. The secret therefore never appears in any
process's `argv`, so it cannot be read from `ps` on the host, and it is not
persisted into the container's config the way `environment:` entries are.

The credentials themselves need no new plumbing: the backend declares
`env_file: .env` (`docker-compose.prod.yml:280`), so every variable in `.env` —
including `LANGFUSE_DB_USER`, `LANGFUSE_DB_PASSWORD` and `CLICKHOUSE_PASSWORD` —
is already in the backend process environment. This confirms the design's "no new
secrets plumbing" claim.

**Alternatives considered**:

- *`docker exec -e PGPASSWORD=<value>`.* Rejected: puts the password in argv.
- *Rely on the postgres image's `local all all trust` pg_hba entry and pass no
  password.* Works today for socket connections, but couples correctness to an
  undocumented property of the base image. Forwarding the password costs one flag
  and works either way.

---

## R3 — The four databases have three different owners

**Decision**: Run every `pg_dump` as the superuser (`LANGFUSE_DB_USER`, default
`postgres`), and capture roles with `pg_dumpall --roles-only`.

**Rationale**: The databases are not uniformly owned:

| Database | Owner | Created by |
|---|---|---|
| `langfuse` | `${LANGFUSE_DB_USER}` (superuser) | the postgres image's `POSTGRES_DB` |
| `dtrack` | `dtrack` | `dependencytrack-db-init` |
| `temporal`, `temporal_visibility` | `temporal` | `temporal-db-init` / `temporal-schema-init` |

Only the superuser can read all four. A restore therefore needs the `dtrack` and
`temporal` roles to exist before the dumps are loaded, which is what
`roles.sql` provides — and why ADR 4's per-database split matters: the
`*-db-init` services normally own role creation, so the restore must create roles
only when absent rather than unconditionally.

`pg_dumpall --roles-only` emits `CREATE ROLE` with password hashes. That is
sensitive but not newly so: the same passwords are already in `.env`, the archive
never leaves the operator's own Drive, and without roles a restore cannot produce
a working database. It is noted in the runbook rather than filtered.

---

## R4 — ClickHouse `BACKUP` needs a config entry, a volume, and a chown

**Decision**: `config/clickhouse/backup-disk.xml` declaring
`<backups><allowed_path>` , plus a `langfuse-clickhouse-backups` named volume
mounted into `langfuse-clickhouse` and `backend`, plus a one-shot
`clickhouse-backups-init` busybox service that chowns it.

**Rationale**: `BACKUP DATABASE default TO File('name.zip')` is refused unless the
target is under a path the server has been told to allow. The config overlay is a
drop-in at `/etc/clickhouse-server/config.d/`, matching how the existing
`searxng` and `temporal` config files are mounted.

The chown service is not optional. `langfuse-clickhouse` runs as
`user: "101:101"` (`docker-compose.prod.yml:531`). A named volume mounted at a
path that does **not** exist in the image — `/backups` — is created empty and
root-owned, so ClickHouse cannot write to it and every `BACKUP` fails with a
permission error. (`langfuse-clickhouse-data` does not have this problem because
`/var/lib/clickhouse` exists in the image, so Docker seeds the volume with the
image's content *and ownership*.) The repo already has exactly this pattern in
`uploads-init` (`docker-compose.prod.yml:262`), so this follows precedent.

**Critical follow-on**: `scripts/monitor-prod.sh` keeps an explicit
`ONESHOT_SERVICES` list (line ~58) of containers whose `exited 0` state is
success. A new one-shot service that is *not* added to that list reads as a
broken container on every cron tick, and the watchdog reconciles the whole stack
once a minute, forever. `clickhouse-backups-init` must be added there in the same
change.

**Alternatives considered**: covered by ADR 3 (per-table `SELECT … FORMAT Native`,
cold tar of the volume) — both rejected there on restore-correctness grounds.

---

## R5 — ClickHouse restore over an existing database (design open question 1)

**RESOLVED BY EXPERIMENT.** Verified 2026-08-25 against the pinned
`clickhouse/clickhouse-server:26.7.1.1315` image (arm64, OrbStack), on a fixture
reproducing Langfuse's awkward shape: two `MergeTree` tables plus a materialized
view, so the `.inner_id.<uuid>` inner table the design worried about was actually
present.

**Verified sequence** — no fallbacks needed:

```sql
DROP DATABASE IF EXISTS default SYNC;
RESTORE DATABASE default FROM File('<name>.zip');
```

### What the experiment established

| # | Case | Result |
|---|---|---|
| A | `RESTORE DATABASE default` onto a **populated** database | **Fails**, `Code: 608 CANNOT_RESTORE_TABLE` — "already contains some data". The error itself suggests `allow_non_empty_tables=true`; see below for why that is refused. |
| B | `DROP DATABASE IF EXISTS default SYNC` | Succeeds; `default` disappears from `system.databases` and is *not* auto-recreated while the server keeps running. |
| C | `RESTORE` after the drop | **`RESTORED`.** All tables return, including the materialized view *and* its inner table, with exact row counts, and the MV is queryable. |
| D | Container restarted between drop and restore | The entrypoint **does** recreate an empty `default`. |
| E | `RESTORE` into an existing-but-**empty** `default` | **`RESTORED`.** No `allow_different_database_def` required. |
| F | File placed via `docker cp`, then restored | **Fails**, `Code: 76 CANNOT_OPEN_FILE`, permission denied. |
| G | Same file after `docker exec -u 0 … chown 101:101` | **`RESTORED`.** |

**Both documented fallbacks turned out to be unnecessary.** Case E is the reason:
because `RESTORE` accepts an existing-but-empty database, the single sequence works
whether or not the container restarted in between — which is exactly the fragile
window the fallbacks were meant to cover.

`allow_non_empty_tables = 1` remains deliberately **unused** even though
ClickHouse's own error message recommends it: it *appends* rather than replaces,
so it would silently duplicate every trace row. A loud failure is better than
duplicated data. `SYNC` on the drop is load-bearing — the Atomic engine drops
asynchronously by default, so a following `RESTORE` can otherwise race the
deletion.

### Two code changes this experiment forced

Both were wrong in the pre-verification design, and neither would have been caught
by a unit test:

1. **`docker cp` breaks the restore (cases F/G).** It preserves the *host* file's
   uid and mode — observed as `501:root` mode `0640` — so ClickHouse (uid 101)
   cannot read it and the restore fails at `CANNOT_OPEN_FILE`, not at anything
   that hints at ownership. `restore-platform.sh` must therefore
   `docker exec -u 0 <container> chown 101:101 <file>` after copying it in. Had
   this not been tested, the ClickHouse restore would have failed the first time
   it was ever needed, in an incident.
2. **MV inner tables must be excluded from the manifest's row counts.** The row
   count query originally recorded every table in `system.parts`, which includes
   `.inner_id.<uuid>`. Case C shows the **UUID changes across a restore**
   (`69456919…` → `def7d1a1…`), so recording it makes every manifest incomparable
   with the next and shows up as a *missing table* on a post-restore verification
   that in fact succeeded. The query now filters `table NOT LIKE '.inner%'`;
   the inner table's data is derived from the base tables and is restored with
   them regardless.

### Also confirmed here: R4's permission claim was not theoretical

The first `BACKUP` attempt failed with `CANNOT_OPEN_FILE … /backups/verify-1.zip.lock,
errno 13`, and it failed **even with the container started as root**, because the
ClickHouse *server process* drops to uid 101 regardless. `chown -R 101:101 /backups`
fixed it immediately. So `clickhouse-backups-init` is required, not defensive.

Spec FR-037 and SC-007 are satisfied by this experiment; the sequence goes into
the runbook as verified rather than proposed.

---

## R6 — Archive size on the Pi (design open question 2)

**Decision**: Measure before rollout, from a copy-paste command block run on the
host. Do not gate the implementation on it; do gate the *rollout* on it.

**Rationale**: Nothing expires Langfuse's ClickHouse trace tables, so the
compressed size is unbounded and unknown. Seven platform archives sit alongside
seven full Mongo-with-media archives in the same Drive account. If ClickHouse
compresses to multiple GB, the quota — not the code — is the binding constraint,
and the fix is a ClickHouse TTL, which the design correctly scopes out.

There is no SSH from this workspace, so the measurement is emitted as a single
command block for the operator (`docker exec langfuse-clickhouse clickhouse-client
--query "SELECT formatReadableSize(sum(bytes_on_disk)) FROM system.parts WHERE
active AND database='default'"`, plus per-table breakdown). Result recorded in the
runbook. Spec NFR-004 and SC-012 track this.

---

## R7 — Fingerprinting secrets identically in Java and bash

**Decision**: `SHA-256("platform-backup-fingerprint-v1:" + name + ":" + value)`,
lowercase hex, over UTF-8 bytes with **no trailing newline**.

**Rationale**: The fingerprint is compared by a bash script against a Java-written
manifest, so the two implementations must agree exactly. Two things make that
fragile if left implicit:

- **The trailing newline.** `echo "$v" | sha256sum` hashes `v\n`; Java hashes `v`.
  The script must use `printf '%s'`. This is the most likely way to ship a
  fingerprint check that refuses every legitimate restore.
- **The domain-separation prefix.** A bare `SHA-256(value)` is a
  rainbow-table-able hash of a live production secret sitting in a cloud
  filesystem. Prefixing with a fixed non-secret label and the key's name makes
  precomputed tables useless and pins each digest to its own key, so a
  `SALT`/`ENCRYPTION_KEY` swap is detected rather than cancelling out. The `v1`
  makes the scheme versionable alongside the manifest's `schemaVersion`.

**Fingerprinted keys** — the four whose mismatch corrupts data silently:

| Key | Consequence of restoring under a different value |
|---|---|
| `ENCRYPTION_KEY` | Langfuse's stored LLM API keys will not decrypt |
| `SALT` | Langfuse API-key hashes stop matching; keys silently fail auth |
| `NEXTAUTH_SECRET` | Existing sessions invalid (recoverable, but a real symptom) |
| `DEPENDENCYTRACK_KEK` | Dependency-Track's encrypted secrets will not decrypt |

Database passwords are deliberately **not** fingerprinted: a password mismatch
fails loudly at connect time, which needs no detection mechanism. Only
*silent* failures justify the check.

All four are already in the backend's environment via `env_file: .env`, so
`System.getenv` reads them with no new configuration. Absent values are recorded
as `null`, and the restore script treats "absent in the manifest" as unverifiable
rather than as a match.

---

## R8 — Streaming a dump into the archive

**Decision**: Stream each `pg_dump` process's stdout straight into an open
`ZipOutputStream` entry, counting bytes as they pass. Never buffer a dump in
memory or stage it on disk.

**Rationale**: The existing `BackupService` accumulates each Mongo collection into
a `StringBuilder`, which is fine for a few thousand documents but would be
reckless for a `temporal_visibility` dump of unknown size on a 2 GB-limited
container. Streaming also yields the per-database byte counts the manifest needs
(FR-005) for free, as a side effect of the copy.

`pg_dump`'s stderr must be drained on a separate thread and captured for the error
message; a full stderr pipe with nobody reading it deadlocks the process while its
stdout still has data. Exit code is checked *after* the stream is fully consumed —
checking early is how "the archive contains a truncated dump and the backup
reported success" happens.

`ProcessBuilder` in a GraalVM native image is already proven in this codebase by
`RedeployService`, so this needs no new native-image configuration.

---

## R9 — Where the schedule sits relative to the Mongo backup

**Decision**: 02:00 Europe/London, per the design. Configurable via
`backup.platform.schedule.cron`.

**Rationale**: The Mongo backup runs at 22:00 (`application.yml:407`), zips all
media, and uploads over a residential uplink, so it can run for hours.
`DataOperationsService` holds a single global mutex
(`AtomicReference.compareAndSet`), so an overlap does not queue — it *skips the
whole night*. Four hours of clearance makes that rare; when it does happen the
scheduler logs and skips with no retry, matching `BackupScheduler` exactly and
self-correcting the next night.

---

## R10 — Frontend and watchdog touchpoints that are easy to miss

**Decision**: Three edits that are not in the design document but are required for
it to work as described.

1. `frontend/src/services/dataOperationsApi.ts:11` types `DataOperation.type` as a
   closed string union that omits `REEMBED_CONTENT` already; adding
   `PLATFORM_BACKUP` to it is required or the SSE progress events for a platform
   backup are typed as never occurring. (`REEMBED_CONTENT` missing is a latent
   pre-existing bug in the same union; adding both is a one-word fix and avoids
   leaving a known hole next to a new one.)
2. `scripts/monitor-prod.sh` `ONESHOT_SERVICES` must gain
   `clickhouse-backups-init` — see R4.
3. `DataOperation.type.replace('_', ' ')` in `DataOperationsAdmin.tsx` replaces
   only the *first* underscore, so `PLATFORM_BACKUP` renders as "PLATFORM BACKUP"
   correctly, but this is worth an assertion in the frontend test rather than an
   assumption.

---

## Summary of resolved unknowns

| # | Unknown | Resolution |
|---|---|---|
| R1 | Drive folder isolation | New name-based resolver; configured-id short-circuit bypassed |
| R2 | Postgres credential passing | `docker exec -e PGPASSWORD` (no value) + ProcessBuilder env |
| R3 | Multiple database owners | Dump as superuser; `roles.sql` creates absent roles only |
| R4 | ClickHouse backup path | Config overlay + named volume + chown init + watchdog list |
| R5 | ClickHouse restore-over-existing | `DROP … SYNC` + `RESTORE`, two fallbacks, verified not assumed |
| R6 | Archive size | Measured on the host pre-rollout via a command block |
| R7 | Secret fingerprint scheme | Domain-separated SHA-256, no trailing newline, four keys |
| R8 | Dump streaming | Stream to zip, drain stderr on a thread, exit code after EOF |
| R9 | Schedule | 02:00, four hours clear of the 22:00 Mongo backup |
| R10 | Missed touchpoints | Frontend union type, watchdog one-shot list, label rendering |

No `NEEDS CLARIFICATION` markers remain.
