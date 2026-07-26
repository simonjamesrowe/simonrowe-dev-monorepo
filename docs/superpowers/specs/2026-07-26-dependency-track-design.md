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
| `dependencytrack-apiserver` | `dependencytrack/apiserver` | JVM API + analysis engine |
| `dependencytrack-frontend` | `dependencytrack/frontend` | Static SPA |

Both listen on `8080` inside their own containers. Neither publishes a host port — all
ingress is through nginx, matching how Portainer is handled today.

**Memory tuning.** Dependency-Track's documented recommendation is ~4.5 GB for the API
server. The Pi cannot spare that alongside MongoDB, Elasticsearch, Kafka, ClickHouse, MinIO,
Redis, Postgres, two Langfuse containers, searxng, Alloy, Portainer and the application
itself. The API server therefore gets an explicit JVM heap cap and a container `mem_limit`
— the first resource limits in this compose file, which currently sets none.

Actual values are sized against real free memory on the Pi during implementation, not
guessed here. The starting point is a ~1.5 GB heap under a 2 GB limit.

The trade-off is accepted and explicit: slower analysis, a much slower initial vulnerability
database sync, and a risk that memory pressure causes the kernel OOM-killer to target a
different container (Elasticsearch is the most likely victim, being the next-largest JVM).

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
| nginx refactor breaks the site and Portainer with no SSH recovery | **High** | `nginx -t` validation; full OrbStack dry-run before deploying |
| Memory pressure OOM-kills another container | **High** | Explicit heap cap and `mem_limit`; size against real `free -m`; verify all containers healthy after the first NVD sync |
| OIDC misconfiguration locks out the UI | Medium | Local `admin` break-glass account retained |
| First vulnerability-database sync saturates the Pi for hours | Medium | Deploy at a quiet time; expect degraded site performance during the initial sync |
| `langfuse-db` becomes a shared point of failure | Medium | Accepted; documented here and in `CLAUDE.md` |
| Silent SBOM upload failures | Low | Explicit logging and job summary |

## Verification checklist

These must be confirmed against current upstream documentation during implementation rather
than assumed:

- [ ] `dependencytrack/apiserver` and `dependencytrack/frontend` publish `linux/arm64`
      images, and the current stable version is identified.
- [ ] The exact environment variable names for OIDC on both containers, and for the heap cap
      on the API server.
- [ ] The exact Auth0 callback path Dependency-Track's frontend expects.
- [ ] The correct Postgres connection environment variables and the minimum supported
      Postgres version (must be satisfied by postgres:15).
- [ ] The official SBOM upload GitHub Action and its current version.
- [ ] The `@cyclonedx/cyclonedx-npm` package name, version and invocation.
- [ ] The real free memory on the Pi, before choosing heap and limit values.

## Documentation to update on completion

- `docs/auth0-setup.md` — a Dependency-Track SSO section mirroring the Langfuse one.
- `CLAUDE.md` — the new hostname, the `langfuse-db` coupling, and removal of the nginx
  restart gotcha once the resolver fix lands.
- A runbook in `docs/runbooks/` covering rotating the API key, re-uploading SBOMs by hand,
  and recovering via the break-glass admin account.
