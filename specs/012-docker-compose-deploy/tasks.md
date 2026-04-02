# Tasks: Docker Compose Local Deployment with Pinggy and Nginx Reverse Proxy

**Input**: Design documents from `/specs/012-docker-compose-deploy/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: No automated tests — this is an infrastructure feature validated via manual testing (docker compose, curl, browser).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup

**Purpose**: Create directory structure and verify prerequisites

- [x] T001 Create `config/nginx/` directory for reverse proxy configuration
- [x] T002 Verify existing configuration files exist: `config/otel/otel-collector-config.yaml`, `frontend/nginx.conf`

---

## Phase 2: Foundational (CI/Build Changes)

**Purpose**: Update the frontend Docker build to support `VITE_API_BASE_URL` as a build argument — MUST complete before user stories since GHCR images need to be rebuilt

**CRITICAL**: No user story work can begin until this phase is complete

- [x] T003 [P] Add `VITE_API_BASE_URL` and `VITE_GA_MEASUREMENT_ID` as build arguments in `Dockerfile.frontend` alongside existing `VITE_RECAPTCHA_SITE_KEY`
- [x] T004 [P] Update `.github/workflows/publish.yml` to pass `VITE_API_BASE_URL=https://api.simonrowe.dev`, `VITE_RECAPTCHA_SITE_KEY`, and `VITE_GA_MEASUREMENT_ID` as build args to the frontend Docker build step

**Checkpoint**: Frontend Docker image can now be built with the correct API URL baked in

---

## Phase 3: User Story 2 - Domain-Based Routing via Nginx Reverse Proxy (Priority: P1)

**Goal**: Create an nginx reverse proxy that routes traffic based on hostname to frontend or backend containers, including WebSocket upgrade support for the chat endpoint.

**Independent Test**: `docker run --rm -v $(pwd)/config/nginx/nginx-proxy.conf:/etc/nginx/conf.d/default.conf nginx:alpine nginx -t` should validate config syntax.

### Implementation for User Story 2

- [x] T005 [US2] Create nginx reverse proxy configuration at `config/nginx/nginx-proxy.conf` with two server blocks: (1) `simonrowe.dev` and `www.simonrowe.dev` proxying to `http://frontend:80`, (2) `api.simonrowe.dev` proxying to `http://backend:8080` with `proxy_http_version 1.1`, `Upgrade`, and `Connection` headers for WebSocket support at `/ws/chat`. Use contract at `specs/012-docker-compose-deploy/contracts/nginx-proxy.conf` as reference.

**Checkpoint**: Nginx reverse proxy config created and syntax-validated

---

## Phase 4: User Story 1 - Launch Full Stack Locally (Priority: P1) + US4 (Environment) + US5 (Persistence)

**Goal**: Rewrite `docker-compose.prod.yml` to orchestrate all 8 services with proper dependencies, health checks, environment configuration, named volumes, and restart policies. This phase combines US1 (full stack), US4 (environment config), and US5 (data persistence) since they all modify the same file.

**Independent Test**: `docker compose -f docker-compose.prod.yml config` should validate the compose file. `docker compose -f docker-compose.prod.yml up -d` should start all services.

### Implementation for User Story 1 / US4 / US5

- [x] T006 [US1] Rewrite `docker-compose.prod.yml` with all services per the contract at `specs/012-docker-compose-deploy/contracts/docker-compose.prod.yml`:
  - **Infrastructure**: MongoDB 8, Kafka 7.8.0 (KRaft), Elasticsearch 8.17.0 — no exposed ports, named volumes (`mongodb-data`, `kafka-data`, `elasticsearch-data`), health checks, `restart: unless-stopped`
  - **Backend**: `ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend:latest` with `env_file: ./backend/.env` for secrets, hardcoded overrides for service discovery (`SPRING_DATA_MONGODB_URI`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `SPRING_ELASTICSEARCH_URIS`), `CORS_ALLOWED_ORIGINS=https://simonrowe.dev,https://www.simonrowe.dev`, OTel config, `backend-uploads` named volume, depends on MongoDB+Kafka+Elasticsearch healthy, health check on management port 8081
  - **Frontend**: `ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-frontend:latest`, depends on backend healthy, health check on port 80
  - **Nginx**: `nginx:alpine` with bind mount `./config/nginx/nginx-proxy.conf:/etc/nginx/conf.d/default.conf:ro`, depends on frontend+backend healthy, health check
  - **Pinggy**: `pinggy/pinggy` with command `ssh -p 443 -R0:nginx:80 -o StrictHostKeyChecking=no ${PINGGY_TOKEN}@pro.pinggy.io`, `env_file: ./backend/.env` for token, depends on nginx healthy
  - **OTel Collector**: existing config bind mount, port 4317 exposed
  - **All containers**: `restart: unless-stopped`
  - **Volumes section**: `mongodb-data`, `kafka-data`, `elasticsearch-data`, `backend-uploads`

**Checkpoint**: Full stack starts with `docker compose -f docker-compose.prod.yml up -d`, all containers healthy, data persists across restarts

---

## Phase 5: User Story 3 - Public Access via Pinggy Tunnel (Priority: P1)

**Goal**: Verify Pinggy tunnel configuration connects correctly to the nginx reverse proxy using the Pro tier with custom domain authentication.

**Independent Test**: After `docker compose up -d`, check `docker compose logs pinggy` for successful tunnel establishment. Access `https://simonrowe.dev` from an external browser.

**Note**: The Pinggy configuration is already included in the docker-compose.prod.yml from Phase 4 (T006). This phase is a validation checkpoint.

### Implementation for User Story 3

- [x] T007 [US3] Validate Pinggy tunnel configuration by running `docker compose -f docker-compose.prod.yml config` and confirming the pinggy service correctly references `${PINGGY_TOKEN}@pro.pinggy.io` with `-R0:nginx:80` and depends on nginx health

**Checkpoint**: Pinggy tunnel connects and `https://simonrowe.dev`, `https://www.simonrowe.dev`, and `https://api.simonrowe.dev` are accessible externally

---

## Phase 6: User Story 6 - Cloudflare DNS Configuration (Priority: P2)

**Goal**: Provide clear DNS setup guidance for Cloudflare so that `simonrowe.dev`, `www.simonrowe.dev`, and `api.simonrowe.dev` resolve to the Pinggy tunnel.

**Independent Test**: After DNS records are set, `dig simonrowe.dev` resolves to the Pinggy CNAME target.

### Implementation for User Story 6

- [x] T008 [US6] DNS guidance is documented in `specs/012-docker-compose-deploy/quickstart.md` — no additional files needed. Verify quickstart.md contains Cloudflare CNAME setup instructions for `@`, `www`, and `api` subdomains with DNS-only mode (grey cloud).

**Checkpoint**: DNS documentation is complete and accurate

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Final validation and cleanup

- [x] T009 Validate `docker-compose.prod.yml` syntax with `docker compose -f docker-compose.prod.yml config`
- [x] T010 Verify `.dockerignore` contains appropriate patterns for the project (check existing file, append missing patterns if needed)
- [x] T011 Run quickstart.md validation: confirm all documented commands, URLs, and troubleshooting steps are accurate

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: No dependencies — can run in parallel with Phase 1
- **US2 - Nginx Config (Phase 3)**: No dependencies on Phase 2 — can run in parallel with Phase 2
- **US1/US4/US5 - Docker Compose (Phase 4)**: Depends on Phase 1 (directory exists) and Phase 3 (nginx config exists)
- **US3 - Pinggy Validation (Phase 5)**: Depends on Phase 4 (compose file written)
- **US6 - DNS Docs (Phase 6)**: No dependencies — can run anytime
- **Polish (Phase 7)**: Depends on all previous phases

### User Story Dependencies

- **User Story 2 (Nginx)**: Independent — first to implement (config file only)
- **User Story 1 (Full Stack)**: Depends on US2 (nginx config must exist for compose file reference)
- **User Story 4 (Env Config)**: Implemented within US1 (same file)
- **User Story 5 (Persistence)**: Implemented within US1 (same file)
- **User Story 3 (Pinggy)**: Depends on US1 (compose file must include pinggy service)
- **User Story 6 (DNS)**: Independent — documentation only

### Within Each User Story

- Config files before compose file
- Compose file before validation
- All validations after compose file is written

### Parallel Opportunities

- T001 and T002 can run in parallel (Setup)
- T003 and T004 can run in parallel (Foundational — different files)
- Phase 2 (T003, T004) and Phase 3 (T005) can run in parallel (different files)
- T008 (DNS docs) can run at any time

---

## Parallel Example: Phases 2 and 3

```bash
# These can all run in parallel (different files):
Task: "T003 - Add VITE_API_BASE_URL build arg in Dockerfile.frontend"
Task: "T004 - Update publish.yml with build args"
Task: "T005 - Create nginx-proxy.conf"
```

---

## Implementation Strategy

### MVP First (User Stories 1 + 2)

1. Complete Phase 1: Setup (create directory)
2. Complete Phase 2: Foundational (Dockerfile + CI) — in parallel with Phase 3
3. Complete Phase 3: US2 (nginx config)
4. Complete Phase 4: US1/US4/US5 (docker-compose.prod.yml)
5. **STOP and VALIDATE**: `docker compose -f docker-compose.prod.yml config` passes

### Full Delivery

1. Complete MVP above
2. Complete Phase 5: US3 (Pinggy validation)
3. Complete Phase 6: US6 (DNS documentation review)
4. Complete Phase 7: Polish

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- US4 (Environment) and US5 (Persistence) are embedded in the US1 docker-compose task since they all modify the same file
- No automated tests — validation is via `docker compose config`, manual startup, and browser testing
- Commit after each phase or logical group
- Total tasks: 11
