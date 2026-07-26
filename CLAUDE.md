# simonrowe-dev-monorepo Development Guidelines

Last updated: 2026-04-12

## Technology Stack

- **Backend**: Java 21, Spring Boot 3.5.x, Gradle, Spring Data MongoDB, Spring Kafka, Spring Data Elasticsearch, Spring Security (OAuth2 Resource Server)
- **Frontend**: TypeScript, React (latest stable), Vite, MDXEditor, Lucide React, react-markdown
- **Persistence**: MongoDB 8 (primary), Elasticsearch (search), Kafka (async messaging)
- **Auth**: Auth0 (OAuth2/JWT)
- **CSS**: Plain CSS with BEM naming, single `styles.css` file, CSS custom properties for theming

## Project Structure

```text
backend/           # Spring Boot application
  src/main/java/   # Java source (com.simonrowe.*)
  src/test/java/   # Tests with Testcontainers
  uploads/         # Media asset storage
frontend/          # React + Vite application
  src/             # TypeScript source
  tests/           # Vitest tests
scripts/           # Bash scripts for backup, restore, migration
```

## Commands

```bash
# Start/stop applications (sources env vars from .env files)
./scripts/start.sh                      # Start both backend and frontend together
./scripts/stop.sh                       # Stop both backend and frontend
./scripts/start-backend.sh              # Start backend only (port 8080)
./scripts/start-frontend.sh             # Start frontend only (port 5173)

# Tests
cd backend && ../gradlew test           # Run backend tests
cd frontend && npm test                 # Run frontend tests (vitest)

# Backup & Restore
./scripts/backup.sh                     # Create backup to /Users/simonrowe/backups/
./scripts/restore.sh                    # Restore latest backup

# Environment setup (run automatically by Conductor on workspace creation)
# Copies ~/workspace/simonjamesrowe/env to backend/.env and frontend/.env
```

## Code Style

- Java: Google Java Style Guide, enforced via Checkstyle
- TypeScript: Standard conventions, ESLint
- CSS: BEM naming, plain CSS with custom properties

## Key Design Decisions

- Blog tags/skills use MongoDB `@DBRef` references; admin API uses DTO pattern converting between `@DBRef` entities and string IDs for the frontend
- Admin CMS uses Lucide React icons for actions/status, right-side drawer for Media Library, two-column layout for blog editor
- Uploads served via Spring `ResourceHandlerRegistry` at `/uploads/**`, path configurable via `UPLOADS_PATH` env var (default: `uploads/` relative to backend CWD)
- `scripts/backup.sh` and `scripts/restore.sh` are the canonical data management scripts (legacy Strapi migration scripts retained for reference)

## Production Deployment

Production runs the full stack via `docker-compose.prod.yml` (project name `simonrowe-dev-monorepo`),
deployed from `~/workspace/simonjamesrowe/simonrowe-dev-monorepo` (needs a `.env` in that directory).
It is exposed to the internet by the `pinggy` service, which tunnels `nginx:80` out to Cloudflare
(Cloudflare → pinggy tunnel → the `nginx` container), so the stack can run on any Docker host.

- **Single `nginx:alpine` reverse proxy** (`config/nginx/nginx-proxy.conf`) fronts every public hostname:
  `www/simonrowe.dev → frontend:80`, `api.simonrowe.dev → backend:8080`,
  `console.simonrowe.dev → portainer:9000` (Portainer has **no** published port — only reachable through nginx),
  `langfuse.simonrowe.dev → langfuse:3000`, `dependency-track.simonrowe.dev → dependencytrack-frontend:8080`
  (with `/api/` routed to `dependencytrack-apiserver:8080`).
- **nginx resolves upstreams at runtime, not just at boot** (fixed in commit `62d26cc`): the proxy conf sets
  `resolver 127.0.0.11 valid=10s ipv6=off;` (Docker's embedded DNS) and every `proxy_pass` target is a variable
  (e.g. `set $upstream_frontend frontend; proxy_pass http://$upstream_frontend:80;`), which forces nginx to defer
  DNS resolution instead of caching it at startup. As a result, **nginx now boots regardless of which upstreams
  are running**, and simply returns `502` for any hostname whose upstream is down or not yet started — restarting
  nginx no longer risks taking the whole stack (including Portainer, which sits behind the same nginx) offline.
  Historical context: before this fix, the proxy conf used static `proxy_pass http://<name>` with no resolver, so
  nginx resolved all upstream hostnames once at startup and aborted (`host not found in upstream`) if any of
  them were not running — that failure mode is what older incident reports referring to a "nginx restart gotcha"
  describe; it no longer applies.
- **nginx's healthcheck must stay upstream-independent.** It hits `/healthz`, served by a `default_server`
  block in `config/nginx/nginx-proxy.conf` that proxies to nothing. Do not point it back at `/`: with no
  `default_server`, a `Host: localhost` request falls through to the first block (`simonrowe.dev`) and proxies
  to `frontend`, so a stopped frontend marked nginx unhealthy — and because `pinggy` waits on nginx being
  `service_healthy`, the tunnel never started and *every* public hostname, Portainer included, went offline.
  Adding `default_server` does not change which block serves the five public hostnames: `server_name` matching
  takes precedence, and the default block's `server_name _` cannot match a real `Host` header.
- **`langfuse-db` (Postgres) is now a shared dependency of two tools**: it hosts both the
  `langfuse` database and, since Dependency-Track was added, a `dtrack` database (see
  `docs/runbooks/dependency-track.md`). Stopping or restarting `langfuse-db` takes down both
  Langfuse and Dependency-Track, not just Langfuse.
- **Recover a downed/partial stack** from the deploy directory: `docker compose -f docker-compose.prod.yml up -d`
  (reconciles containers stuck in `created`, respecting `depends_on` ordering). Minimal alternative:
  `docker start simonrowe-dev-monorepo-langfuse-1 && docker start simonrowe-dev-monorepo-nginx-1`.
- Containers left in Docker `created` state (built but never started) after an interrupted `docker compose up`
  are a common failure mode: nginx keeps serving with a stale cached upstream IP → `502`.
- **Pinggy tunnel:** one `PINGGY_TOKEN` = one active tunnel. If another host still holds it you get
  `A tunnel with the same token is already active`; reclaim it by setting `PINGGY_TOKEN=<token>+force`
  (the `+force` suffix terminates the stale session). The token maps to the `*.simonrowe.dev` custom domain.
- **Running prod on macOS/OrbStack for testing:** the backend bind-mounts the docker CLI via
  `DOCKER_BINARY_PATH`/`DOCKER_PLUGINS_PATH`, whose compose defaults (`/usr/bin/docker`,
  `/usr/libexec/docker/cli-plugins`) don't exist on macOS — set them in `.env` to
  `/opt/homebrew/bin/docker` and `~/.docker/cli-plugins`. nginx/portainer publish no host ports
  (all ingress is via the pinggy tunnel), so there are no conflicts with other local stacks.

## Recent Changes
- 029-favourite-news-events: Added Java 21 (backend), TypeScript 5.x / React 19 (frontend) + Spring Boot 3.5.9 (web, security OAuth2 resource server, data-mongodb), `@auth0/auth0-react` (adds `loginWithPopup` usage), Lucide React `Heart` icon. No new dependencies.
- 028-chat-ontopic-web-search: Added Java 21 (backend only) + Spring Boot 3.5.x, Spring AI 1.1.4 (OpenAI SDK starter + `@Tool`),
- 027-mcp-page: Added TypeScript 5.x (frontend); Java 21 / Spring Boot 3.5.x (backend — MCP server config + ToolCallbackProvider) + React (latest stable), React Router v7, Vite, Vitest, Lucide React; Spring AI 1.1.4 `spring-ai-starter-mcp-server-webmvc` (existing)
  authoritative `fullResponse` + single initial-query send guard), contextual tool labels
  (dropped "Used 1 tool" expander), safe allowlisted link/image rendering in answers
  (`chat/linkPolicy.ts`, custom react-markdown `a`/`img` renderers, no `rehype-raw`),
  item-level deep links (`/experience?job=`/`?skillGroup=` via `useDrawer` + `useScrollToHash`,
  job/skill-group ids added to widget payloads), Playwright e2e (`frontend/e2e/`), and
  deterministic Langfuse bootstrap (`LANGFUSE_INIT_*` in `docker-compose.prod.yml`,
  `scripts/verify-langfuse-trace.sh`, `docs/runbooks/langfuse-observability.md`).

<!-- MANUAL ADDITIONS START -->
# Manual additions

> Maintained in simonjamesrowe/agent-setup — edit there.

- The `pinggy` tunnel is single-tenant per `PINGGY_TOKEN`: if another host still holds the tunnel, reclaim it by appending `+force` to the token value (`PINGGY_TOKEN=<token>+force`).
- On macOS, running the production compose file under OrbStack requires overriding `DOCKER_BINARY_PATH=/opt/homebrew/bin/docker` and `DOCKER_PLUGINS_PATH=~/.docker/cli-plugins`, since the compose defaults assume a Linux Docker install.
- There is a management-port mismatch between environments: `docker-compose.prod.yml` sets `MANAGEMENT_SERVER_PORT: 8081`, while `application.yml` defaults `management.server.port` to `8082`; local health checks should target `8082` unless an env override is in effect.
- The README's backup/restore instructions are stale: `scripts/create-backup.sh`, `scripts/restore-backup.sh`, and `scripts/migrate-strapi-data.js` no longer exist in the repo — use `scripts/backup.sh` and `scripts/restore.sh` instead.
- The backend exposes a self-redeploy endpoint, `POST /api/admin/data-operations/redeploy`, which pulls the backend, frontend, and nginx images and restarts the backend container via an ephemeral `docker:cli` helper container (since the backend can't safely recreate its own running container).
<!-- MANUAL ADDITIONS END -->

## Active Technologies
- Java 21 (backend), TypeScript 5.x / React 19 (frontend) + Spring Boot 3.5.9 (web, security OAuth2 resource server, data-mongodb), `@auth0/auth0-react` (adds `loginWithPopup` usage), Lucide React `Heart` icon. No new dependencies. (029-favourite-news-events)
- MongoDB — new `favourites` collection (record + `@Document`, unique compound index on `userId,type,contentId`). Existing `aggregated_articles` / `aggregated_events` unchanged. (029-favourite-news-events)

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
<!-- SPECKIT END -->
