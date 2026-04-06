# Tasks: Production Health Monitoring & Lifecycle Scripts

**Input**: Design documents from `/specs/016-prod-health-scripts/`
**Prerequisites**: plan.md (required), spec.md (required), research.md

**Tests**: Not requested — no test tasks included.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

## Path Conventions

- **Scripts**: `scripts/` at repository root
- **Docker Compose**: `docker-compose.prod.yml` at repository root

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: No setup phase needed — all scripts are standalone bash files in an existing directory. No project initialization, no dependencies to install.

*(Skipped — proceed directly to User Stories)*

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: No foundational phase needed — each script is independent. No shared libraries, no base classes, no database schema. Each user story creates a self-contained script file.

*(Skipped — proceed directly to User Stories)*

---

## Phase 3: User Story 1 - Start and Stop Production Environment (Priority: P1) 🎯 MVP

**Goal**: Provide single-command scripts to start and stop the entire production stack, with health check waiting and status reporting.

**Independent Test**: Run `scripts/start-prod.sh` on the Raspberry Pi and verify all 9 services come up healthy. Run `scripts/stop-prod.sh` and verify all services shut down. Run start again to confirm idempotency.

### Implementation for User Story 1

- [x] T001 [P] [US1] Create production start script in `scripts/start-prod.sh` — resolve PROJECT_DIR, run `docker compose -f docker-compose.prod.yml up -d`, poll `docker compose ps` until all services are healthy or 3-minute timeout, print final status table, exit 0 on success / exit 1 on timeout
- [x] T002 [P] [US1] Create production stop script in `scripts/stop-prod.sh` — resolve PROJECT_DIR, run `docker compose -f docker-compose.prod.yml down`, report completion, preserve named volumes
- [x] T003 [US1] Make both scripts executable: `chmod +x scripts/start-prod.sh scripts/stop-prod.sh`

**Checkpoint**: At this point, the operator can start and stop the full production environment with single commands.

---

## Phase 4: User Story 2 — Pinggy Health Check via External Monitor (Priority: P1)

**Goal**: Detect when the Pinggy tunnel drops and automatically restart the container. Per research, Docker health checks on the Pinggy container are not feasible (image lacks tools, and `restart: unless-stopped` doesn't restart unhealthy containers anyway). The external monitor script (User Story 3) is the solution for both US2 and US3.

**Note**: Research Decision 2 concluded that adding a Docker HEALTHCHECK to the Pinggy service is not viable. The spec's US2 acceptance scenarios (detect unhealthy, auto-restart) are fulfilled by the monitor script in US3. This phase updates the spec note and ensures the monitor covers US2's requirements.

### Implementation for User Story 2

- [x] T004 [US2] Add a comment to the `pinggy` service in `docker-compose.prod.yml` documenting why no HEALTHCHECK is added (image lacks curl/wget, restart policy doesn't restart unhealthy containers — monitored externally by `scripts/monitor-prod.sh` instead)

**Checkpoint**: US2's requirements (detect tunnel drop, auto-restart) are addressed by the monitor in US3. The compose file documents the design decision.

---

## Phase 5: User Story 3 — External Reachability Monitor with Auto-Restart (Priority: P2)

**Goal**: A continuously running script that checks whether simonrowe.dev is reachable and restarts the Pinggy container when it isn't, with backoff to prevent restart storms.

**Independent Test**: Start production, then run `scripts/monitor-prod.sh` in the foreground. Verify it logs successful checks every 30s. Stop the Pinggy container manually (`docker compose -f docker-compose.prod.yml stop pinggy`), verify the monitor detects 3 consecutive failures, restarts Pinggy, and the site comes back. Stop Pinggy again 3 more times to verify the backoff limit triggers.

### Implementation for User Story 3

- [x] T005 [US3] Create the health monitor script in `scripts/monitor-prod.sh` with:
  - Bash strict mode (`#!/usr/bin/env bash`, `set -euo pipefail`), `SCRIPT_DIR`/`PROJECT_DIR` resolution
  - Configurable env vars: `CHECK_INTERVAL` (default 30), `FAILURE_THRESHOLD` (default 3), `MAX_RESTARTS` (default 3), `BACKOFF_WINDOW` (default 600), `CHECK_URL` (default `https://simonrowe.dev`)
  - Main loop: `curl -sf -o /dev/null -m 10 "$CHECK_URL"` every `CHECK_INTERVAL` seconds
  - Failure counter: increment on curl failure, reset to 0 on success
  - When failures reach `FAILURE_THRESHOLD`: run `docker compose -f docker-compose.prod.yml restart pinggy`, increment restart counter, reset failure counter
  - Restart backoff: track restart timestamps in an array, count restarts within `BACKOFF_WINDOW` seconds, if `>= MAX_RESTARTS` log CRIT and skip restart until window expires
  - Logging: ISO 8601 timestamps, levels `[INFO]`/`[WARN]`/`[ERROR]`/`[CRIT]` to stdout
  - Signal handling: `trap` on SIGTERM/SIGINT for clean exit with log message
- [x] T006 [US3] Make script executable: `chmod +x scripts/monitor-prod.sh`

**Checkpoint**: The monitor can run in the foreground, detect outages, restart Pinggy, and back off on persistent failures. This satisfies both US2 (auto-restart on tunnel drop) and US3 (external reachability monitoring).

---

## Phase 6: User Story 4 — Production Status Check (Priority: P3)

**Goal**: A quick command that shows operator the health of all production services at a glance plus external reachability.

**Independent Test**: Run `scripts/status-prod.sh` while production is up — verify it shows all services with health status and external reachability. Stop a service and run again — verify it shows the degraded service.

### Implementation for User Story 4

- [x] T007 [US4] Create production status script in `scripts/status-prod.sh` with:
  - Bash strict mode, `SCRIPT_DIR`/`PROJECT_DIR` resolution
  - Run `docker compose -f docker-compose.prod.yml ps --format json` and parse each service's name, state, and health
  - Display a formatted table: Service | Status | Health
  - Check external reachability: `curl -sf -o /dev/null -m 5 https://simonrowe.dev` and display result
  - Show overall status summary: `ALL HEALTHY` / `DEGRADED` / `DOWN`
  - Exit 0 if all healthy, exit 1 if degraded or down
- [x] T008 [US4] Make script executable: `chmod +x scripts/status-prod.sh`

**Checkpoint**: Operator can get a full production health overview in under 5 seconds.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Integration between scripts and final documentation.

- [x] T009 Optionally integrate monitor startup into `scripts/start-prod.sh` — add a `--monitor` flag that starts `monitor-prod.sh` in the background (via `nohup` with log redirect) after all services are healthy
- [x] T010 Validate all scripts follow Constitution Principle VIII: `#!/usr/bin/env bash`, `set -euo pipefail`, `SCRIPT_DIR`/`PROJECT_DIR` resolution, clear error messages on precondition failures

---

## Dependencies & Execution Order

### Phase Dependencies

- **User Story 1 (Phase 3)**: No dependencies — can start immediately
- **User Story 2 (Phase 4)**: No dependencies — can start immediately (parallel with US1)
- **User Story 3 (Phase 5)**: No dependencies — can start immediately (parallel with US1, US2)
- **User Story 4 (Phase 6)**: No dependencies — can start immediately (parallel with all others)
- **Polish (Phase 7)**: Depends on US1 (T001) and US3 (T005) for the `--monitor` integration

### User Story Dependencies

- **User Story 1 (P1)**: Fully independent
- **User Story 2 (P1)**: Fully independent (compose file comment only)
- **User Story 3 (P2)**: Fully independent (satisfies US2's auto-restart requirement)
- **User Story 4 (P3)**: Fully independent

### Within Each User Story

- Scripts within a story are independent files — [P] tasks can run in parallel
- `chmod` tasks depend on the script being written first

### Parallel Opportunities

All four user stories operate on different files and can be implemented in parallel:

```text
┌─────────────────────────────────────────────────┐
│ T001 [US1] start-prod.sh  ──┐                   │
│ T002 [US1] stop-prod.sh   ──┤ (parallel)        │
│ T004 [US2] compose comment ─┤                    │
│ T005 [US3] monitor-prod.sh ─┤                    │
│ T007 [US4] status-prod.sh  ─┘                    │
│                                                   │
│ Then: T003, T006, T008 (chmod, sequential)        │
│ Then: T009, T010 (polish)                         │
└─────────────────────────────────────────────────┘
```

---

## Parallel Example: All User Stories

```bash
# Launch all script creation tasks together (different files, no dependencies):
Task: "T001 [US1] Create production start script in scripts/start-prod.sh"
Task: "T002 [US1] Create production stop script in scripts/stop-prod.sh"
Task: "T004 [US2] Add comment to pinggy service in docker-compose.prod.yml"
Task: "T005 [US3] Create health monitor script in scripts/monitor-prod.sh"
Task: "T007 [US4] Create production status script in scripts/status-prod.sh"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete T001 + T002 + T003 (start/stop scripts)
2. **STOP and VALIDATE**: Start and stop production on the Raspberry Pi
3. Deploy if ready — immediate operational value

### Incremental Delivery

1. Add User Story 1 → Start/stop production → Deploy (MVP!)
2. Add User Story 3 → Monitor with auto-restart → Deploy (addresses the original Pinggy drop problem)
3. Add User Story 4 → Status overview → Deploy (convenience tooling)
4. Add User Story 2 → Compose documentation → Deploy (documentation)
5. Polish → `--monitor` flag integration → Deploy (quality of life)

### Recommended Execution Order

Since all scripts are independent files, the optimal approach is to implement them all in parallel (T001, T002, T004, T005, T007), then do chmod (T003, T006, T008), then polish (T009, T010).

---

## Notes

- All scripts are self-contained bash files — no shared libraries or imports
- [P] tasks operate on different files with zero dependencies
- US2 is intentionally minimal (compose comment) because research proved Docker health checks are not viable for this use case
- The monitor script (US3) is the primary solution for the Pinggy drop problem
- Each script can be tested independently on the Raspberry Pi
- Commit after each script is complete and tested
