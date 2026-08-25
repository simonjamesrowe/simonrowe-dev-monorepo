# Data Model: Platform Datastore Backup

**Feature**: 034-platform-datastore-backup
**Date**: 2026-08-25

This feature adds no application persistence — no MongoDB collection, no index, no
Mongock change unit. Its "data model" is the archive format and the in-memory
records that describe it. That format *is* the contract with the restore script and
with a future operator reading a months-old archive, so it is specified here as
precisely as a schema.

---

## 1. Archive layout

```text
platform-backup-YYYYMMDD-HHMMSS.zip
├── manifest.json                       # written last; describes everything else
├── postgres/roles.sql                  # pg_dumpall --roles-only
├── postgres/langfuse.sql               # pg_dump -d langfuse
├── postgres/dtrack.sql                 # pg_dump -d dtrack
├── postgres/temporal.sql               # pg_dump -d temporal
├── postgres/temporal_visibility.sql    # pg_dump -d temporal_visibility
└── clickhouse/default.zip              # ClickHouse's own BACKUP output, verbatim
```

Rules:

- **The timestamp is UTC**, formatted `yyyyMMdd-HHmmss`, matching
  `BackupService.TIMESTAMP_FORMAT`. Consistency with the Mongo archive naming
  matters more than local-time readability, because both lists are read side by
  side.
- **Entry paths are fixed and flat.** The restore script addresses entries by
  exact name; a "helpful" nested or dated prefix would break it silently.
- **`clickhouse/default.zip` is opaque.** It is whatever ClickHouse's `BACKUP`
  command produced, stored without inspection or re-compression. Unpacking or
  re-zipping it would defeat ADR 3's entire rationale — letting ClickHouse
  serialise its own state so the backup stays correct across Langfuse version
  bumps.
- **`manifest.json` is written last**, because it records byte counts and row
  counts that are only known after the dumps complete.
- The host's `.env` is **never** in the archive (FR-007).

---

## 2. `manifest.json`

```json
{
  "schemaVersion": 1,
  "createdAt": "2026-08-25T02:00:07.412Z",
  "postgres": {
    "container": "langfuse-db",
    "databases": {
      "langfuse":            { "entry": "postgres/langfuse.sql",            "bytes": 48213771 },
      "dtrack":              { "entry": "postgres/dtrack.sql",              "bytes": 19044210 },
      "temporal":            { "entry": "postgres/temporal.sql",            "bytes": 8871004 },
      "temporal_visibility": { "entry": "postgres/temporal_visibility.sql", "bytes": 2210398 }
    },
    "roles": { "entry": "postgres/roles.sql", "bytes": 3122 }
  },
  "clickhouse": {
    "container": "langfuse-clickhouse",
    "database": "default",
    "entry": "clickhouse/default.zip",
    "bytes": 913448201,
    "tables": {
      "traces": 1842991,
      "observations": 5120443,
      "scores": 88104
    }
  },
  "images": {
    "langfuse": "langfuse/langfuse:3.212.0",
    "dependencytrack-apiserver": "dependencytrack/apiserver:5.0.3",
    "langfuse-clickhouse": "clickhouse/clickhouse-server:26.7.1.1315"
  },
  "secretFingerprints": {
    "ENCRYPTION_KEY": "9f2b…",
    "SALT": "41ac…",
    "NEXTAUTH_SECRET": "c7d0…",
    "DEPENDENCYTRACK_KEK": "1e58…"
  }
}
```

### Field semantics

| Field | Type | Why it exists |
|---|---|---|
| `schemaVersion` | integer | A restore script from a later format must be able to *detect* an older archive rather than misread it. Starts at `1` (FR-005, Assumptions). |
| `createdAt` | ISO-8601 instant, UTC | The authoritative capture time. The filename timestamp is for humans; this is for code. |
| `postgres.container` | string | Records which container was dumped, so an archive taken from a differently-named stack is diagnosable. |
| `postgres.databases.<name>.bytes` | long | Dump size in bytes, counted **as the stream passed into the zip** (research R8). Primary signal that a dump is unexpectedly empty or truncated. |
| `postgres.roles` | object | Same shape as a database entry. Separate because roles restore conditionally (only when absent) while databases restore unconditionally (FR-003, FR-034). |
| `clickhouse.tables.<name>` | long | Per-table row counts (FR-005). The only practical way to verify a ClickHouse restore landed everything, because the archive itself is opaque. Materialized-view inner tables (`.inner_id.<uuid>`) are excluded: **verified** that a restore regenerates those UUIDs, so recording them would make consecutive manifests incomparable and would read as a missing table after a restore that actually succeeded. Their data is derived and is restored with the base tables. |
| `images.*` | string | Image tag per tool at capture time. A `dtrack` dump taken under 5.0.3 restored into a later major may need that version's own schema migration afterwards, and this is what tells you which version produced the dump. |
| `secretFingerprints.<KEY>` | hex string or `null` | One-way digest of a host secret. Never the value (FR-006). `null` means the variable was absent when the archive was written — the restore script must treat that as *unverifiable*, not as *matching*. |

### Invariants

- `bytes` values are always present and non-negative. A zero-byte dump is
  recorded honestly rather than suppressed — the operator needs to see it.
- `secretFingerprints` contains exactly the four keys listed, always, even when a
  value is `null`. A missing *key* would be ambiguous between "absent secret" and
  "older archive format".
- No field anywhere contains a secret value, a connection string with a password,
  or the contents of `.env`.

---

## 3. Secret fingerprint

```text
fingerprint(name, value) = hex( SHA-256( "platform-backup-fingerprint-v1:" + name + ":" + value ) )
```

- UTF-8 bytes, lowercase hex, **no trailing newline** on the input.
- The bash side must therefore use `printf '%s'`, never `echo` — `echo` appends
  `\n` and every legitimate restore would be refused. This is the single most
  likely way to ship a broken fingerprint check (research R7).
- The `platform-backup-fingerprint-v1:` prefix is domain separation: it makes
  precomputed tables useless against a live production secret sitting in cloud
  storage, and pins each digest to its own key so swapping `SALT` and
  `ENCRYPTION_KEY` is detected rather than cancelling out.
- `v1` is versioned alongside `schemaVersion`.

**Fingerprinted keys** — the four whose mismatch corrupts data *silently*:

| Key | What breaks silently on mismatch |
|---|---|
| `ENCRYPTION_KEY` | Langfuse's stored LLM API keys load but never decrypt |
| `SALT` | Langfuse API-key hashes stop matching; keys fail auth with no error at load |
| `NEXTAUTH_SECRET` | Sessions are invalid — recoverable, but a confusing symptom |
| `DEPENDENCYTRACK_KEK` | Dependency-Track's encrypted secrets load but never decrypt |

Database passwords are deliberately excluded: a password mismatch fails loudly at
connect time, so it needs no detection mechanism. Only silent failures justify the
check.

---

## 4. Restore targets

The unit of restore. Chosen so that a failure in one cannot affect another
(FR-027, SC-009).

| Target | Postgres databases | ClickHouse | Consumers stopped first |
|---|---|---|---|
| `langfuse` | `langfuse` | `default` | `langfuse`, `langfuse-worker` |
| `dtrack` | `dtrack` | — | `dependencytrack-apiserver` |
| `temporal` | `temporal`, `temporal_visibility` | — | `temporal`, `temporal-ui`, `software-factory` |
| `all` | all four | `default` | the union of the above |

Why the consumer lists are what they are:

- `langfuse` and `langfuse-worker` share `langfuse-db` *and* ClickHouse, so both
  must stop or the worker writes into a database being dropped.
- `dependencytrack-frontend` is **not** stopped for `dtrack`: it is static nginx
  with no database connection. Stopping it would extend the outage for nothing.
- `software-factory` is stopped for `temporal` because it hosts the Temporal
  workers — a poller against a dropped `temporal` database is exactly the
  "healthy container, no poller registered" failure mode the software-factory
  runbook warns about.
- `langfuse-db` itself is never stopped. Every target drops and recreates
  databases *within* a running server; stopping the server would couple all
  targets together and take Dependency-Track down with a Langfuse restore, which
  is the independence property this table exists to preserve.

### Restore ordering per target

1. Verify fingerprints; abort unless `--force`.
2. Local pre-restore `pg_dump` of what is about to be overwritten (FR-032).
3. `docker compose stop` the consumers.
4. `pg_terminate_backend` any remaining connections to the target databases —
   `DROP DATABASE` fails while even one session is attached, and a stopped
   consumer can leave one briefly.
5. Drop, recreate with the correct owner, `psql -f` the dump. Create roles from
   `roles.sql` **only when absent** — the `*-db-init` services normally own them
   (FR-034).
6. For `langfuse`, restore ClickHouse (research R5).
7. Restart the consumers — **in a trap, so this happens on the failure path too**
   — and poll their healthchecks (FR-035, FR-036).

---

## 5. In-memory records (backend)

Java records, no persistence, no reflection (native-image safe).

| Record | Fields | Notes |
|---|---|---|
| `PlatformManifest` | `schemaVersion`, `createdAt`, `postgres`, `clickhouse`, `images`, `secretFingerprints` | Serialised to `manifest.json`. Built incrementally as the capture proceeds. |
| `PlatformManifest.DumpEntry` | `entry`, `bytes` | One per database, plus one for roles. |
| `PlatformManifest.ClickHouseSection` | `container`, `database`, `entry`, `bytes`, `tables` | `tables` is an ordered map of name → row count. |
| `PlatformBackupProperties` | `postgresContainer`, `clickhouseContainer`, `databases`, `clickhouseBackupPath`, `driveFolderName`, `maxBackups` | `@ConfigurationProperties("backup.platform")`. |

`BackupMetadata` is **reused unchanged** for the archive list — same fields, same
`formatFileSize`, so the frontend's existing `BackupMetadata` type and rendering
work as-is. Introducing a parallel type would duplicate the formatting logic for
no gain.

---

## 6. What is deliberately absent

| Not in the archive | Why |
|---|---|
| The host `.env` | It is a secret; Drive is not the place for it; it is already reproduced from `~/workspace/simonjamesrowe/env` (FR-007). |
| MinIO object store | Out of scope. Consequence — restored traces keep metadata, scores and observations but lose very large payload bodies — must be stated in the runbook so it is not discovered mid-incident. |
| Redis, Kafka, software-factory workspace | Disposable by design. |
| Dependency-Track's Lucene index | Self-rebuilding. |
| Portainer settings | Cheap to recreate by hand. |
| MongoDB, uploads, Elasticsearch | Already covered by the existing backup, which this must not touch (FR-014). |
