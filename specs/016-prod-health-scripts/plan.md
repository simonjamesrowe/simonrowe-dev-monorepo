# Implementation Plan: Production Health Monitoring & Lifecycle Scripts

**Branch**: `016-prod-health-scripts` | **Date**: 2026-04-06 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/016-prod-health-scripts/spec.md`

## Summary

The Pinggy tunnel container silently drops in production, taking simonrowe.dev offline with no automatic recovery. The root cause is twofold: (1) Pinggy is the only service without a health check, and (2) Docker's restart policy only restarts **exited** containers, not unhealthy ones — so even adding a health check alone wouldn't fix it.

The solution is a set of production lifecycle scripts (start, stop, status) plus an external health monitor that periodically checks whether simonrowe.dev is reachable and restarts the Pinggy container when it isn't. This approach is simpler and more reliable than adding Docker health checks or sidecar containers, because it tests the full user-visible path.

## Technical Context

**Language/Version**: Bash (strict mode: `set -euo pipefail`)
**Primary Dependencies**: Docker Compose, curl, systemd (optional, for persistent monitoring)
**Storage**: N/A (log files only)
**Testing**: Manual integration testing on Raspberry Pi
**Target Platform**: Raspberry Pi (Debian/Ubuntu-based), aarch64
**Project Type**: Scripts (operational tooling)
**Performance Goals**: Health check completes in <5 seconds, site recovery within 2 minutes
**Constraints**: Must work on ARM64, minimal dependencies (only what's on stock Raspberry Pi OS)
**Scale/Scope**: Single host, single production environment

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
| --------- | ------ | ----- |
| I. Monorepo with Separate Containers | PASS | Docker Compose remains the orchestration mechanism. No changes to container architecture. |
| II. Modern Java & React Stack | N/A | No Java or React changes in this feature. |
| III. Quality Gates | N/A | Shell scripts — no JaCoCo, SonarQube, or Checkstyle applicable. |
| IV. Observability & Operability | PASS | Monitor script adds operational visibility with timestamped logs. |
| V. Simplicity & Incremental Delivery | PASS | External monitor is the simplest solution. No sidecar containers, no custom Docker images, no additional infrastructure. |
| VI. Admin CMS UX Standards | N/A | No UI changes. |
| VII. Backup & Restore | N/A | No changes to backup/restore. |
| VIII. Shell Scripting Standards | PASS | All scripts use `#!/usr/bin/env bash`, `set -euo pipefail`, resolve `SCRIPT_DIR`/`PROJECT_DIR`. |

**Post-Phase 1 Re-check**: All gates still pass. No violations introduced during design.

## Project Structure

### Documentation (this feature)

```text
specs/016-prod-health-scripts/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 research output
├── quickstart.md        # Phase 1 quickstart guide
└── checklists/
    └── requirements.md  # Spec quality checklist
```

### Source Code (repository root)

```text
scripts/
├── start-prod.sh        # NEW: Start all production services
├── stop-prod.sh         # NEW: Stop all production services
├── status-prod.sh       # NEW: Show production service health
├── monitor-prod.sh      # NEW: External reachability monitor with auto-restart
├── start.sh             # EXISTING: Dev start (unchanged)
├── stop.sh              # EXISTING: Dev stop (unchanged)
├── backup.sh            # EXISTING: Backup (unchanged)
└── restore.sh           # EXISTING: Restore (unchanged)
```

**Structure Decision**: All new scripts go in the existing `scripts/` directory, following the established `-prod` suffix naming convention to distinguish from development scripts. No new directories needed.

## Design

### Script 1: `scripts/start-prod.sh`

**Purpose**: Start the full production stack with a single command.

**Behaviour**:
1. Resolve `PROJECT_DIR` to locate `docker-compose.prod.yml`
2. Run `docker compose -f docker-compose.prod.yml up -d`
3. Wait for all services to become healthy (poll `docker compose ps` until all health checks pass or timeout after 3 minutes)
4. Report final status of each service
5. Exit 0 on success, exit 1 if any service fails to become healthy

**Idempotency**: Running when already started is safe — Docker Compose handles this natively (recreates only changed containers).

### Script 2: `scripts/stop-prod.sh`

**Purpose**: Stop all production services gracefully.

**Behaviour**:
1. Resolve `PROJECT_DIR`
2. Run `docker compose -f docker-compose.prod.yml down`
3. Report completion
4. Named volumes are preserved (data survives stop/start cycles)

### Script 3: `scripts/status-prod.sh`

**Purpose**: Quick health overview of all production services.

**Behaviour**:
1. Run `docker compose -f docker-compose.prod.yml ps --format json` to get service state
2. For each service, display: name, status (running/exited), health (healthy/unhealthy/N/A)
3. Check external reachability: `curl -sf -o /dev/null -m 5 https://simonrowe.dev`
4. Display overall status: ALL HEALTHY / DEGRADED / DOWN

### Script 4: `scripts/monitor-prod.sh`

**Purpose**: Continuously monitor simonrowe.dev reachability and auto-restart Pinggy on failure.

**Behaviour**:
1. **Check interval**: Every 30 seconds (configurable via `CHECK_INTERVAL` env var)
2. **Health check**: `curl -sf -o /dev/null -m 10 https://simonrowe.dev`
3. **Failure threshold**: 3 consecutive failures before restart (configurable via `FAILURE_THRESHOLD`)
4. **Restart action**: `docker compose -f docker-compose.prod.yml restart pinggy`
5. **Backoff**: Maximum 3 restarts per 10-minute window. After exhausting, log critical alert and wait for window to expire.
6. **Logging**: All events logged with ISO 8601 timestamps to stdout (can be redirected to file)
7. **Signal handling**: Trap SIGTERM/SIGINT for clean shutdown

**Running persistently**:
- Primary: `systemd` timer/service unit (if available)
- Fallback: `nohup ./scripts/monitor-prod.sh >> /var/log/monitor-prod.log 2>&1 &`
- The start-prod.sh script will optionally start the monitor as well

**Log format**:
```
2026-04-06T14:30:00+00:00 [INFO] Health check passed - simonrowe.dev reachable
2026-04-06T14:30:30+00:00 [WARN] Health check failed (1/3) - simonrowe.dev unreachable
2026-04-06T14:31:30+00:00 [ERROR] Restarting pinggy container (3 consecutive failures)
2026-04-06T14:32:00+00:00 [CRIT] Max restarts reached (3/3 in 10min window) - backing off
```

## Complexity Tracking

No constitution violations. No complexity tracking needed.
