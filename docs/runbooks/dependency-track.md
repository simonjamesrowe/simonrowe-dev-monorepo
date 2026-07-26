# Runbook: Dependency-Track (prod)

Owner-executed. These steps touch the production deploy dir
(`~/workspace/simonjamesrowe/simonrowe-dev-monorepo`) and are intentionally **not**
automated in the app workspace.

## Access

SSH access to the Pi now exists: `ssh simonrowe@192.168.4.66`. Credentials are in the usual
env store, not in this file or any other tracked file. Where a command below needs to run on
the Pi, it is written as a direct command — paste it into that SSH session (or run it via a
copy-paste block if working from a machine without the key configured).

## Architecture

- **`dependencytrack-apiserver`** (`dependencytrack/apiserver:5.0.3`, pinned — never `latest`,
  which resolves to the unrelated 4.14.3 line) and **`dependencytrack-frontend`**
  (`dependencytrack/frontend:5.0.3`) are two containers in `docker-compose.prod.yml`.
- Both share the existing `langfuse-db` Postgres container — a new `dtrack` database and
  `dtrack` role, created idempotently by the one-shot `dependencytrack-db-init` service.
  **`langfuse-db` is now a shared dependency of two tools** (Langfuse and Dependency-Track):
  stopping it takes both down.
- nginx (`config/nginx/nginx-proxy.conf`) serves a single hostname,
  `dependency-track.simonrowe.dev`, split by path: `/api/` → `dependencytrack-apiserver:8080`,
  everything else → `dependencytrack-frontend:8080`. `/static/oidc-callback.html` deliberately
  falls through to the frontend location — it is a static SPA asset, not an API route, and must
  not be routed to the apiserver.
- Auth is Dependency-Track's native OIDC against the existing Auth0 tenant, reusing the
  `DEV_PORTAL_ADMIN` role/claim that Langfuse already uses.

## The KEK gotcha (read this before touching the container)

Dependency-Track v5 encrypts secrets it stores in Postgres (OIDC client secrets, API keys, etc.)
with a **key encryption key (KEK)**. By default the KEK keyset is a file inside the container
filesystem with **no volume mounted**, so every container recreation (image pull, version bump,
`docker compose up --force-recreate`) generates a **new** KEK. The app then refuses to start
against the *existing* database:

```
java.lang.IllegalStateException: KEK keyset mismatch. The loaded keyset does not contain all keys
previously registered in the database ...
```

We avoid this by pinning the KEK explicitly via `.env`:

```yaml
DT_SECRET_MANAGEMENT_DATABASE_KEK: ${DEPENDENCYTRACK_KEK}
```

**Rules:**

1. **`DEPENDENCYTRACK_KEK` must NEVER change once the `dtrack` database holds data.** Changing
   it is equivalent to losing the key: every secret already encrypted with the old KEK becomes
   permanently undecryptable, and the apiserver will crash-loop with the error above.
2. Treat it exactly like a database password: back it up in the same env store as the rest of
   the `.env` file, never rotate it casually, never regenerate it as part of routine maintenance.
3. **If it is genuinely lost** (not backed up, or corrupted), there is no way to recover the
   existing encrypted secrets. The only path forward is to drop and recreate the `dtrack`
   database (see "Restoring after data loss" below) and re-supply a fresh
   `DEPENDENCYTRACK_KEK`. This is an acceptable last resort because all Dependency-Track state
   is a **derived cache** — projects, findings and metrics can be rebuilt by re-running the
   `publish` workflow, which re-uploads all four SBOMs.

Verify the KEK is actually being honoured (already done once during implementation, evidence in
`.superpowers/sdd/2026-07-26-dependency-track/kek-verification.md` — repeat only if you suspect
regression):

```bash
docker logs simonrowe-dev-monorepo-dependencytrack-apiserver-1 2>&1 | grep -i "kek\|keyset\|secret manager"
# Expect: "Loading KEK from config" and no IllegalStateException.
```

## Memory limit is currently NOT enforced — read before trusting `docker stats`

`docker-compose.prod.yml` sets `mem_limit: 2g` on `dependencytrack-apiserver`. On this Pi, that
limit is **decorative today**. The kernel boots with the memory cgroup controller disabled:

```bash
ssh simonrowe@192.168.4.66 'cat /proc/cmdline'
# Contains: cgroup_disable=memory
ssh simonrowe@192.168.4.66 'ls /sys/fs/cgroup/ | grep -i mem'
# No memory.max / memory.current — the controller is off
ssh simonrowe@192.168.4.66 'docker stats --no-stream'
# MEM USAGE / LIMIT column reads 0B / 0B for every container, including this one
```

Consequences:

- Docker accepts `mem_limit: 2g` in the compose file but the **kernel does not enforce it**. A
  runaway apiserver process could exceed 2 GB with no per-container throttling or OOM kill —
  only the host-wide OOM killer would eventually step in if total memory ran out.
- `docker stats` memory figures are **useless for this container** (and every other container on
  this host) until the fix below ships. Don't use them to conclude "it's fine" or "it's not."
- The real protection today is **headroom**, not the cap. Measured 2026-07-26: Pi 5, 4 cores,
  16.2 GB RAM, 8.6 GB genuinely available (`free -m`'s `available` column, which already
  accounts for reclaimable page cache), load average 0.5–0.8, no OOM history in `dmesg` or
  `journalctl -k`. A 2 GB apiserver fits comfortably inside that.

To make the limit real: edit `/boot/firmware/cmdline.txt` on the Pi to remove
`cgroup_disable=memory`, then **reboot the Pi**. This is a deliberate, disruptive, human-gated
change — do not do it opportunistically; schedule it like any other host reboot, and confirm
after reboot with:

```bash
ssh simonrowe@192.168.4.66 'cat /proc/cmdline | grep -o cgroup_disable=memory; cat /sys/fs/cgroup/memory.max 2>/dev/null || echo "still disabled"'
```

Until that reboot happens, monitor memory pressure at the host level instead of per-container:

```bash
ssh simonrowe@192.168.4.66 'free -m'
ssh simonrowe@192.168.4.66 'dmesg | grep -i "out of memory" | tail -20'
ssh simonrowe@192.168.4.66 'journalctl -k | grep -i "out of memory" | tail -20'
```

Any OOM-kill hit in the last two commands means the apiserver (or something else) is pushing the
host over budget and `mem_limit` on `dependencytrack-apiserver` needs to come down, or something
else needs to move off this host.

## Disk and database size — check this periodically, not just on day one

Dependency-Track's `DEPENDENCYMETRICS_*` tables use daily partitions and **grow unbounded by
default**. They live in the same `dtrack` Postgres database, on the same undifferentiated 117 GB
partition as everything else on the Pi (Mongo, Elasticsearch, Kafka, ClickHouse, MinIO, container
images/logs — there is no separate volume for Postgres data).

Run this monthly, or any time the site feels slow:

```bash
ssh simonrowe@192.168.4.66 'df -h /'
ssh simonrowe@192.168.4.66 'docker exec simonrowe-dev-monorepo-langfuse-db-1 psql -U postgres -c "\l+"'
```

Watch the `dtrack` row's `Size` column over time. One operator reported unbounded growth from
~50 GB to ~500 GB in a month on a large portfolio; this deployment only tracks four projects so
growth should be far smaller, but it is not bounded by default and the disk is shared with
everything else. If `dtrack` grows into a real share of the 72 GB free (measured 2026-07-26),
either configure Dependency-Track's built-in metrics retention (`DT_METRICS_RETENTION_DAYS` or
equivalent for the running version) or prune old `DEPENDENCYMETRICS_*` partitions manually.

## Break-glass access

If OIDC login is broken, use the local `admin` account (Dependency-Track's built-in break-glass
user, separate from Auth0/OIDC). The password lives in the usual env store, never in this repo.
Log in at `https://dependency-track.simonrowe.dev/` with username `admin` and use the
"local account" login option rather than "Login with Auth0". Change the default password on
first use if this is a fresh install (the UI will prompt for this).

**Never pull `dependencytrack/apiserver:latest` or `dependencytrack/frontend:latest`** — `latest`
currently resolves to `4.14.3`, a different major line with an incompatible config surface
(`ALPINE_*` vars instead of `DT_*`, different KEK handling). Always pin `5.0.3` (or a deliberately
chosen newer 5.x tag) in `docker-compose.prod.yml`.

## Diagnosing a missing login button

Silent OIDC failures are the norm here — nothing errors, the button just doesn't render. Check in
this order:

1. **Is OIDC even enabled/reachable from the frontend's point of view?**

   ```bash
   curl -s https://dependency-track.simonrowe.dev/api/v1/oidc/available
   ```

   Expect `true`. If this returns `false`, Dependency-Track's own config thinks OIDC isn't usable
   — go to step 2.

2. **Issuer trailing slash.** Dependency-Track does strict string equality between
   `DT_OIDC_ISSUER` and the `issuer` field in Auth0's discovery document, which always ends in
   `/`. `DEPENDENCYTRACK_OIDC_ISSUER` in `.env` must be `https://<tenant>.auth0.com/` — **with**
   the trailing slash. Confirm what Auth0 actually serves:

   ```bash
   curl -s "https://<tenant>.auth0.com/.well-known/openid-configuration" | grep -o '"issuer":"[^"]*"'
   ```

   The value in `.env` must match this byte-for-byte, trailing slash included.

3. **`OIDC_SCOPE` unset on the frontend container.** The frontend entrypoint assigns runtime
   config via `jq` unconditionally; an unset `OIDC_SCOPE` becomes `null`, which silently removes
   the login button with no error anywhere. Confirm it's set:

   ```bash
   docker exec simonrowe-dev-monorepo-dependencytrack-frontend-1 env | grep OIDC_SCOPE
   # Expect: OIDC_SCOPE=openid profile email
   ```

4. **Team name case mismatch (auth succeeds but user has no access).** The Dependency-Track team
   must be named exactly `DEV_PORTAL_ADMIN` — matching the `https://simonrowe.dev/roles` claim
   value byte-for-byte, including case. A near-miss (`Dev_Portal_Admin`, `dev_portal_admin`)
   means team synchronisation silently maps the user into no team at all, so they log in but see
   nothing. Check under **Administration → Access Management → Teams**.

## Rotating the CI API key

1. In the UI: **Administration → Access Management → Teams → `CI Upload`** → regenerate the API
   key.
2. Update the GitHub secret:

   ```bash
   gh secret set DEPENDENCYTRACK_API_KEY --repo simonjamesrowe/simonrowe-dev-monorepo
   # paste the new key when prompted
   ```

3. Re-run the last `publish` workflow to confirm the new key works end to end:

   ```bash
   gh run list --repo simonjamesrowe/simonrowe-dev-monorepo --workflow publish.yml --limit 1
   gh run rerun --repo simonjamesrowe/simonrowe-dev-monorepo <run-id>
   ```

4. Confirm with the project check below — do not trust the workflow's green tick alone (see next
   section).

## The `continue-on-error: true` trap

The `sbom` job in `.github/workflows/publish.yml` is deliberately `continue-on-error: true` —
Dependency-Track running on a Pi behind a tunnel must never block a production deploy. That means
**the Publish workflow can show fully green while every SBOM upload silently failed** (expired
API key, DT down, network blip). The workflow's status is not evidence of anything. The only real
confirmation is checking the four projects directly:

Export the API key from your usual env store first (never paste it inline, never commit it):

```bash
export DEPENDENCYTRACK_API_KEY="<value from env store>"

for project in "simonrowe-dev/backend" "simonrowe-dev/frontend" "simonrowe-dev/backend-image" "simonrowe-dev/frontend-image"; do
  echo "=== $project ==="
  curl -s -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
    "https://dependency-track.simonrowe.dev/api/v1/project/lookup?name=${project}&version=main" \
    | grep -o '"lastBomImport":[^,]*'
done
```

Expect a recent (non-null) `lastBomImport` timestamp for all four. A missing project, a `null`
timestamp, or a stale timestamp older than the last merge to `main` means the upload failed —
check the `sbom` job's logs directly (`gh run view <run-id> --log`) rather than trusting the
overall workflow conclusion.

## Manual SBOM upload (when CI has silently failed)

Generate and upload each SBOM by hand, matching what the CI job does:

```bash
# Backend
cd backend && ../gradlew cyclonedxBom
curl -X POST "https://dependency-track.simonrowe.dev/api/v1/bom" \
  -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
  -F "autoCreate=true" \
  -F "projectName=simonrowe-dev/backend" \
  -F "projectVersion=main" \
  -F "bom=@build/reports/bom.json"

# Frontend
cd ../frontend && npm run sbom
curl -X POST "https://dependency-track.simonrowe.dev/api/v1/bom" \
  -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
  -F "autoCreate=true" \
  -F "projectName=simonrowe-dev/frontend" \
  -F "projectVersion=main" \
  -F "bom=@bom.json"
```

For the container image SBOMs, generate with the same tool CI uses (`anchore/sbom-action`'s
underlying `syft`) and upload with `projectName=simonrowe-dev/backend-image` /
`simonrowe-dev/frontend-image`:

```bash
syft ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend:latest -o cyclonedx-json > backend-image-bom.json
curl -X POST "https://dependency-track.simonrowe.dev/api/v1/bom" \
  -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
  -F "autoCreate=true" \
  -F "projectName=simonrowe-dev/backend-image" \
  -F "projectVersion=main" \
  -F "bom=@backend-image-bom.json"
```

(`${DEPENDENCYTRACK_API_KEY}` must be exported from the usual env store first — never paste the
key inline into a command you might paste into a shared terminal or commit to a file.)

## Restoring after data loss

Dependency-Track's own state (projects, findings, metrics history) is a **derived cache**: it can
always be rebuilt from source (the four SBOMs) plus a re-run of the `publish` workflow. It is
**deliberately excluded from `scripts/backup.sh`**, which only backs up MongoDB and
`backend/uploads` — not the `langfuse-db` Postgres container at all.

If the `dtrack` database is corrupted, or the KEK is genuinely lost (see above):

```bash
# From the deploy directory on the Pi
docker compose -f docker-compose.prod.yml stop dependencytrack-apiserver

docker exec simonrowe-dev-monorepo-langfuse-db-1 \
  psql -U postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='dtrack';"
docker exec simonrowe-dev-monorepo-langfuse-db-1 psql -U postgres -c "DROP DATABASE IF EXISTS dtrack;"
docker exec simonrowe-dev-monorepo-langfuse-db-1 psql -U postgres -c "DROP ROLE IF EXISTS dtrack;"

# Recreate cleanly via the idempotent init service (recreates role + database)
docker compose -f docker-compose.prod.yml up dependencytrack-db-init

# If the KEK was the problem, set a fresh DEPENDENCYTRACK_KEK in .env now, before starting
# the apiserver against the empty database, then:
docker compose -f docker-compose.prod.yml up -d dependencytrack-apiserver dependencytrack-frontend

# Re-populate by re-running the last publish workflow (re-uploads all four SBOMs)
gh run list --repo simonjamesrowe/simonrowe-dev-monorepo --workflow publish.yml --limit 1
gh run rerun --repo simonjamesrowe/simonrowe-dev-monorepo <run-id>
```

Note this drops `dtrack` only — `langfuse-db`'s `langfuse` database and the rest of the stack are
untouched (confirmed during implementation, see
`.superpowers/sdd/2026-07-26-dependency-track/kek-verification.md`).

## Notes

- `nginx` no longer needs all four upstreams running to restart safely — see the
  "nginx restart gotcha" entry in `CLAUDE.md`, which was retired by the resolver fix in commit
  `62d26cc`. This applies to `dependency-track.simonrowe.dev` the same as every other hostname:
  nginx boots regardless, and 502s only the specific downed host.
- Never `docker compose up -d` with no service names on a developer machine that also has
  `PINGGY_TOKEN` configured — it starts every service including `pinggy`, which would hijack the
  single production tunnel. Always name services explicitly when testing locally.
