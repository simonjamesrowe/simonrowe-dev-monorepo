# Implementation Plan: Docker Compose Local Deployment

**Branch**: `012-docker-compose-deploy` | **Date**: 2026-04-02 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/012-docker-compose-deploy/spec.md`

## Summary

Deploy the full stack locally using Docker Compose with an nginx reverse proxy for hostname-based routing (`simonrowe.dev` → frontend, `api.simonrowe.dev` → backend) and a Pinggy tunnel for public HTTPS access via the `*.simonrowe.dev` wildcard custom domain. Images are pulled from GHCR. All data stores use named volumes. The frontend makes direct API calls to `api.simonrowe.dev`, requiring CORS configuration and `VITE_API_BASE_URL` as a build argument.

## Technical Context

**Language/Version**: Nginx config (reverse proxy), Docker Compose YAML, Bash (scripts), Dockerfile modifications
**Primary Dependencies**: Docker, Docker Compose, nginx:alpine, pinggy/pinggy, GHCR images
**Storage**: MongoDB 8, Kafka 7.8.0, Elasticsearch 8.17.0 (all via named Docker volumes)
**Testing**: Manual verification — `docker compose ps`, curl, browser testing
**Target Platform**: macOS (local development), Docker Desktop
**Project Type**: Infrastructure/DevOps — no application code changes except build args and CI workflow
**Performance Goals**: All containers healthy within 5 minutes of startup
**Constraints**: Must work on macOS with Docker Desktop. Outbound port 443 required for Pinggy SSH tunnel.
**Scale/Scope**: Single developer local deployment. 8 containers total.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Monorepo with Separate Containers | PASS | Backend and frontend remain separate containers. Docker Compose is the orchestration mechanism. Images published to GHCR. Frontend nginx proxies `/api/` and `/uploads/`. |
| II. Modern Java & React Stack | PASS | No stack changes. `VITE_API_BASE_URL` build arg aligns with existing Vite env pattern. CORS config uses existing `CORS_ALLOWED_ORIGINS` env var. |
| III. Quality Gates | PASS | No application code changes — only infrastructure config. CI workflow change (adding build arg) is additive. |
| IV. Observability & Operability | PASS | OTel collector retained. Backend sends traces via OTLP. Prometheus metrics on separate actuator port. |
| V. Simplicity & Incremental Delivery | PASS | Minimal new files: one nginx config, updated docker-compose. No new abstractions. |
| VI. Admin CMS UX Standards | N/A | No UI changes. |
| VII. Backup & Restore | N/A | Backup scripts operate on host; Docker volumes don't affect them. |
| VIII. Shell Scripting Standards | N/A | No new shell scripts. |

**Post-Phase 1 re-check**: All principles remain satisfied. The nginx reverse proxy config is a new file but follows the simplest possible approach (two server blocks).

## Project Structure

### Documentation (this feature)

```text
specs/012-docker-compose-deploy/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0: Technology decisions
├── data-model.md        # Phase 1: Service topology and env vars
├── quickstart.md        # Phase 1: Developer guide
├── contracts/
│   ├── nginx-proxy.conf       # Target nginx reverse proxy config
│   └── docker-compose.prod.yml # Target docker-compose contract
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
# Files modified or created by this feature
docker-compose.prod.yml              # Modified: nginx, pinggy, restart policies, CORS, volumes
config/
├── nginx/
│   └── nginx-proxy.conf             # Created: hostname-based reverse proxy
└── otel/
    └── otel-collector-config.yaml   # Existing: unchanged
Dockerfile.frontend                  # Modified: added VITE_API_BASE_URL build arg
.github/workflows/publish.yml        # Modified: pass VITE_API_BASE_URL to frontend build
```

**Structure Decision**: Infrastructure-only changes. No new source directories. Two existing files modified (`docker-compose.prod.yml`, `Dockerfile.frontend`), one CI workflow updated, one new config file created (`config/nginx/nginx-proxy.conf`).

## Implementation Details

### 1. Nginx Reverse Proxy Configuration

Create `config/nginx/nginx-proxy.conf` with two server blocks:

- **`simonrowe.dev` / `www.simonrowe.dev`** → `proxy_pass http://frontend:80` — standard HTTP proxy headers
- **`api.simonrowe.dev`** → `proxy_pass http://backend:8080` — includes `proxy_http_version 1.1`, `Upgrade`, and `Connection` headers for WebSocket support at `/ws/chat`

See [contracts/nginx-proxy.conf](contracts/nginx-proxy.conf) for the target configuration.

### 2. Docker Compose Updates

Rewrite `docker-compose.prod.yml` to include:

- **All infrastructure services** (MongoDB, Kafka, Elasticsearch) with existing configs, plus `restart: unless-stopped`
- **Backend** with:
  - `env_file: ./backend/.env` for secrets
  - Hardcoded environment overrides for service discovery and CORS
  - `backend-uploads` named volume
  - `restart: unless-stopped`
- **Frontend** with `restart: unless-stopped` and health check
- **Nginx** (new): `nginx:alpine` with bind mount for `config/nginx/nginx-proxy.conf`, depends on frontend + backend healthy
- **Pinggy** (updated): `${PINGGY_TOKEN}@pro.pinggy.io` instead of `tcp@a.pinggy.io`, `-R0:nginx:80` targeting the reverse proxy, depends on nginx healthy, `env_file` for token
- **OTel collector** with `restart: unless-stopped`
- **New volume**: `backend-uploads`

See [contracts/docker-compose.prod.yml](contracts/docker-compose.prod.yml) for the target configuration.

### 3. Dockerfile.frontend Update

Add `VITE_API_BASE_URL` as a build argument alongside existing `VITE_RECAPTCHA_SITE_KEY`:

```dockerfile
ARG VITE_API_BASE_URL
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
```

Also add `VITE_GA_MEASUREMENT_ID` for Google Analytics support in production images.

### 4. GitHub Actions Publish Workflow Update

In `.github/workflows/publish.yml`, pass build args to the frontend Docker build:

```yaml
build-args: |
  VITE_API_BASE_URL=https://api.simonrowe.dev
  VITE_RECAPTCHA_SITE_KEY=${{ secrets.VITE_RECAPTCHA_SITE_KEY }}
  VITE_GA_MEASUREMENT_ID=${{ secrets.VITE_GA_MEASUREMENT_ID }}
```

This ensures GHCR images are built with the correct API URL baked in.

### 5. Cloudflare DNS Documentation

Add CNAME records in Cloudflare (DNS-only mode) for `@`, `www`, and `api` subdomains pointing to the Pinggy tunnel endpoint. Documented in [quickstart.md](quickstart.md).

## Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|------------|
| Pinggy tunnel drops | Service temporarily unavailable | `restart: unless-stopped` auto-recovers |
| GHCR images not available (first run) | Stack won't start | Document `docker login ghcr.io` and ensure CI runs first |
| Backend native image lacks `bash` for healthcheck | Healthcheck fails | Existing prod compose already uses `bash` — verified it works with buildpacks image |
| Cloudflare proxy mode causes TLS conflicts | Site unreachable | Document DNS-only mode requirement |
| `VITE_API_BASE_URL` not baked into image | Frontend API calls fail | CI workflow passes it as build arg; local builds can override |

## Complexity Tracking

No constitution violations. All changes follow the simplest approach.
