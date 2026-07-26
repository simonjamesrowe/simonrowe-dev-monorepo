# Dependency-Track in production — design

**Date:** 2026-07-26
**Status:** approved, pending implementation plan

## Goal

Run [OWASP Dependency-Track](https://dependencytrack.org/) at
`https://dependency-track.simonrowe.dev`, secured by the existing Auth0 tenant, and have
GitHub Actions publish SBOMs to it on every merge to `main` — for both the application
dependencies (Gradle, npm) and the published container images.

The value is continuous vulnerability visibility: Dependency-Track re-analyses stored SBOMs
against fresh vulnerability intelligence on its own schedule, so newly disclosed CVEs in
already-shipped dependencies surface without anyone re-running a build.

## Non-goals

- **No merge gating.** Nothing in CI fails because of a vulnerability finding.
- **No alerting.** Dashboard only, until the signal-to-noise ratio is understood.
- **No local environment.** Dependency-Track is added to `docker-compose.prod.yml` only, not
  to the local development stack. (This does not preclude running the *production* compose
  file locally on OrbStack as a pre-deploy rehearsal — that is a validation step, not a
  supported local environment.)
- **No per-commit history.** Four projects, fixed at version `main`, overwritten each merge.
- **No backup integration.** Dependency-Track state is a derived cache, fully rebuildable by
  re-uploading SBOMs. It stays out of `scripts/backup.sh`.

## Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Hosting | On the Pi, memory-tuned | Keeps everything in one stack; accepts slower analysis |
| Auth | Native OIDC inside Dependency-Track | True SSO; API keys remain independent for CI |
| Scope | 4 projects — backend, frontend, and both container images | Base-image OS CVEs matter as much as app dependencies |
| Upload trigger | Merge to `main` only, observational | Simplest useful thing; no CI coupling to Pi uptime |
| Database | Reuse the existing `langfuse-db` Postgres | Saves a container on a memory-constrained host |
| Versioning | Fixed version `main` | Portfolio stays at exactly 4 projects |
| nginx | Fix the boot landmine for all upstreams | Adding DT makes an existing risk worse; fix it now |

## Architecture

### Containers

Two new services in `docker-compose.prod.yml`:

| Service | Image | Notes |
|---|---|---|
| `dependencytrack-apiserver` | `dependencytrack/apiserver:5.0.3` | JVM API + analysis engine |
| `dependencytrack-frontend` | `dependencytrack/frontend:5.0.3` | Static SPA |

Both listen on `8080` inside their own containers. Neither publishes a host port — all
ingress is through nginx, matching how Portainer is handled today.

**Memory tuning.** Dependency-Track documents a 4 GB minimum heap. The Pi cannot spare that
alongside MongoDB, Elasticsearch, Kafka, ClickHouse, MinIO, Redis, Postgres, two Langfuse
containers, searxng, Alloy, Portainer and the application itself. The API server therefore
gets a container `mem_limit` — the first resource limit in this compose file, which
currently sets none. Because the image already defaults to `-XX:MaxRAMPercentage=90.0`, the
limit constrains the heap on its own without needing the bug-prone `EXTRA_JAVA_OPTIONS`
lever.

Concrete values are deliberately **not** fixed here. See "Memory — unresolved" below: the
gap between the documented 4 GB minimum and what the Pi can spare is a genuine go/no-go that
must be closed before implementation starts. If it cannot be closed, the hosting decision
is revisited.

The trade-off, assuming it is viable, is accepted and explicit: slower analysis, a much
slower initial vulnerability database sync, and a risk that memory pressure causes the
kernel OOM-killer to target a different container (Elasticsearch is the most likely victim,
being the next-largest JVM).

### Database

A new `dependencytrack` database and role inside the existing `langfuse-db` (postgres:15).

The `langfuse-db-data` volume already exists, so Postgres' first-run initialisation scripts
will never execute again. Creating the database therefore needs a one-shot
`dependencytrack-db-init` service that runs an idempotent `CREATE ROLE` / `CREATE DATABASE`
and exits, with `dependencytrack-apiserver` depending on its completion.

**Accepted risk:** `langfuse-db` becomes a single point of failure for two unrelated tools,
and future Langfuse upgrades that demand a specific Postgres version now also affect
Dependency-Track.

### nginx

A new `dependency-track.simonrowe.dev` server block using a single hostname split by path:

- `/api/*` and the OIDC discovery paths → `dependencytrack-apiserver:8080`
- everything else → `dependencytrack-frontend:8080`

The frontend container's `API_BASE_URL` is set to `https://dependency-track.simonrowe.dev`
so the SPA calls back through the same hostname.

**Boot landmine fix.** `config/nginx/nginx-proxy.conf` currently uses static
`proxy_pass http://<name>` with no `resolver`, so nginx resolves every upstream hostname at
startup and refuses to boot if any one of them is down — taking Portainer, and therefore the
only management UI, offline with it. Adding Dependency-Track takes this from four upstreams
to six.

This design fixes it: add `resolver 127.0.0.11 valid=10s;` (Docker's embedded DNS) and
convert every upstream to a variable `proxy_pass`:

```nginx
set $upstream_backend backend;
proxy_pass http://$upstream_backend:8080;
```

nginx then starts regardless of what is running and returns 502 only for the specific host
that is down.

**This is the highest-risk change in the whole piece.** It touches all four existing
upstreams, and a mistake takes the entire public site plus Portainer offline on a host with
no SSH access. Two required mitigations:

1. Validate with `nginx -t` inside the container before reloading.
2. Dry-run the complete production compose locally on OrbStack first (documented in
   `CLAUDE.md`, requires the `DOCKER_BINARY_PATH` / `DOCKER_PLUGINS_PATH` overrides).

One behavioural caveat to check per block rather than find-and-replace mechanically:
variable `proxy_pass` disables the URI-rewriting that static `proxy_pass` performs. The
Portainer block in particular carries CORS handling, WebSocket upgrade headers and an
`Origin` reset that must survive the change intact.

### Auth0

Dependency-Track's native OIDC support is enabled on both containers, reusing the tenant,
role and Post-Login Action already documented in `docs/auth0-setup.md`. No new Action is
needed — Dependency-Track slots into the pattern Langfuse already uses.

1. **New Auth0 application** named `Dependency-Track`. Callback, logout and web-origin URLs
   point at `https://dependency-track.simonrowe.dev`.
2. **Reuse the existing claim.** The `Add roles to tokens` Action already injects
   `https://simonrowe.dev/roles` into both the access and ID tokens. Dependency-Track's
   teams claim is pointed at that same namespaced claim.
3. **Reuse the existing role.** A Dependency-Track team named `DEV_PORTAL_ADMIN` is created
   and granted administrative permissions, so the existing role maps straight onto it with
   team synchronisation enabled.
4. **Extend the deny-list.** The new client ID is added to the Action's `protectedClientIds`
   array via a new Action secret, exactly as `LANGFUSE_CLIENT_ID` is today. Users without
   `DEV_PORTAL_ADMIN` are then rejected by Auth0 before ever reaching Dependency-Track.
5. **Keep break-glass access.** Dependency-Track's local `admin` account stays enabled with
   a strong password from the env file. Without it, a misconfigured OIDC setup means being
   locked out of an application on a host with no SSH.

`docs/auth0-setup.md` gains a Dependency-Track section mirroring the Langfuse one.

**Accepted risk:** because there is no local environment, the OIDC configuration is first
exercised in production. The OrbStack dry-run mitigates this partially; break-glass admin
access covers the rest.

### CI/CD

`.github/workflows/publish.yml` gains an `sbom` job that runs after both image jobs
complete, generating four SBOMs and uploading each to Dependency-Track.

| Dependency-Track project | Version | Source |
|---|---|---|
| `simonrowe-dev/backend` | `main` | `./gradlew cyclonedxBom` |
| `simonrowe-dev/frontend` | `main` | `@cyclonedx/cyclonedx-npm` |
| `simonrowe-dev/backend-image` | `main` | Syft against the pushed ghcr image |
| `simonrowe-dev/frontend-image` | `main` | Syft against the pushed ghcr image |

The Gradle CycloneDX plugin is **already configured** (`gradle/libs.versions.toml`, root
`build.gradle.kts`) and `ci.yml` already runs `cyclonedxBom`, uploading the result as a
build artifact that nothing consumes. That existing step is left alone; the new job in
`publish.yml` is what actually delivers the SBOM somewhere useful.

Frontend SBOM generation adds `@cyclonedx/cyclonedx-npm` as a dev dependency, run against
the committed `package-lock.json`.

Container SBOMs are produced by Syft against the images already pushed to ghcr, so they
describe exactly the artifact that ships — including the Paketo buildpack layers and the
`nginx:alpine` base.

**Authentication.** A `DEPENDENCYTRACK_API_KEY` GitHub secret, belonging to a
Dependency-Track team scoped to only `BOM_UPLOAD` and `PROJECT_CREATION_UPLOAD`. Projects
are auto-created on first upload.

**Failure handling.** The entire job is `continue-on-error: true`. A Pi that is offline,
rebooting, or saturated mid-analysis must never block a production deploy. The consequence
is that upload failures are silent by default, so each upload step logs its outcome
explicitly and the job summary states clearly whether all four SBOMs landed.

**Exposure.** GitHub Actions reaches the Dependency-Track API over the public internet via
Cloudflare → pinggy → nginx. The API is protected by the API key and the key is
minimally scoped, but the endpoint is internet-facing. This is the same exposure Portainer
and Langfuse already have.

## Risks

| Risk | Severity | Mitigation |
|---|---|---|
| DT needs 4 GB heap and the Pi cannot spare it — the design is unbuildable as specified | **Blocking** | Resolve before implementation: establish the real working floor and actual free memory. Fall back to hosting off the Pi if it cannot be closed |
| nginx refactor breaks the site and Portainer with no SSH recovery | **High** | `nginx -t` validation; full OrbStack dry-run before deploying |
| Memory pressure OOM-kills another container | **High** | `mem_limit` on the API server; size against real `free -m`; verify all containers healthy after the first vulnerability sync |
| v5 config uses `DT_*`, but most documentation and examples online still show `ALPINE_*`, which fail silently | Medium | Variable names verified against ADR 018; documented in this spec |
| OIDC misconfiguration locks out the UI | Medium | Local `admin` break-glass account retained |
| First vulnerability-database sync saturates the Pi for hours | Medium | Deploy at a quiet time; expect degraded site performance during the initial sync |
| `langfuse-db` becomes a shared point of failure | Medium | Accepted; documented here and in `CLAUDE.md` |
| Silent SBOM upload failures | Low | Explicit logging and job summary |

## Verified implementation facts

Confirmed against the Docker registry API, the GitHub releases API, the npm registry and
current upstream documentation on 2026-07-26.

### Versions

| Component | Pinned version |
|---|---|
| `dependencytrack/apiserver` | `5.0.3` |
| `dependencytrack/frontend` | `5.0.3` |
| `DependencyTrack/gh-upload-sbom` | `v4.1.0` |
| `@cyclonedx/cyclonedx-npm` | `6.0.0` |
| `anchore/sbom-action` | `v0.24.0` |
| CycloneDX Gradle plugin | `2.1.0` (already in `gradle/libs.versions.toml`) |

Both Dependency-Track images publish `linux/arm64` on all current tags, so the Pi is
supported.

**Do not use `latest`.** On `dependencytrack/apiserver` it currently resolves to `4.14.3`,
not the v5 line. Tags are pinned explicitly.

**The `bundled` single-container image is v4-only** — its highest tag is `4.14.3`. Choosing
v5 means the two-container split is mandatory, not a preference.

v5.0.0 reached GA on 2026-06-07; 5.0.3 followed on 2026-07-20. v4.14.x remains maintained
in parallel with an end-of-support around December 2026. There is no in-place v4→v5 upgrade
path, which is the main reason a greenfield install starts on v5.

### Database

v5 is **PostgreSQL-only** (H2, MySQL and SQL Server were all dropped) and requires
**PostgreSQL 14+**. The existing `langfuse-db` runs postgres:15, which satisfies this.

### Configuration variables

⚠️ **The API server's variable names changed completely in v5.** [ADR 018](https://github.com/DependencyTrack/dependency-track/blob/main/docs/adr/018-dissolve-alpine-config.md)
replaced Alpine config with MicroProfile Config, renaming every `ALPINE_*` variable to
`DT_*`. Most Dependency-Track material online — including much of the official docs site —
still shows the v4 names, which silently do nothing on v5.

| Purpose | v5 variable |
|---|---|
| OIDC enabled | `DT_OIDC_ENABLED` |
| Issuer | `DT_OIDC_ISSUER` |
| Client ID | `DT_OIDC_CLIENT_ID` |
| Username claim | `DT_OIDC_USERNAME_CLAIM` |
| User provisioning | `DT_OIDC_USER_PROVISIONING` |
| Team synchronisation | `DT_OIDC_TEAM_SYNCHRONIZATION` |
| Teams claim | `DT_OIDC_TEAMS_CLAIM` |
| Default teams | `DT_OIDC_DEFAULT_TEAMS` (note the word-order change from v4's `ALPINE_OIDC_TEAMS_DEFAULT`) |

The **frontend** variables are unchanged between v4 and v5: `API_BASE_URL`, `OIDC_ISSUER`,
`OIDC_CLIENT_ID`, `OIDC_SCOPE`, `OIDC_FLOW`, `OIDC_LOGIN_BUTTON_TEXT`. There is no frontend
enable flag — OIDC activates when issuer, client ID and scope are all truthy *and* the API
server reports OIDC as available.

### Three silent-failure traps

All three fail with no error message, which matters disproportionately here because there is
no local environment to debug in:

1. **`OIDC_SCOPE` must be set explicitly.** The frontend entrypoint assigns config via `jq`
   unconditionally, and an unset variable evaluates to `null` — so omitting it overwrites
   the shipped default with `null`, and because the login button requires a truthy scope,
   the button silently disappears. Set `OIDC_SCOPE=openid profile email`.
2. **The Auth0 issuer needs its trailing slash.** Dependency-Track does a strict string
   equality check against the discovery document, and Auth0 reports the issuer *with* a
   trailing slash. Use `https://<tenant>.auth0.com/`. A mismatch makes
   `/v1/oidc/available` return false and the login button never appears.
3. **Register the full callback path, not just the origin.** The frontend redirects to
   `https://dependency-track.simonrowe.dev/static/oidc-callback.html`. That exact path goes
   in Auth0's Allowed Callback URLs; the bare origin goes in Allowed Web Origins and Allowed
   Logout URLs. The official docs' Auth0 section understates this.

Auth0 application type is **Single Page Application**.

### Claims

Since v4.3.0 Dependency-Track validates the **ID token** and prefers it over the
`/userinfo` endpoint. The existing `Add roles to tokens` Action already sets the custom
claim on both the ID and access tokens, so Auth0's opaque access tokens are not a problem
here.

`DT_OIDC_TEAMS_CLAIM` is set to `https://simonrowe.dev/roles`. The Dependency-Track team
name **must match the claim value exactly, including case**, so the team is named
`DEV_PORTAL_ADMIN`. `DT_OIDC_USERNAME_CLAIM` is set to `email`.

### Memory — resolved

The 4 GB figure that shaped the original design **no longer applies**. It was a hard startup
gate in the API server (`RequirementsVerifier`), removed in 4.14.0 by
[PR #5058](https://github.com/DependencyTrack/dependency-track/pull/5058): "the previous
system requirements are no longer accurate."

The requirement moved in three steps, which is why stale figures are everywhere:

| Version | Documented minimum |
|---|---|
| 4.13 and earlier | 4.5 GB RAM, plus a hard `-Xmx4G` startup gate |
| 4.14.x | 2 GB RAM, gate removed |
| 5.0.x | 2 GB / 4 cores starting point; below 1 GB "unlikely to sustain any meaningful load" |

The upstream v5 Helm chart defaults to 2Gi and its quickstart runs the API server at 1Gi. The
API server therefore gets `mem_limit: 2g` — at the documented starting point, not below a
minimum.

The image sets `-XX:MaxRAMPercentage=80.0` with no fixed `-Xmx`, so the heap tracks the
container limit and the remaining 20% covers off-heap memory, thread stacks and the OS.
`EXTRA_JAVA_OPTIONS` is deliberately not used: it has a history of argument-parsing bugs, and
a fixed `-Xmx` equal to the cgroup limit leaves nothing for non-heap memory.

Note the Docker Hub overview page for `dependencytrack/apiserver` still shows the v4-era
"4.5GB minimum" text while serving v5 images. It is stale.

### Postgres is the real constraint

Upstream's v5 production guide asks for 8 GB / 4 cores for the database, says not to go below
4 GB / 2 cores "even for evaluation workloads", and says to run it on a **dedicated host**
because co-locating it with the API server makes them compete for CPU, memory and I/O.

This design co-locates it on a shared Pi Postgres that also serves Langfuse — precisely what
that guidance warns against. Accepted for a four-project portfolio, with two things to watch
after deployment:

- **Disk growth is unbounded by default.** `DEPENDENCYMETRICS_*` uses daily partitions; one
  operator saw Postgres grow ~50 GB → ~500 GB in a month on a large portfolio
  ([discussion #6711](https://github.com/DependencyTrack/dependency-track/discussions/6711)).
  Four projects should be negligible, but the Pi's disk is finite — check a week in, not just
  on day one.
- **CPU contention** between Postgres, Elasticsearch and the API server on four ARM cores is
  the likeliest cause of the site feeling slow.

**Honest caveat:** no v5 result on Raspberry Pi–class hardware has been published by anyone
outside the project. The 1Gi/2Gi figures are maintainer defaults, not independently
validated.

### Also unverified

- [ ] The exact `gh-upload-sbom@v4.1.0` input schema (the v3 schema is known; v4 may differ).
- [ ] v5 API key permission names and the upload endpoint verb.
- [ ] First-run vulnerability mirror size, duration on ARM, and which sources can be
      disabled to reduce it.

### Frontend SBOM command

```bash
npx @cyclonedx/cyclonedx-npm@6 \
  --package-lock-only \
  --spec-version 1.6 \
  --output-format JSON \
  --output-file frontend-bom.json
```

`--package-lock-only` avoids needing `node_modules`. Note that `--omit dev` only applies
automatically when `NODE_ENV=production`, so dev dependency inclusion must be set
deliberately.

## Documentation to update on completion

- `docs/auth0-setup.md` — a Dependency-Track SSO section mirroring the Langfuse one.
- `CLAUDE.md` — the new hostname, the `langfuse-db` coupling, and removal of the nginx
  restart gotcha once the resolver fix lands.
- A runbook in `docs/runbooks/` covering rotating the API key, re-uploading SBOMs by hand,
  and recovering via the break-glass admin account.
