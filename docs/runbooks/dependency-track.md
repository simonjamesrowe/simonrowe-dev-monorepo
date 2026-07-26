# Runbook: Dependency-Track (prod)

Owner-executed. These steps touch the production deploy dir
(`~/workspace/simonjamesrowe/simonrowe-dev-monorepo`) and are intentionally **not**
automated in the app workspace.

## Access

SSH access to the Pi now exists: connect as the usual admin user. The host address and
credentials are in the usual env store, not in this file or any other tracked file (this
repo is public, so no LAN topology is committed); the Pi is reachable on the LAN only.
Where a command below needs to run on the Pi, it is written as a bare command under a
"Run on the Pi" heading — paste it into that SSH session, or hand it over as a copy-paste
block if you are working from a machine without the key configured.

## Deploy checklist

Work through this in order. **Step 0 is a hard prerequisite** — skip it and everything else
looks like it succeeded while the hostname simply does not resolve.

- [ ] **Step 0: the `dependency-track.simonrowe.dev` DNS record must exist before deploying.**
      Nothing in this repo creates it. In Cloudflare, `dependency-track.simonrowe.dev` needs a
      record pointing at the same target as the other public hostnames
      (`simonrowe.dev`, `api`, `console`, `langfuse`) — copy whatever those use rather than
      inventing a new target.

      **Confirm, do not assume, that the pinggy custom-domain mapping covers a new subdomain
      label.** The `PINGGY_TOKEN` maps to the `*.simonrowe.dev` custom domain, but whether an
      unseen label is served automatically or must be registered with pinggy has not been
      verified for a *new* label — check it before concluding the deploy is healthy. Verify
      resolution and reachability end to end from a machine off the LAN:

      ```bash
      dig +short dependency-track.simonrowe.dev
      curl -s -o /dev/null -w "dt=%{http_code}\n" https://dependency-track.simonrowe.dev/
      ```

      An empty `dig` result, or `dt=000`/`530`, means DNS or the tunnel mapping is missing.
      Fix that first: until it resolves, CI's four SBOM uploads fail **invisibly**, because the
      `sbom` job is `continue-on-error: true` (see "The `continue-on-error: true` trap").

- [ ] **Step 1: put the required variables in the deploy-dir `.env`.**
      `DEPENDENCYTRACK_DB_PASSWORD`, `DEPENDENCYTRACK_KEK`, `DEPENDENCYTRACK_OIDC_ISSUER` and
      `DEPENDENCYTRACK_OIDC_CLIENT_ID` are declared with compose's required-variable syntax
      (`${VAR:?...}`). If any is missing **or empty**, every `docker compose` command against
      `docker-compose.prod.yml` fails immediately with a named error — including unrelated ones
      like restarting the backend. That is deliberate (see "The passwordless-role trap"), but it
      means the `.env` must be updated *before* the first deploy that includes these services.

- [ ] **Step 2: complete the Auth0 setup** — see the Dependency-Track SSO section in
      `docs/auth0-setup.md`. This is human-gated and cannot be automated.

- [ ] **Step 3: deploy.** Run on the Pi, from the deploy directory:

      ```bash
      cd ~/workspace/simonjamesrowe/simonrowe-dev-monorepo
      git pull
      docker compose -f docker-compose.prod.yml up -d
      docker compose -f docker-compose.prod.yml ps
      ```

      Expect every container `running`, with `dependencytrack-db-init` `exited (0)`.

- [ ] **Step 4: verify.**

      ```bash
      curl -s -o /dev/null -w "dt=%{http_code}\n" https://dependency-track.simonrowe.dev/
      curl -s https://dependency-track.simonrowe.dev/api/v1/oidc/available
      ```

      Expect `dt=200` and `true`. The API server's first boot runs schema migrations and starts
      mirroring vulnerability data — expect sustained high CPU on ARM and a slower-feeling site
      while it runs, so deploy at a quiet time.

- [ ] **Step 5: create the `DEV_PORTAL_ADMIN` team** in Dependency-Track and grant it admin
      permissions (again, see `docs/auth0-setup.md`), then log in via Auth0 and confirm you can
      see the portfolio.

## The passwordless-role trap (fixed — do not undo it)

`dependencytrack-db-init` creates the `dtrack` role, and its `CREATE ROLE` is guarded by a
`SELECT 1 FROM pg_roles` check so it only runs once. That guard was originally the whole
story, which made an unrecoverable state reachable: if the service ever ran with
`DEPENDENCYTRACK_DB_PASSWORD` empty, Postgres logged
`NOTICE: empty string is not a valid password, clearing password`, **`CREATE ROLE` still
succeeded**, and the service printed "dependency-track database ready" and exited 0. The
apiserver then failed authentication forever — and because the role now existed, the guard
skipped the `CREATE` on every subsequent run, so adding the variable to `.env` and re-running
did **not** repair it.

Two changes close this, and both matter:

1. An **unconditional** `ALTER ROLE dtrack LOGIN PASSWORD '...'` runs after the guarded
   `CREATE`. It is idempotent, it repairs the passwordless state described above, and it
   re-syncs the role after a deliberate password rotation in `.env`.
2. The variables use compose's required-variable syntax, so an empty or missing value fails
   the command outright instead of producing a broken role.

If you ever suspect the role is wrong, check it on the Pi rather than guessing:

```bash
docker exec simonrowe-dev-monorepo-langfuse-db-1 psql -U postgres -tAc \
  "SELECT rolname, rolcanlogin, rolpassword IS NULL AS no_password FROM pg_authid WHERE rolname='dtrack'"
```

`no_password = t` means the role has no password set; re-running
`docker compose -f docker-compose.prod.yml up dependencytrack-db-init` with a correct `.env`
now fixes it.

**Password charset constraint:** the password is interpolated into the SQL as a single-quoted
literal, so a value containing a single quote (`'`) breaks the statement with a syntax error
and the init service exits non-zero. Generate `DEPENDENCYTRACK_DB_PASSWORD` without single
quotes (e.g. `openssl rand -base64 32`).

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
- The apiserver's `/data` (which is its `HOME`, holding the Lucene search indexes and analyzer
  working state) is a named volume, `dependencytrack-data`. Without it, every container
  recreation would wipe the indexes and force a full rebuild on four ARM cores.
- The apiserver's connection pool is capped at `DT_DATASOURCE_POOL_MAX_SIZE: "10"` (the v5
  default is 30) because `langfuse-db` is stock `postgres:15` at `max_connections=100` and is
  now shared with the Langfuse app and worker. Exhausting the server's connection slots would
  show up as **Langfuse** failing, which is a misleading place to start debugging.
- Auth is Dependency-Track's native OIDC against the existing Auth0 tenant, reusing the
  `DEV_PORTAL_ADMIN` role/claim that Langfuse already uses. See the Dependency-Track SSO
  section of `docs/auth0-setup.md` for the Auth0 side.

## The KEK gotcha (read this before touching the container)

Dependency-Track v5 encrypts secrets it stores in Postgres (OIDC client secrets, API keys, etc.)
with a **key encryption key (KEK)**. By default the KEK is generated and written to a keyset file
under `${user.home}/.dependency-track/keys/` — and the image sets `HOME=/data/`. When this
service was first added, nothing was mounted at `/data`, so every container recreation (image
pull, version bump, `docker compose up --force-recreate`) generated a **new** KEK and the app
refused to start against the *existing* database:

```
java.lang.IllegalStateException: KEK keyset mismatch. The loaded keyset does not contain all keys
previously registered in the database ...
```

Two things now protect against this, in order of importance:

1. The KEK is **pinned explicitly** via `.env`, which is what actually guarantees stability:

   ```yaml
   DT_SECRET_MANAGEMENT_DATABASE_KEK: ${DEPENDENCYTRACK_KEK:?set DEPENDENCYTRACK_KEK in .env}
   ```

   The `:?` means an empty or missing value fails the compose command outright, rather than
   letting the container fall back to generating its own keyset.

2. `/data` is now a **named volume** (`dependencytrack-data`), so even the fallback keyset file
   would survive container recreation. That volume exists primarily to persist the Lucene
   indexes, but it closes this hole too. Do not read it as a licence to unset the KEK: the
   pinned value in `.env` is the backed-up copy, a Docker volume is not.

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

Run on the Pi:

```bash
cat /proc/cmdline
# Contains: cgroup_disable=memory
ls /sys/fs/cgroup/ | grep -i mem
# No memory.max / memory.current — the controller is off
docker stats --no-stream
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
after reboot on the Pi with:

```bash
cat /proc/cmdline | grep -o cgroup_disable=memory
cat /sys/fs/cgroup/memory.max 2>/dev/null || echo "still disabled"
```

Until that reboot happens, monitor memory pressure at the host level instead of
per-container. Run on the Pi:

```bash
free -m
dmesg | grep -i "out of memory" | tail -20
journalctl -k | grep -i "out of memory" | tail -20
```

Any OOM-kill hit in the last two commands means the apiserver (or something else) is pushing the
host over budget and `mem_limit` on `dependencytrack-apiserver` needs to come down, or something
else needs to move off this host.

## Disk and database size — check this periodically, not just on day one

Dependency-Track's `DEPENDENCYMETRICS_*` tables use daily partitions and **grow unbounded by
default**. They live in the same `dtrack` Postgres database, on the same undifferentiated 117 GB
partition as everything else on the Pi (Mongo, Elasticsearch, Kafka, ClickHouse, MinIO, container
images/logs — there is no separate volume for Postgres data).

Run this monthly, or any time the site feels slow. On the Pi:

```bash
df -h /
docker exec simonrowe-dev-monorepo-langfuse-db-1 psql -U postgres -c "\l+"
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

4. **Logs in but sees nothing.** There are two distinct causes, and both come back to
   `DT_OIDC_TEAM_SYNCHRONIZATION: "true"`. Check under
   **Administration → Access Management → Teams**.

   a. **Team name case mismatch.** The Dependency-Track team must be named exactly
      `DEV_PORTAL_ADMIN` — matching the `https://simonrowe.dev/roles` claim value
      byte-for-byte, including case. A near-miss (`Dev_Portal_Admin`, `dev_portal_admin`)
      means team synchronisation maps the user into no team at all, silently.

   b. **The claim is missing from the ID token, so previously assigned teams get STRIPPED.**
      With team synchronisation enabled, Dependency-Track *reconciles* the user's team
      membership from the claim on **every login** — it does not merely add teams. If the
      `https://simonrowe.dev/roles` claim is absent or empty in the ID token (the
      `Add roles to tokens` Action not deployed, removed from the Login flow, or the user's
      role unassigned), then any team you assigned by hand in the UI is **removed** on their
      next login. Symptom: access that worked yesterday is gone today and re-assigning the
      team in the UI "fixes" it until the next login. Fix the claim, not the team assignment
      — decode the ID token and confirm the claim is present before touching Teams.

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

Generate and upload each SBOM by hand, matching what the CI job does. Every path below is
relative to the repo root — start there.

The CycloneDX Gradle plugin is applied to the **root** project (`build.gradle.kts`), not to
`:backend`, so `cyclonedxBom` must be invoked from the repo root — from `backend/` it fails
with `Task 'cyclonedxBom' not found in project ':backend'`. The output path
`build/reports/bom.json` is likewise relative to the repo root.

```bash
# Backend — from the repo root
./gradlew cyclonedxBom
curl -X POST "https://dependency-track.simonrowe.dev/api/v1/bom" \
  -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
  -F "autoCreate=true" \
  -F "projectName=simonrowe-dev/backend" \
  -F "projectVersion=main" \
  -F "bom=@build/reports/bom.json"

# Frontend — writes frontend/bom.json, so the upload runs from inside frontend/
cd frontend && npm run sbom
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
cd ..  # back to the repo root if you ran the frontend block above
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

# Recreate cleanly via the idempotent init service (recreates the role, resets its password,
# and recreates the database)
docker compose -f docker-compose.prod.yml up dependencytrack-db-init

# Optional: also discard the Lucene indexes, which now refer to rows that no longer exist.
# Dependency-Track rebuilds them, so this only costs CPU.
docker volume rm simonrowe-dev-monorepo_dependencytrack-data

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

- `nginx` no longer needs all four upstreams running to restart safely. The old
  "nginx restart gotcha" was retired by the resolver fix in commit `62d26cc`; `CLAUDE.md` now
  documents the current behaviour under "nginx resolves upstreams at runtime, not just at boot".
  This applies to `dependency-track.simonrowe.dev` the same as every other hostname: nginx boots
  regardless, and 502s only the specific downed host.
- nginx's container healthcheck hits `/healthz` in a `default_server` block that proxies to
  **nothing**, so nginx's health reflects nginx alone. Do not point it back at `/`: with no
  `default_server`, `Host: localhost` fell through to the `simonrowe.dev` block and proxied to
  `frontend`, so a stopped frontend marked nginx unhealthy — and because `pinggy` waits on
  nginx being `service_healthy`, the tunnel never started and every public hostname, Portainer
  included, went offline.
- Never `docker compose up -d` with no service names on a developer machine that also has
  `PINGGY_TOKEN` configured — it starts every service including `pinggy`, which would hijack the
  single production tunnel. Always name services explicitly when testing locally.
