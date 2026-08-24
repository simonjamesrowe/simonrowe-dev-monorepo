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
  `langfuse.simonrowe.dev → langfuse:3000`, `temporal.simonrowe.dev → temporal-ui:8080`,
  `dependency-track.simonrowe.dev → dependencytrack-frontend:8080`
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
- **`software-factory`** (formerly `reviewer-api` + a `temporal-reviewer-worker` host service) is
  one container running the GitHub webhook receiver and the Temporal code-review worker in one JVM,
  with `git` and a pinned Claude Code binary baked into the image. There are no host prerequisites
  and no systemd unit — it is reconciled by `docker compose up -d` like everything else.
  Only `POST /webhooks/github` is routed by nginx (exact-match `location =`); the internal
  `/api/reviews` endpoints are unrouted *and* token-protected. It deliberately has no `env_file`.
  A container can be `healthy` while having registered no Temporal poller, in which case webhooks
  return `202` and nothing ever reviews — check pollers on the `code-review` task queue, not just
  the healthcheck. See `docs/runbooks/software-factory.md`.
  The same container also hosts the `cve-fix` task queue and, behind `FACTORY_CVEFIX_ENABLED`
  (default `false`), a **paused-by-default** 24-hour Temporal schedule (`cve-fix-daily`) declared in
  code so a deploy reconciles it; enabling the flag deliberately does not start opening pull
  requests until an operator unpauses it after a dry run. That flow builds nothing locally — the
  agent has no `Bash` tool and the image carries no Gradle/Node/Docker, so CI is the only build
  environment and the repair loop is a CI poll. It adds no HTTP route. See
  `docs/runbooks/cvefix.md`.
- **Self-healing watchdog:** `scripts/monitor-prod.sh` runs from cron every minute
  (installed by `scripts/install-prod-monitoring.sh`, logs to `/var/log/prod-health/monitor.log`).
  It checks the site, then every container's Docker health, then each public hostname, and
  remediates at the narrowest level that fits. **Test changes to it with `DRY_RUN=1` and a
  throwaway `STATE_DIR`** — every remediation path shells out to `docker compose`, so merely
  running it performs real restarts and can recreate containers if the compose file has been
  edited since the last deploy. Key fact it exists to work around: **Docker never restarts an
  `unhealthy` container** — `restart: unless-stopped` only fires on process *exit*, so a
  container that is up but failing its healthcheck stays broken forever unless something
  external restarts it. See `docs/runbooks/prod-monitoring.md`.
- **A `healthy` container is not proof a service is serving.** On 2026-08-14 a host reboot
  cold-started all 21 containers at once and two came back broken *and invisible* for 10 days:
  `dependencytrack-apiserver` reported `healthy` while its API port was dead (its healthcheck
  probed only the management port on 9000; the JVM survived a `NoClassDefFoundError` that
  killed the Jetty listener, so it never exited and was never restarted), and `langfuse` had
  **no healthcheck at all** while every page 500'd on a broken `next-auth` module — its API
  routes stayed 200, so a health-endpoint probe would have missed it. Both were fixed by a
  plain `docker restart`; neither was an image problem. `langfuse`, `langfuse-worker`,
  `temporal-ui` and `dependencytrack-frontend` now have healthchecks, and the apiserver probes
  its API port too. **After any reboot, curl the public hostnames — do not trust a green
  `docker compose ps`.**
- **The kernel memory cgroup is disabled, so every `mem_limit` is unenforced.** `docker info`
  warns `No memory limit support` and `docker stats` reports `0B / 0B`. The Raspberry Pi
  *firmware* prepends `cgroup_disable=memory`; it is in `/proc/cmdline` but in **no file under
  `/boot`**, so trying to delete it from `cmdline.txt` (as older docs advised) cannot work —
  you *append* `cgroup_enable=memory cgroup_memory=1` instead, which is what
  `scripts/enable-memory-cgroup.sh --apply` does. Needs a planned reboot. Second-order effect
  while it is off: JVMs size their heap from the host's 15.84GiB rather than their container
  limit (Dependency-Track's `-XX:MaxRAMPercentage=80.0` implies a ~12.7GiB heap against a
  declared `mem_limit: 2g`). The compose file now declares limits/reservations for 17 services,
  sized with ~2x headroom over measured PSS; they are inert until that reboot, and applying
  them recreates ~17 containers — so do it in the same maintenance window.
- **The backend healthcheck budget is tight for a reason.** `/actuator/health` aggregates
  Elasticsearch, Kafka, Mongo, mail and SSL, and the Kafka indicator builds a fresh AdminClient
  per call: measured ~9s on the Pi while returning `{"status":"UP"}`. It previously allowed only
  4-5s, so it marked a healthy backend unhealthy, which made `up -d` abort with
  "backend is unhealthy" and strand `frontend` in `created` (502 on www). That is the real cause
  of the "just re-run restart-prod.sh" folklore. Now `interval: 30s`/`timeout: 25s`. To rescue a
  stranded frontend without waiting on the dependency gate:
  `docker compose -f docker-compose.prod.yml up -d --no-deps frontend`.
- **Recover a downed/partial stack** from the deploy directory: `docker compose -f docker-compose.prod.yml up -d`
  (reconciles containers stuck in `created`, respecting `depends_on` ordering). Minimal alternative:
  `docker start simonrowe-dev-monorepo-langfuse-1 && docker start simonrowe-dev-monorepo-nginx-1`.
- Containers left in Docker `created` state (built but never started) after an interrupted
  `docker compose up` are a common failure mode — a stranded `frontend` means `502` on www.
  (The old explanation, "nginx keeps serving with a stale cached upstream IP", no longer
  applies: since `62d26cc` nginx resolves upstreams per request, so a `502` here means the
  upstream really is not running. `monitor-prod.sh` now detects `created`/`exited` containers
  and reconciles the stack, and no longer bounces nginx for DNS reasons.)
- **Pinggy tunnel:** one `PINGGY_TOKEN` = one active tunnel. If another host still holds it you get
  `A tunnel with the same token is already active`; reclaim it by setting `PINGGY_TOKEN=<token>+force`
  (the `+force` suffix terminates the stale session). The token maps to the `*.simonrowe.dev` custom domain.
- **Running prod on macOS/OrbStack for testing:** the backend bind-mounts the docker CLI via
  `DOCKER_BINARY_PATH`/`DOCKER_PLUGINS_PATH`, whose compose defaults (`/usr/bin/docker`,
  `/usr/libexec/docker/cli-plugins`) don't exist on macOS — set them in `.env` to
  `/opt/homebrew/bin/docker` and `~/.docker/cli-plugins`. nginx/portainer publish no host ports
  (all ingress is via the pinggy tunnel), so there are no conflicts with other local stacks.

## Recent Changes
- 033-sonarqube-static-analysis: SonarCloud analysis moved out of the `backend` job into its
  own `sonar` job (`needs` all three build jobs, `fetch-depth: 0`, `continue-on-error: true`,
  runs `./gradlew classes testClasses sonar` — no test re-run). `SONAR_TOKEN` moved to
  job-level `env:` so the `if: env.SONAR_TOKEN != ''` guard can actually evaluate true; it
  never could before, so the analysis had never run once. **A tokenless `sonar` invocation
  takes ~10 minutes and then fails hard**, so that guard is load-bearing. Frontend gains
  `@vitest/coverage-v8` + `test:coverage` + a blocking `npm run lint` step (exits 0 today:
  5 `react-refresh` warnings, 0 errors); `software-factory` gains JaCoCo **report only, no
  floor**. `sonar.coverage.exclusions` hand-mirrors `backend`'s nine `jacocoExcludes` entries,
  translated from JaCoCo's class-file dialect to Sonar's source-file dialect — keep the two
  lists in step or the coverage percentages disagree. Frontend has 58 tests in
  `frontend/tests` and **9 co-located under `frontend/src`**, so `sonar.sources`/`sonar.tests`
  deliberately overlap and are disambiguated by `sonar.exclusions` +
  `sonar.test.inclusions`. Gate is advisory (`sonar.qualitygate.wait` unset). See
  `docs/runbooks/static-analysis.md`.
- 030-langfuse-sessions-content-evals: `chat-turn` Micrometer observation carries `session.id` +
  `langfuse.trace.input`/`.output` (fixes empty Sessions and shallow traces);
  `LangfuseContentObservationFilter` writes prompt/completion span attributes (Spring AI's
  `log-prompt`/`log-completion` only log, they never set attributes); `LangfuseScoreClient`
  posts guardrail/tool-count/error/empty-answer scores; Alloy `ai_only` keep-list gains
  `langfuse.trace.name`; local Langfuse upgraded to v3 with an Alloy traces pipeline;
  `scripts/bootstrap-langfuse-evaluators.sh` provisions LLM-as-a-judge. Spring Boot 3.5.16,
  Spring AI 1.1.8, OTel instrumentation 2.30.0.
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
- The backend exposes a self-redeploy endpoint, `POST /api/admin/data-operations/redeploy`, which pulls the backend, frontend, nginx and software-factory images and restarts the backend container via an ephemeral `docker:cli` helper container (since the backend can't safely recreate its own running container). `software-factory` is restarted on its own with `--no-deps` and best-effort: it declares `temporal` and `mongodb` as `service_healthy` dependencies, and a failure appends `WARNING: could not restart software-factory` to the completion message rather than aborting the redeploy.
<!-- MANUAL ADDITIONS END -->

## Active Technologies
- Java 21 (backend), TypeScript 5.x / React 19 (frontend) + Spring Boot 3.5.9 (web, security OAuth2 resource server, data-mongodb), `@auth0/auth0-react` (adds `loginWithPopup` usage), Lucide React `Heart` icon. No new dependencies. (029-favourite-news-events)
- MongoDB — new `favourites` collection (record + `@Document`, unique compound index on `userId,type,contentId`). Existing `aggregated_articles` / `aggregated_events` unchanged. (029-favourite-news-events)
- Static analysis: SonarQube Cloud (`org.sonarqube` 6.0.1.5171, project key `simonjamesrowe_simonrowe-dev-monorepo`), JaCoCo 0.8.12 on `backend` (0.78 floor) and `software-factory` (report only), `@vitest/coverage-v8` ^3.0.0 for frontend LCOV, ESLint 9 in CI. No persistence. (033-sonarqube-static-analysis)

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
<!-- SPECKIT END -->
