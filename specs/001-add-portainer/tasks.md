# Tasks: Add Portainer Container Management Console

**Input**: Design documents from `/specs/001-add-portainer/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, quickstart.md

**Tests**: No tests requested. This is an infrastructure-only change verified through manual deployment.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup

**Purpose**: No setup phase needed — this feature modifies existing infrastructure files only. No new project initialization required.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before user stories can be verified

- [x] T001 [P] Add `portainer` service to `docker-compose.prod.yml` with `portainer/portainer-ce:latest` image, Docker socket mount (`/var/run/docker.sock:/var/run/docker.sock`), `portainer-data:/data` volume, `restart: unless-stopped`, no exposed ports, and health check using `wget --spider http://localhost:9000`
- [x] T002 [P] Add `portainer-data` named volume to the `volumes:` section of `docker-compose.prod.yml`
- [x] T003 [P] Add `console.simonrowe.dev` server block to `config/nginx/nginx-proxy.conf` with `proxy_pass http://portainer:9000`, standard proxy headers (`Host`, `X-Real-IP`, `X-Forwarded-For`, `X-Forwarded-Proto`), and WebSocket upgrade support (`Upgrade`, `Connection` headers, `proxy_http_version 1.1`)
- [x] T004 Add `portainer` to the `depends_on` list of the `nginx` service in `docker-compose.prod.yml` with `condition: service_healthy` so nginx waits for Portainer to be ready before starting

**Checkpoint**: Infrastructure is configured. All user stories can now be verified through deployment.

---

## Phase 3: User Story 1 & 2 - Access Portainer and Authenticate (Priority: P1)

**Goal**: Portainer is accessible at console.simonrowe.dev and secured with built-in authentication

**Independent Test**: Navigate to https://console.simonrowe.dev — should see Portainer login page. Create admin account on first visit. Verify unauthenticated access is denied.

### Implementation

- [x] T005 [US1] [US2] Verify DNS: confirm `console.simonrowe.dev` resolves correctly (check Cloudflare for CNAME record pointing to Pinggy endpoint; add record if missing)
- [ ] T006 [US1] [US2] Deploy updated stack with `docker compose -f docker-compose.prod.yml up -d` and verify Portainer container starts healthy
- [ ] T007 [US1] Navigate to `https://console.simonrowe.dev` and confirm Portainer login/setup page loads
- [ ] T008 [US2] Complete Portainer initial setup wizard: create admin account with secure password
- [ ] T009 [US2] Verify unauthenticated access is denied: open incognito browser to `https://console.simonrowe.dev` and confirm redirect to login page

**Checkpoint**: P1 stories complete — Portainer accessible and authenticated at console.simonrowe.dev

---

## Phase 4: User Story 3 - Manage Docker Containers (Priority: P2)

**Goal**: All production containers visible and manageable through Portainer UI

**Independent Test**: Log into Portainer, view all containers, view logs for one container, restart a non-critical service

### Implementation

- [ ] T010 [US3] Add the local Docker environment in Portainer (Environment > Local > Connect)
- [ ] T011 [US3] Verify all production containers are visible in the dashboard with correct status
- [ ] T012 [US3] Test container log viewing: select any container and verify logs stream correctly through the WebSocket proxy
- [ ] T013 [US3] Test container exec (console): open a shell in a container and verify it works through the WebSocket proxy

**Checkpoint**: P2 container management story complete — full container visibility and control

---

## Phase 5: User Story 4 - Portainer Survives Redeployments (Priority: P2)

**Goal**: Portainer data persists across stack restarts

**Independent Test**: Restart the Docker Compose stack, confirm admin account still works without re-creation

### Implementation

- [ ] T014 [US4] Restart the full stack with `docker compose -f docker-compose.prod.yml down && docker compose -f docker-compose.prod.yml up -d`
- [ ] T015 [US4] Navigate to `https://console.simonrowe.dev` and confirm existing admin credentials still work
- [ ] T016 [US4] Verify `portainer-data` volume exists and is not recreated: `docker volume inspect portainer-data`

**Checkpoint**: P2 persistence story complete — Portainer survives restarts

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Security verification and final validation

- [ ] T017 Verify Portainer port 9000 is NOT accessible directly on the host (only through nginx reverse proxy)
- [ ] T018 Run through quickstart.md post-deployment checklist in `specs/001-add-portainer/quickstart.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational (Phase 2)**: No dependencies — can start immediately. T001, T002, T003 are parallel (different files). T004 depends on T001.
- **User Stories 1 & 2 (Phase 3)**: Depend on all foundational tasks (T001-T004)
- **User Story 3 (Phase 4)**: Depends on Phase 3 (need auth setup to access Portainer)
- **User Story 4 (Phase 5)**: Depends on Phase 3 (need working Portainer to test persistence)
- **Polish (Phase 6)**: Depends on all user stories

### User Story Dependencies

- **User Story 1 & 2 (P1)**: Combined because they're tested together (access + auth). Depend on foundational infra only.
- **User Story 3 (P2)**: Depends on US1/US2 (must be logged in to manage containers)
- **User Story 4 (P2)**: Depends on US1/US2 (must have working Portainer to test persistence). Can run in parallel with US3.

### Parallel Opportunities

- T001, T002, T003 can all run in parallel (different files)
- US3 (Phase 4) and US4 (Phase 5) can run in parallel after Phase 3

---

## Parallel Example: Foundational Phase

```bash
# Launch all foundational config changes together:
Task: "Add portainer service to docker-compose.prod.yml"
Task: "Add portainer-data volume to docker-compose.prod.yml"
Task: "Add console.simonrowe.dev server block to config/nginx/nginx-proxy.conf"
# Then sequentially:
Task: "Add portainer to nginx depends_on in docker-compose.prod.yml"
```

---

## Implementation Strategy

### MVP First (User Stories 1 & 2)

1. Complete Phase 2: Foundational (T001-T004) — all config changes
2. Complete Phase 3: Deploy and verify access + auth (T005-T009)
3. **STOP and VALIDATE**: Portainer accessible and secured at console.simonrowe.dev
4. Deploy if ready — this alone provides full value

### Incremental Delivery

1. Foundational config changes → Deploy → Access + Auth verified (MVP!)
2. Container management verified → WebSocket features confirmed
3. Persistence verified → Production-ready
4. Security polish → Final validation

---

## Notes

- This is a 2-file, infrastructure-only change — total implementation is ~20 lines of YAML and ~15 lines of nginx config
- No application code changes, no tests to write, no builds to run
- Most "tasks" in Phases 3-5 are deployment verification steps, not code changes
- The actual code changes are concentrated in Phase 2 (T001-T004)
- Commit after completing Phase 2 (all config changes together)
