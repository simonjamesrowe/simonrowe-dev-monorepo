# Runbook: platform datastore backup and restore

**Covers:** the four Postgres databases in `langfuse-db` (`langfuse`, `dtrack`,
`temporal`, `temporal_visibility`) and the ClickHouse `default` database.

**Does not cover:** MongoDB, uploaded media or Elasticsearch — those are the
*application* backup, `scripts/backup.sh` and the "Backup to Google Drive" card.
The two are deliberately independent; see [Why two separate
folders](#why-two-separate-folders).

| | Application backup | Platform backup |
|---|---|---|
| What | Mongo, media, embeddings | Postgres ×4 + ClickHouse |
| Schedule | 22:00 Europe/London | 02:00 Europe/London |
| Drive folder | `simonrowe-backups` | `simonrowe-platform-backups` |
| Retained | 7 | 7 |
| Captured by | the backend, on `@Scheduled` | the `deployer`, on a Temporal schedule |
| Capture command | admin Data Ops UI | `scripts/backup-platform.sh` |
| Restore | admin Data Ops UI | `scripts/restore-platform.sh` (host shell) |

---

## Accepted data loss — read this before an incident, not during one

Restored Langfuse traces keep their metadata, scores and observations, but **very
large payload bodies are missing**, because those live in MinIO and MinIO is out
of scope for this backup. Nothing warns you about this at restore time; the traces
simply have empty bodies where a large prompt or completion used to be.

Also not backed up, deliberately: Redis (queues), Kafka (topic logs), the
software-factory workspace (disposable), the Dependency-Track Lucene index
(rebuilds itself), and the Portainer volume (cheap to recreate by hand).

---

## 1. Did the nightly run?

**Fastest check — no shell needed.** Open the admin Data Operations page and look
at the "Platform Data" card. It lists the retained archives with date and size.
Newest entry from last night at 02:00 → healthy. That is the card's whole purpose.

From the shell:

```bash
./scripts/restore-platform.sh --list
```

What to look for, in order:

1. **A dated entry per night**, up to 7. A gap means a run was skipped or failed.
2. **A size that does not swing wildly.** ClickHouse dominates the archive, so a
   sudden drop means the ClickHouse capture produced little or nothing.
3. **Exactly 7, not more.** More means retention pruning is failing — the upload is
   fine, the sweep is not. The script prunes only after a successful upload, so look
   for its `Retention:` lines in the deployer logs.

### Why a run failed or skipped

The run is a Temporal workflow, so its history is the record — richer than a log line,
and it survives a container restart:

```bash
# Recent runs, their status, and how many attempts each took
docker exec simonrowe-dev-monorepo-temporal-1 \
  temporal workflow list --query "WorkflowType='PlatformBackupWorkflow'"

# Why one failed, including the script's own output
docker exec simonrowe-dev-monorepo-temporal-1 \
  temporal workflow show --workflow-id platform-backup-<scheduled-time>
```

Or read the script output directly: `docker logs simonrowe-dev-monorepo-deployer-1`.

- **Failed after 3 attempts** — the script exited non-zero three times. Nothing was
  uploaded and nothing was pruned, which is the intended outcome: a partial archive
  that evicted a good older one would be worse than no backup. The activity's error
  carries the script's own message.
- **Skipped** — the previous run was still going when this one fired, i.e. a capture
  taking over 24 hours. The schedule's overlap policy is SKIP because two concurrent
  captures would fight over the same ClickHouse staging file.
- **Nothing at all** — the schedule is paused, the flag is off, or no poller is
  registered. Check in that order; see §2a.

Note the application and platform backups **can now overlap**. They no longer share
the backend's operation mutex, because the capture runs in a different container. The
22:00/02:00 gap is kept, but for I/O contention on a four-core Pi rather than for
mutual exclusion.

---

## 2. Take a backup now

Before upgrading Langfuse, Dependency-Track or Temporal, or before any maintenance
that touches `langfuse-db`:

```bash
cd ~/workspace/simonjamesrowe/simonrowe-dev-monorepo
./scripts/backup-platform.sh --dry-run   # read it first
./scripts/backup-platform.sh
```

There is **no button** for this. The capture needs `docker exec` into the datastores,
and constitution 2.0.0 keeps host-level container access off the container that
terminates public traffic — so it lives in the `deployer`, and the admin page shows
the archive list only. The list is still the fastest way to confirm a capture landed.

Useful flags: `--no-upload --out-dir DIR` captures without touching Drive (handy for
testing a restore), `--keep-local` uploads but leaves the archive on disk.

### Or trigger the workflow

The scheduled path runs the same script through Temporal, which adds durable retry:

```bash
docker exec -it simonrowe-dev-monorepo-deployer-1 \
  temporal workflow start --task-queue platform-backup \
    --type PlatformBackupWorkflow --input false
```

## 2a. Enabling the nightly schedule (one-time)

Deliberately a two-step rollout, matching the deploy and cve-fix flows.

1. Set `FACTORY_PLATFORM_BACKUP_ENABLED=true` in `.env` and redeploy the `deployer`.
   This registers the activity and **creates the schedule paused** — nothing backs up
   yet.
2. Run a manual `--dry-run`, then a real capture, and confirm the archive appears.
3. Unpause `platform-backup-nightly` in the Temporal UI.
4. **Assert a live poller**, which is the step people skip:

```bash
docker exec simonrowe-dev-monorepo-temporal-1 \
  temporal task-queue describe --task-queue platform-backup
```

A container can be `healthy` with **no poller registered**, in which case the schedule
fires and nothing ever runs — silently. This repo has hit that failure mode twice
already (software-factory's `code-review` queue, and the deployer's `deploy` queue), so
check it rather than infer it from container health.

---

## 3. Restore one tool onto a running host

> **`--dry-run` first, every time.** Every path in this script shells out to
> `docker compose`, so simply running it to "see what it would do" performs real
> restarts. Same precedent, and same reason, as `scripts/monitor-prod.sh`.

```bash
cd ~/workspace/simonjamesrowe/simonrowe-dev-monorepo

# 1. Read what it would do. Changes nothing.
./scripts/restore-platform.sh --target dtrack --latest --dry-run

# 2. Do it.
./scripts/restore-platform.sh --target dtrack --latest
```

Targets are independent by construction — a failed `dtrack` restore cannot take
Langfuse down with it:

| `--target` | Restores | Stops first | Notes |
|---|---|---|---|
| `langfuse` | `langfuse` PG + ClickHouse `default` | `langfuse`, `langfuse-worker` | The only target touching ClickHouse |
| `dtrack` | `dtrack` PG | `dependencytrack-apiserver` | `dependencytrack-frontend` is *not* stopped — static nginx, no DB connection |
| `temporal` | `temporal`, `temporal_visibility` PG | `temporal`, `temporal-ui`, `software-factory` | `software-factory` hosts the Temporal workers |
| `all` | all four + ClickHouse | the union | |

**`langfuse-db` itself is never stopped.** Every target drops and recreates
databases *inside* a running server. That is what keeps the targets independent:
stopping the server would take Dependency-Track and Temporal down with a Langfuse
restore.

### What the script does, per target

1. Verifies the archive's secret fingerprints against your `.env` — see
   [Fingerprint mismatch](#fingerprint-mismatch).
2. Takes a pre-restore `pg_dump` of whatever is about to be overwritten, into
   `~/backups/platform-pre-restore/`. This is your undo.
3. `docker compose stop` the consumers.
4. `pg_terminate_backend` any remaining sessions — `DROP DATABASE` fails while
   even one is attached.
5. Drops, recreates with the correct owner, and loads the dump.
6. For `langfuse`, restores ClickHouse (see §5).
7. Restarts the consumers **from an `EXIT` trap, so this happens even if the
   restore failed**, and polls their health.

That trap matters: a failed restore leaves services running against the
pre-restore database rather than stopped. Verified — an empty dump in the archive
aborts the restore *and* brings the stopped consumer back up.

### After a `dtrack` restore

Dependency-Track's Lucene index lives in the `dependencytrack-data` volume, not in
Postgres, so it can be out of step with restored findings. It rebuilds itself; if
search looks wrong, give it time before investigating.

### After a `temporal` restore

Check that pollers actually re-registered on the `code-review` and `cve-fix` task
queues. `software-factory` can be `healthy` with no poller registered, in which
case webhooks return `202` and nothing ever reviews. See
`docs/runbooks/software-factory.md`.

---

## 4. Fingerprint mismatch

```
WARNING: secret fingerprint mismatch: ENCRYPTION_KEY
WARNING: This archive was captured under DIFFERENT secrets than .../.env holds.
ERROR: refusing to restore (pass --force to override, having understood the above)
```

**This is the check doing its job. Do not reach for `--force`.**

Langfuse encrypts stored LLM API keys with `ENCRYPTION_KEY` and hashes API keys
with `SALT`; Dependency-Track encrypts its secrets with `DEPENDENCYTRACK_KEK`.
Restoring onto a host whose `.env` differs produces rows that **load without error
and then fail to decrypt** — a failure that presents as success. You will not find
out until something quietly stops working.

The fix is to recover the `.env` the archive was captured under, from
`~/workspace/simonjamesrowe/env`. The archive deliberately does not contain the
`.env` itself: it is a secret, Drive is not the place for it, and it is already
reproduced from that directory.

`--force` is correct in exactly one situation: you have accepted that the
encrypted values in that database are lost and you intend to re-enter them by hand
afterwards.

A `WARNING: cannot verify <KEY>` (rather than a mismatch) means the secret was
unset when the archive was written. That is unverifiable, not a match — it does
not block the restore, but it does mean the fingerprint gate gave you no
protection for that key.

---

## 5. The ClickHouse path

Verified against the pinned `clickhouse/clickhouse-server:26.7.1.1315`
(see `specs/034-platform-datastore-backup/research.md`, R5). The sequence is:

```sql
DROP DATABASE IF EXISTS default SYNC;
RESTORE DATABASE default FROM File('<archive>.zip');
```

Established by experiment, and worth knowing before you improvise:

- `RESTORE` onto a **populated** database fails with `Code: 608
  CANNOT_RESTORE_TABLE`. The error suggests `allow_non_empty_tables=true`.
  **Do not use it** — it *appends* rather than replaces, so it would silently
  duplicate every trace row. A loud failure beats duplicated data.
- `SYNC` on the drop is load-bearing: the Atomic engine drops asynchronously, so a
  following `RESTORE` can otherwise race the deletion.
- `RESTORE` into an existing-but-**empty** `default` succeeds. This matters because
  the ClickHouse entrypoint recreates an empty `default` on container restart — so
  the one sequence works whether or not the container bounced in between.
- **`docker cp` alone is not enough.** It preserves the *host* file's ownership, so
  the server (uid 101) cannot read it and the restore fails with
  `Code: 76 CANNOT_OPEN_FILE` — an error that gives no hint that ownership is the
  cause. The script chowns the file after copying it in. Do not remove that step.

Verify a ClickHouse restore by comparing the row counts the script prints against
the `clickhouse.tables` block in the archive's `manifest.json`; the script prints
both. Materialized-view inner tables (`.inner_id.<uuid>`) are excluded from both,
because a restore regenerates those UUIDs — a changed UUID is expected, not a
missing table.

### If `BACKUP` fails with a permission error

```
Code: 76 ... Cannot open file /backups/<name>.zip.lock: errno: 13, Permission denied
```

The `langfuse-clickhouse-backups` volume is not owned by uid 101. `/backups` does
not exist in the ClickHouse image, so Docker creates the volume **root-owned**,
while the server process runs as 101 — even if the container starts as root. The
`clickhouse-backups-init` one-shot service fixes this:

```bash
docker compose -f docker-compose.prod.yml up clickhouse-backups-init
docker exec langfuse-clickhouse sh -c 'touch /backups/.probe && rm /backups/.probe && echo writable'
```

---

## 6. Cold-starting a rebuilt host

**The ordering matters and is easy to get wrong.** Roles must exist before the
dumps load, and the `*-db-init` services are what normally create them.

```bash
cd ~/workspace/simonjamesrowe/simonrowe-dev-monorepo

# 0. The .env MUST be the one the archive was captured under. Restore it from
#    ~/workspace/simonjamesrowe/env FIRST — a freshly generated .env will trip
#    the fingerprint gate, and if you force past it the data is undecryptable.
cp ~/workspace/simonjamesrowe/env .env

# 1. Postgres and ClickHouse only, plus the init services that create the roles
#    (dtrack, temporal) and chown the ClickHouse backup volume.
docker compose -f docker-compose.prod.yml up -d \
  langfuse-db clickhouse-backups-init langfuse-clickhouse \
  dependencytrack-db-init temporal-db-init temporal-schema-init

# 2. Wait for langfuse-db to be healthy and the init services to have exited 0.
docker compose -f docker-compose.prod.yml ps

# 3. Restore. Dry run first.
./scripts/restore-platform.sh --target all --latest --dry-run
./scripts/restore-platform.sh --target all --latest

# 4. Now bring up everything else.
docker compose -f docker-compose.prod.yml up -d

# 5. Do NOT trust a green `docker compose ps`. Curl the public hostnames.
for host in www.simonrowe.dev api.simonrowe.dev langfuse.simonrowe.dev \
            dependency-track.simonrowe.dev temporal.simonrowe.dev console.simonrowe.dev; do
  printf '%-34s %s\n' "$host" "$(curl -s -o /dev/null -w '%{http_code}' "https://$host/")"
done
```

Step 5 is not optional. A `healthy` container is not proof a service is serving:
on 2026-08-14 two containers came back broken and invisible for 10 days after a
reboot, one reporting `healthy` with a dead API port and one with no healthcheck
at all.

Restoring the application data (Mongo, media, Elasticsearch) is a **separate**
step — the admin Data Ops UI, or `scripts/restore.sh`. This runbook does not cover
it.

---

## 7. Archive size and the Drive quota

Nothing currently expires Langfuse's ClickHouse trace tables, so the archive grows
without bound. Seven platform archives sit alongside seven full
Mongo-with-media archives in the same Drive account.

Measure it on the host:

```bash
docker exec langfuse-clickhouse sh -c 'clickhouse-client \
  --user "${CLICKHOUSE_USER:-clickhouse}" --password "${CLICKHOUSE_PASSWORD:-}" --query "
  SELECT table, formatReadableSize(sum(bytes_on_disk)) AS size, sum(rows) AS rows
  FROM system.parts WHERE active AND database = '\''default'\''
  GROUP BY table ORDER BY sum(bytes_on_disk) DESC"'

docker exec langfuse-clickhouse sh -c 'clickhouse-client \
  --user "${CLICKHOUSE_USER:-clickhouse}" --password "${CLICKHOUSE_PASSWORD:-}" --query "
  SELECT formatReadableSize(sum(bytes_on_disk)) FROM system.parts
  WHERE active AND database = '\''default'\''"'
```

| Date | Total on disk | Archive size | Notes |
|---|---|---|---|
| _not yet measured_ | | | **Rollout gate.** Run the two commands above on the Pi and record the result here *before* trusting the retention window. Compressed archive size is typically well below the on-disk figure, but the ratio is unknown until measured. |

The ClickHouse `BACKUP`/`RESTORE` mechanics were verified on a local
`26.7.1.1315` container (see `specs/034-platform-datastore-backup/research.md`,
R5), but **size is a property of production data**, so it can only be measured on
the Pi.

If this runs to multiple GB, the answer is a ClickHouse TTL on the trace tables —
a separate change, deliberately out of scope for the backup work, and probably
wanted regardless.

---

## 8. Deployment note

Rolling this feature out **recreates `langfuse-clickhouse`** (it gains the backup
volume and the config overlay) **and `deployer`** (it gains the feature flags). If the memory-cgroup reboot described in `CLAUDE.md` is
still pending, do both in the same maintenance window — that reboot recreates
roughly 17 containers anyway.

`clickhouse-backups-init` is a one-shot service: it exits 0 and stays `exited` for
the life of the stack. That is success. It is registered in `ONESHOT_SERVICES` in
`scripts/monitor-prod.sh`; a one-shot missing from that list reads as a broken
container on every cron tick and makes the watchdog reconcile the whole stack once
a minute, forever.

---

## Why two separate folders

Retention deletes everything past the newest 7 `.zip` in whichever folder it is
pointed at. If application and platform backups shared one folder they would evict
each other, silently degrading today's "last 7 days" guarantee to roughly "last 3
days" of each — and you would only find out when you needed a six-day-old backup.

`GoogleDriveService.findOrCreatePlatformFolder()` therefore resolves
`simonrowe-platform-backups` by name and **deliberately does not** fall back to
`GOOGLE_DRIVE_FOLDER_ID`, which points at the application folder.
`GoogleDriveFolderResolutionTest` guards this in CI.

To confirm by hand, the two lists must be disjoint:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  https://api.simonrowe.dev/api/admin/data-operations/backups \
  | python3 -c 'import sys,json; print([f["fileName"] for f in json.load(sys.stdin)])'

curl -s -H "Authorization: Bearer $TOKEN" \
  https://api.simonrowe.dev/api/admin/data-operations/platform-backups \
  | python3 -c 'import sys,json; print([f["fileName"] for f in json.load(sys.stdin)])'
```

`backup-*.zip` in one, `platform-backup-*.zip` in the other. Any overlap is a bug.
