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
      Fix that first: until it resolves, CI's five SBOM uploads fail **invisibly**, because the
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

- [ ] **Step 5: change the local `admin` password — do this before anything else in the UI.**
      Dependency-Track seeds `admin`/`admin` with a forced password change, and this instance is
      public. That account bypasses Auth0 completely. Confirm the default is dead:

      ```bash
      curl -s -o /dev/null -w '%{http_code}\n' -X POST \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        --data-urlencode 'username=admin' --data-urlencode 'password=admin' \
        https://dependency-track.simonrowe.dev/api/v1/user/login
      ```

      `401` with an empty body is correct; `401` with the body `FORCE_PASSWORD_CHANGE` means the
      default password is still live.

- [ ] **Step 6: create the OIDC group, the team, and the mapping between them** — all three, per
      step 13 of `docs/auth0-setup.md`. A team on its own is **not** enough: Dependency-Track
      does not match claim values to team names. Then log in via Auth0 and confirm you can see
      the portfolio.

- [ ] **Step 7: create the `CI Upload` team** with `BOM_UPLOAD`, `PROJECT_CREATION_UPLOAD` and
      `VIEW_PORTFOLIO`, generate its API key, and store it as the `DEPENDENCYTRACK_API_KEY`
      GitHub secret — otherwise the publish workflow's SBOM uploads fail invisibly.

- [ ] **Step 8: enable the OSV vulnerability source.** A fresh install only enables NVD, which
      matches by CPE and therefore cannot produce a single finding for a Maven or npm dependency.
      See "Zero vulnerabilities on the dependency SBOMs" below — without this step the whole
      deployment looks healthy and reports nothing.

## Zero vulnerabilities on the dependency SBOMs (out-of-the-box configuration)

**Symptom.** All five projects import cleanly with sensible component counts, but only the
container-image projects show any findings. `simonrowe-dev/backend` and `simonrowe-dev/frontend`
sit at 0 vulnerabilities forever, and the portfolio dashboard looks reassuringly quiet.

**This is not an SBOM or upload problem.** Confirm that first — the component counts prove the
BOMs arrived intact:

```bash
# Any API key with VIEW_PORTFOLIO works; the CI Upload key already has it
curl -s -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
  'https://dependency-track.simonrowe.dev/api/v1/project?pageSize=100' \
  | jq -r '.[] | "\(.name) components=\(.metrics.components) vulns=\(.metrics.vulnerabilities)"'
```

**Root cause.** A default install enables exactly one vulnerability source, NVD, and one
analyzer, `internal`. NVD describes affected products as **CPEs**, and — in Dependency-Track's
own words — *"the internal analyzer skips components that lack a valid CPE when evaluating NVD
data."* The matching identifier per source is:

| Source | Matches on | Enabled by default |
|:-------|:-----------|:-------------------|
| NVD | CPE | yes |
| OSV | PURL | **no** |
| GitHub advisories | PURL | **no** (also needs a PAT) |

The CycloneDX Gradle plugin and `@cyclonedx/cyclonedx-npm` both emit **PURLs only, no CPEs**
(verified: 476/476 Maven components and 493/493 npm components had `cpe: null`). So with only
NVD mirrored, those two projects are *structurally* unmatchable — nothing is broken, there is
simply no data source that speaks their identifier.

The image projects appeared to work only because `syft` stamps CPEs on `deb`, `apk` and `golang`
packages. Every finding they had came back `source: NVD`, `analyzer: internal`, and all of it on
`pkg:deb/ubuntu/openssl` and `pkg:golang/stdlib`. Note that `syft` *also* synthesises CPEs for
jars inside an image, but they are vendor-guessed from the artifact name
(`cpe:2.3:a:a2a-java-sdk-common:a2a-java-sdk-common:...`), so they almost never match a real NVD
CPE — the image projects were not covering the Java dependencies either.

**Fix — enable OSV** (Administration → Vulnerability Sources → Osv): tick **Enabled**, keep the
default ecosystems (`npm`, `Go`, `Maven`, `NuGet`, `PyPI`), **Save**, then **Mirror now**. OSV
needs no credentials. Because OSV re-publishes GHSA records under their GHSA IDs, the findings
land attributed `source: GITHUB` — **enabling the GitHub source separately is largely redundant**
and only buys alias/CVSS detail in exchange for having to manage a PAT.

Measured on the production Pi, 2026-07-30:

- The mirror took **~31 minutes** and grew the vulnerability database from **371,149 → 636,750**
  records. Much of that bulk is `MAL-*` malicious-package advisories, which OSV ships inside the
  npm and PyPI ecosystems. Budget for the disk growth — see "Disk and database size" below.
- Mirroring alone does **not** re-evaluate existing projects. Trigger re-analysis, then refresh
  metrics, or the UI keeps showing the old zeros:

  ```bash
  for uuid in $(curl -s -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
      'https://dependency-track.simonrowe.dev/api/v1/project?pageSize=100' | jq -r '.[].uuid'); do
    curl -s -X POST -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
      "https://dependency-track.simonrowe.dev/api/v1/finding/project/${uuid}/analyze"
    curl -s -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
      "https://dependency-track.simonrowe.dev/api/v1/metrics/project/${uuid}/refresh"
  done
  curl -s -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
    https://dependency-track.simonrowe.dev/api/v1/metrics/portfolio/refresh
  ```

- Result: portfolio findings went from 49 to 189, projects at risk from 2 to 4, vulnerable
  components from 3 to 32. Per project: `backend` 0 → 13, `frontend` 0 → 36,
  `backend-image` 25 → 76, `reviewer-image` 24 → 64.

**Still outstanding:** `simonrowe-dev/frontend-image` remains at 0. It contains only `pkg:apk/*`
Alpine packages, and the `Alpine` OSV ecosystem is not in the mirrored list. Add `Alpine`
(and `Ubuntu` for the two Ubuntu-based images) under **Add Ecosystem** if distro-aware findings
are wanted there. Distro ecosystems are also the *accurate* source for OS packages: NVD CPE
matching flags every CVE ever filed against `openssl 3.0.13` regardless of whether Canonical
already backported the fix into `3.0.13-0ubuntu3`, so a good share of the 25 NVD openssl
findings on `backend-image` are likely false positives.

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
   `publish` workflow, which re-uploads all five SBOMs.

Verify the KEK is actually being honoured (already done once during implementation, evidence in
`.superpowers/sdd/2026-07-26-dependency-track/kek-verification.md` — repeat only if you suspect
regression):

```bash
docker logs simonrowe-dev-monorepo-dependencytrack-apiserver-1 2>&1 | grep -i "kek\|keyset\|secret manager"
# Expect: "Loading KEK from config" and no IllegalStateException.
```

## Memory limit is currently NOT enforced — read before trusting `docker stats`

`docker-compose.prod.yml` sets `mem_limit: 2g` on `dependencytrack-apiserver` (and limits on 16
other services). On this Pi, every one of those limits is **decorative until the memory cgroup
is enabled** — there is now a script for that, see the end of this section. The kernel boots with the memory cgroup controller disabled:

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

To make the limit real, use `scripts/enable-memory-cgroup.sh` (see
`docs/runbooks/prod-monitoring.md` for the full write-up):

```bash
./scripts/enable-memory-cgroup.sh --verify   # report current state
./scripts/enable-memory-cgroup.sh --apply    # backs cmdline.txt up first
# ... reboot at a planned time ...
./scripts/enable-memory-cgroup.sh --verify   # docker stats must not read 0B / 0B
```

⚠️ **Earlier revisions of this runbook said to "remove `cgroup_disable=memory` from
`/boot/firmware/cmdline.txt`". That instruction cannot work, and is probably why this was
never fixed.** The parameter is **not in `cmdline.txt`, `config.txt`, or any other file
under `/boot`** — the Raspberry Pi *firmware* prepends it to the kernel command line, so it
appears in `/proc/cmdline` while `grep -r cgroup /boot/firmware/` returns nothing. There is
nothing to delete. The working approach is to *append* an explicit re-enable
(`cgroup_enable=memory cgroup_memory=1`); the kernel parses its command line left to right,
and the later parameter wins over the firmware's earlier disable.

Two things to know before scheduling that reboot:

- A reboot is the **riskiest event on this host**. The 2026-08-14 reboot is what left both
  Dependency-Track and Langfuse broken for 10 days (see "The false-healthy trap" below).
  Verify the stack afterwards rather than assuming a green `docker compose ps`.
- The reboot is also the natural moment for the `mem_limit`/`mem_reservation` values added
  across `docker-compose.prod.yml` to take effect, since applying them requires recreating
  ~17 containers and they do nothing until the controller is on. Note that recreating
  `langfuse-db` briefly takes down **both** Langfuse and Dependency-Track, which share that
  Postgres instance.

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

## The false-healthy trap — `healthy` does not mean the API is serving

**Symptom:** the UI loads at `https://dependency-track.simonrowe.dev/` but you cannot log in
with Auth0, and every `/api/` request returns **502**. `docker compose ps` shows
`dependencytrack-apiserver` as **`healthy`**, and it has been "healthy" for days.

This happened for real: on 2026-08-14 the Pi rebooted, and the apiserver came back with

```
Exception in thread "main" java.lang.NoClassDefFoundError: dev/cel/runtime/CelFunctionBinding
    at dev.cel.extensions.CelStringExtensions$Function.<clinit>(CelStringExtensions.java:51)
    ...
    at org.dependencytrack.policy.cel.CelPolicyEngine.<init>(CelPolicyEngine.java:109)
    at org.dependencytrack.dex.DexEngineInitializer.contextInitialized(...)
```

The Jetty API listener on **8080 never started**, so nginx had nothing to proxy to. But the
JVM did **not exit** — the management/health listener on 9000 and the Hikari connection pool
run on non-daemon threads, so the process stayed alive. Therefore:

- `restart: unless-stopped` never fired (the policy only acts on process *exit*).
- The old healthcheck probed **only** `curl -fsS http://localhost:9000/health/ready`, which
  reports datasource reachability and knows nothing about the API port. It returned
  `{"status":"UP","checks":[{"name":"dataSources","status":"UP"}]}` throughout.
- Docker therefore reported `healthy`, and **Docker never restarts an unhealthy container
  anyway** — so even a correct healthcheck would not have self-healed it.

It stayed broken for 10 days.

**This is not a bad image.** `dev.cel.runtime.CelFunctionBinding` *is* present, in
`/opt/owasp/dependency-track/lib/runtime-0.13.1.jar`, and the classpath is
`dependency-track-apiserver.jar:lib/*` which includes it. (Note `cel-0.13.1.jar` contains only
the *lite* runtime classes, so grepping just that jar is misleading.) The same image had been
running fine for two weeks. A plain restart fixed it with no image change — treat it as a
transient failure during a contended cold start.

**Fix / diagnosis:**

```bash
# Is the API actually serving? This is the real question.
curl -s -o /dev/null -w '%{http_code}\n' https://dependency-track.simonrowe.dev/api/version
curl -s https://dependency-track.simonrowe.dev/api/v1/oidc/available    # must be: true

# Confirm from inside: only :9000 listening and not :8080 means this exact failure.
docker exec simonrowe-dev-monorepo-dependencytrack-apiserver-1 sh -c \
  'curl -fsS -o /dev/null http://localhost:8080/api/version; echo "8080 exit=$?"'
docker logs simonrowe-dev-monorepo-dependencytrack-apiserver-1 2>&1 | grep -E 'ServerConnector|NoClassDefFound'

# Remedy: a plain restart, then confirm Jetty bound 8080.
docker restart simonrowe-dev-monorepo-dependencytrack-apiserver-1
docker logs --since 5m simonrowe-dev-monorepo-dependencytrack-apiserver-1 2>&1 | grep ServerConnector
# want: Started oejs.ServerConnector{HTTP/1.1, (http/1.1)}{0.0.0.0:8080}
```

**What now prevents a repeat:** the healthcheck probes both ports —
`curl -fsS localhost:9000/health/ready && curl -fsS localhost:8080/api/version` — so this
failure now shows as `unhealthy`; and `scripts/monitor-prod.sh` both restarts unhealthy
containers (Docker will not) and independently probes
`https://dependency-track.simonrowe.dev/api/version` every minute. See
`docs/runbooks/prod-monitoring.md`.

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

Since 2026-07-30 the mirrored vulnerability data is itself a significant share of that size:
enabling OSV took the `VULNERABILITY` table from 371k to 637k rows (see "Zero vulnerabilities on
the dependency SBOMs" above). That part is bounded by what upstream publishes, unlike the metrics
partitions below, but it is a one-off step change worth knowing about when reading the numbers.

Watch the `dtrack` row's `Size` column over time. One operator reported unbounded growth from
~50 GB to ~500 GB in a month on a large portfolio; this deployment only tracks five projects so
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
   `DT_OIDC_TEAM_SYNCHRONIZATION: "true"`. Check under **Administration → Access Management**,
   in both **OpenID Connect Groups** and **Teams** — the mapping between the two is the part
   most often missing.

   a. **The OIDC group or its team mapping is missing.** Dependency-Track does **not** match
      claim values against team names, so a team called `DEV_PORTAL_ADMIN` on its own grants
      nothing. Three objects are required — see step 13 of
      [the Auth0 setup guide](../auth0-setup.md#dependency-track-single-sign-on-sso):

      ```text
      claim value  →  OpenID Connect Group  →  mapping  →  Team  →  permissions
      ```

      The **group** name is the one that must equal the `https://simonrowe.dev/roles` claim
      byte-for-byte, including case; the team name is arbitrary. Check all three at once
      rather than guessing, from the deploy directory:

      ```bash
      PW=$(grep '^DEPENDENCYTRACK_DB_PASSWORD=' .env | cut -d= -f2-)
      docker exec -e PGPASSWORD="$PW" simonrowe-dev-monorepo-langfuse-db-1 \
        psql -h 127.0.0.1 -U dtrack -d dtrack -c \
        'SELECT g."NAME" AS oidc_group, t."NAME" AS mapped_team,
                (SELECT count(*) FROM "TEAMS_PERMISSIONS" tp WHERE tp."TEAM_ID"=t."ID") AS perms
         FROM "MAPPEDOIDCGROUP" m
         JOIN "OIDCGROUP" g ON g."ID"=m."GROUP_ID"
         JOIN "TEAM" t ON t."ID"=m."TEAM_ID";'
      ```

      Zero rows means the group, the team or the mapping between them is absent. Note that
      Dependency-Track never auto-creates groups from claims it observes, so an empty
      `OIDCGROUP` table says nothing about whether the claim is arriving — rule this cause out
      first, then move to (b).

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
confirmation is checking the five projects directly:

Export the API key from your usual env store first (never paste it inline, never commit it):

```bash
export DEPENDENCYTRACK_API_KEY="<value from env store>"

for project in "simonrowe-dev/backend" "simonrowe-dev/frontend" "simonrowe-dev/backend-image" "simonrowe-dev/frontend-image" "simonrowe-dev/reviewer-image"; do
  echo "=== $project ==="
  curl -s -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
    "https://dependency-track.simonrowe.dev/api/v1/project/lookup?name=${project}&version=main" \
    | grep -o '"lastBomImport":[^,]*'
done
```

Expect a recent (non-null) `lastBomImport` timestamp for all five. A missing project, a `null`
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
underlying `syft`) and upload with `projectName=simonrowe-dev/backend-image`,
`simonrowe-dev/frontend-image`, or `simonrowe-dev/reviewer-image`:

```bash
cd ..  # back to the repo root if you ran the frontend block above
syft ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend:latest -o cyclonedx-json > backend-image-bom.json
curl -X POST "https://dependency-track.simonrowe.dev/api/v1/bom" \
  -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
  -F "autoCreate=true" \
  -F "projectName=simonrowe-dev/backend-image" \
  -F "projectVersion=main" \
  -F "bom=@backend-image-bom.json"

syft ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-reviewer:latest -o cyclonedx-json > reviewer-image-bom.json
curl -X POST "https://dependency-track.simonrowe.dev/api/v1/bom" \
  -H "X-Api-Key: ${DEPENDENCYTRACK_API_KEY}" \
  -F "autoCreate=true" \
  -F "projectName=simonrowe-dev/reviewer-image" \
  -F "projectVersion=main" \
  -F "bom=@reviewer-image-bom.json"
```

(`${DEPENDENCYTRACK_API_KEY}` must be exported from the usual env store first — never paste the
key inline into a command you might paste into a shared terminal or commit to a file.)

## Restoring after data loss

Dependency-Track's own state (projects, findings, metrics history) is a **derived cache**: it can
always be rebuilt from source (the five SBOMs) plus a re-run of the `publish` workflow. It is
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

# Re-populate by re-running the last publish workflow (re-uploads all five SBOMs)
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
