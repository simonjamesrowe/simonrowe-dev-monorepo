# Quickstart: Platform Datastore Backup

**Feature**: 034-platform-datastore-backup

How to build, test and exercise this feature. For operating it in production, see
`docs/runbooks/platform-backup-restore.md` (written as part of this work).

---

## Build and test

```bash
# Backend unit tests + checkstyle (the pre-commit hook runs these)
cd backend && ../gradlew test checkstyleMain checkstyleTest

# Just this feature's tests
cd backend && ../gradlew test --tests 'com.simonrowe.dataops.*'

# Frontend
cd frontend && npm test
cd frontend && npm run lint      # blocking in CI
```

Every new backend test is a plain unit test with mocks — no Docker, no Postgres,
no Testcontainers. That is deliberate: `PlatformBackupService` takes a
`CommandRunner` seam over `ProcessBuilder`, so the archive contents, the failure
paths, the manifest and the temp-file cleanup are all assertable with a fake.
Without that seam the service would only be testable on a host running the whole
production stack, which in practice means untested.

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

Trigger a capture from the admin UI ("Platform Data" → "Back Up Now") or directly:

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/admin/data-operations/platform-backup

curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/admin/data-operations/platform-backups | python3 -m json.tool
```

To watch the nightly path without waiting for 02:00, override the cron:

```bash
BACKUP_PLATFORM_SCHEDULE_CRON='0 */5 * * * *' ./scripts/start-backend.sh
```

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
| Trailing-newline bug | — | Covered by `SecretFingerprinterTest`'s cross-language vector; if a legitimate restore is ever refused, suspect `echo` vs `printf '%s'` first |

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
