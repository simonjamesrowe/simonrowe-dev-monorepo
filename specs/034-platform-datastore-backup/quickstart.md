# Quickstart: Platform Datastore Backup

**Feature**: 034-platform-datastore-backup — **Revision 2, 2026-08-26**

> **Revision 2**: the capture is now `scripts/backup-platform.sh`, run in the
> `deployer` container on a Temporal schedule, not a Spring service in the backend.
> Sections below that describe triggering it from the admin UI or running
> `PlatformBackupServiceTest` are superseded — see plan.md "Revision 2". The Drive
> folder-isolation check and the whole restore section are unchanged and still apply.

How to build, test and exercise this feature. For operating it in production, see
`docs/runbooks/platform-backup-restore.md` (written as part of this work).

---

## Build and test

```bash
# The capture script — the bulk of the feature
shellcheck scripts/backup-platform.sh
./scripts/backup-platform.sh --dry-run

# Temporal glue in the deployer's module
./gradlew :software-factory:check

# Backend: only the read-only Drive listing remains for this feature.
# NoHostProcessLaunchTest is the one that proves the capture really did move out.
cd backend && ../gradlew test checkstyleMain checkstyleTest
cd backend && ../gradlew test --tests 'com.simonrowe.NoHostProcessLaunchTest'

# Frontend
cd frontend && npm test && npm run lint
```

The capture is bash, so it is verified the way the restore script was: `shellcheck`,
a `--dry-run` read-through, and a real run against a throwaway stack. That method
found three real bugs the first time round (a swallowed `ALTER ROLE`, a `docker cp`
ownership failure, and unstable MV inner-table UUIDs), none of which a unit test would
have caught. The Java that remains — the Temporal activity, workflow and schedule
initializer — is thin and mock-tested.

---

## Exercise the capture locally

The capture shells out to `docker exec langfuse-db` and
`docker exec langfuse-clickhouse`, so it needs those containers running. The
production compose file runs fine on macOS/OrbStack with two `.env` overrides:

```bash
# .env — required on macOS, the compose defaults assume a Linux docker install
DOCKER_BINARY_PATH=/opt/homebrew/bin/docker
DOCKER_PLUGINS_PATH=~/.docker/cli-plugins
```

Then:

```bash
docker compose -f docker-compose.prod.yml up -d langfuse-db langfuse-clickhouse

# Verify the ClickHouse backup path is writable — the single most likely
# first-run failure. The volume is created root-owned at a path absent from the
# image, and ClickHouse runs as 101:101, so clickhouse-backups-init must have run.
docker compose -f docker-compose.prod.yml up clickhouse-backups-init
docker exec langfuse-clickhouse sh -c 'touch /backups/.probe && rm /backups/.probe && echo writable'
```

Trigger a capture by running the script — there is no admin-UI button in revision 2:

```bash
./scripts/backup-platform.sh --dry-run     # always first
./scripts/backup-platform.sh

# The listing is still served by the backend, and is what the admin card shows.
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/admin/data-operations/platform-backups | python3 -m json.tool
```

To exercise the scheduled path without waiting for 02:00, trigger the workflow
directly rather than editing the schedule — and remember the schedule is paused by
default behind `FACTORY_PLATFORM_BACKUP_ENABLED`. **Assert a live poller on the task
queue afterwards**: a `healthy` deployer with no registered poller runs nothing and
says nothing.

### Inspect an archive

```bash
unzip -l platform-backup-*.zip                        # entry list + sizes
unzip -p platform-backup-*.zip manifest.json | python3 -m json.tool
```

Check, in this order — these are the assertions that matter:

1. All seven entries present (`manifest.json`, five `postgres/*.sql`, `clickhouse/default.zip`).
2. No dump is 0 bytes. A zero-byte dump is recorded honestly rather than
   suppressed, so it shows up here rather than during a restore.
3. `secretFingerprints` holds four hex strings (or explicit `null`s) — and **no
   secret values anywhere in the manifest**.
4. `images` records the tags actually running.

---

## Verify the Drive folder isolation

This is the highest-risk detail in the feature (research R1) and the one worth
checking by hand once, because it fails silently:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/admin/data-operations/backups \
  | python3 -c 'import sys,json; print([f["fileName"] for f in json.load(sys.stdin)])'

curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/admin/data-operations/platform-backups \
  | python3 -c 'import sys,json; print([f["fileName"] for f in json.load(sys.stdin)])'
```

The two lists must be **disjoint**: `backup-*.zip` in one, `platform-backup-*.zip`
in the other. Any overlap means the two retention windows will evict each other and
quietly halve both recovery windows. `GoogleDriveFolderResolutionTest` guards this
in CI; this is the manual confirmation against a real Drive account.

---

## Exercise the restore script

`--dry-run` first, always. Every remediation path shells out to `docker compose`,
so simply running the script to "see what it says" performs real restarts.

```bash
./scripts/restore-platform.sh --list
./scripts/restore-platform.sh --target langfuse --latest --dry-run
./scripts/restore-platform.sh --target dtrack --file ./platform-backup-20260825-020000.zip --dry-run
```

Then a real restore against a **local** stack:

```bash
./scripts/restore-platform.sh --target dtrack --file ./platform-backup-*.zip
```

`dtrack` is the right first real target: one database, one consumer, and
Dependency-Track's Lucene index rebuilds itself, so a botched attempt costs
nothing. `langfuse` is the one that must also be proven at least once, because it
is the only target exercising the ClickHouse path — the part of the design that was
an open question rather than a known quantity (spec SC-007).

### Cases worth provoking deliberately

| Case | How | Expected |
|---|---|---|
| Fingerprint mismatch | Edit `ENCRYPTION_KEY` in `.env`, then restore | Refused with an explanation naming the key; proceeds only with `--force` |
| Mid-restore failure | Point `--file` at a zip with a truncated `postgres/dtrack.sql` | Aborts, and the stopped consumer is **restarted** — services run against pre-restore data, never left down |
| Missing roles | Drop the `dtrack` role before restoring | Recreated from `roles.sql`; existing roles untouched |
| Trailing-newline bug | — | Both sides are bash now and share one `fingerprint_of` helper, so the cross-language mismatch cannot occur. If a legitimate restore is ever refused, still suspect `echo` vs `printf '%s'` first |

---

## Measure the ClickHouse archive size (pre-rollout, blocking)

Nothing expires Langfuse's trace tables, so the archive is unbounded and its size
is unknown. Seven platform archives sit alongside seven full Mongo-with-media
archives in the same Drive account, so this is a rollout gate (spec NFR-004,
SC-012). Run on the production host:

```bash
docker exec langfuse-clickhouse clickhouse-client --password "$CLICKHOUSE_PASSWORD" --query "
  SELECT table, formatReadableSize(sum(bytes_on_disk)) AS size, sum(rows) AS rows
  FROM system.parts WHERE active AND database = 'default'
  GROUP BY table ORDER BY sum(bytes_on_disk) DESC"

docker exec langfuse-clickhouse clickhouse-client --password "$CLICKHOUSE_PASSWORD" --query "
  SELECT formatReadableSize(sum(bytes_on_disk)) FROM system.parts
  WHERE active AND database = 'default'"
```

Record the result in the runbook. If it runs to multiple GB, the answer is a
ClickHouse TTL on the trace tables — a separate change, deliberately out of scope
here, and probably wanted regardless.

---

## Deployment note

Deploying this recreates `langfuse-clickhouse` and `backend`, because both gain
volume mounts. If the memory-cgroup reboot described in `CLAUDE.md` is still
pending, do both in the same maintenance window — that reboot recreates roughly 17
containers anyway.

After the deploy, do not trust a green `docker compose ps`: curl the public
hostnames. A `healthy` container is not proof a service is serving, which is how
two containers stayed broken and invisible for 10 days.
