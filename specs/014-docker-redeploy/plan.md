# Implementation Plan: Docker Redeploy from Admin Console

**Branch**: `014-docker-redeploy` | **Date**: 2026-04-04 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/014-docker-redeploy/spec.md`

## Summary

Add a "Redeploy Site" operation to the admin Data Operations page that pulls the latest container images from GHCR and restarts the application containers (backend, frontend, nginx). Uses `ProcessBuilder` to invoke `docker compose up -d --pull always` — avoiding docker-java's GraalVM native image compatibility issues. The backend persists operation status to MongoDB before restarting itself last.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript (frontend)
**Primary Dependencies**: Spring Boot 3.5.x, React, ProcessBuilder (java.lang), Docker Compose CLI (mounted from host)
**Storage**: MongoDB (operation status persistence via existing DataOperation record)
**Testing**: Testcontainers (backend integration), Vitest (frontend)
**Target Platform**: Docker containers on Linux host (production), macOS (development)
**Project Type**: Web application (backend + frontend)
**Performance Goals**: Redeploy completes within 5 minutes; < 60s downtime
**Constraints**: GraalVM native image (no docker-java), Cloud Native Buildpacks (no Docker CLI in image)
**Scale/Scope**: Single admin user, 3 application containers to manage

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Monorepo with Separate Containers | PASS | No changes to container build process. Volume mounts added to docker-compose.prod.yml. |
| II. Modern Java & React Stack | PASS | Java 21, Spring Boot 3.5.x, React. No new frameworks introduced. |
| III. Quality Gates | PASS | Tests will use Testcontainers. Checkstyle/JaCoCo apply (dataops already excluded from JaCoCo). |
| IV. Observability | PASS | Operation status persisted to MongoDB. Structured logging for redeploy events. |
| V. Simplicity | PASS | ProcessBuilder is the simplest approach. No new libraries. Single shell command. |
| VI. Admin CMS UX Standards | PASS | New card follows existing action card pattern with Lucide React icons. |
| VII. Backup & Restore | N/A | No changes to backup/restore. |
| VIII. Shell Scripting Standards | N/A | No new shell scripts created. |

**Post-Phase 1 re-check**: All principles still satisfied. The ProcessBuilder approach adds zero new dependencies, aligning perfectly with Principle V (Simplicity).

## Project Structure

### Documentation (this feature)

```text
specs/014-docker-redeploy/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── api.yaml         # OpenAPI contract for POST /redeploy
│   └── docker-compose.prod.yml.patch  # Required volume mount changes
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/simonrowe/dataops/
│   ├── DataOperationsController.java  # MODIFY: Add POST /redeploy endpoint
│   ├── DataOperationsService.java     # MODIFY: Add Docker availability check
│   ├── OperationType.java             # MODIFY: Add REDEPLOY enum value
│   ├── RedeployService.java           # NEW: ProcessBuilder orchestration
│   └── RedeployProperties.java        # NEW: Configuration properties
└── src/test/java/com/simonrowe/dataops/
    └── RedeployServiceTest.java       # NEW: Unit/integration tests

frontend/
├── src/
│   ├── pages/admin/
│   │   └── DataOperationsAdmin.tsx    # MODIFY: Add Redeploy card + reconnection
│   └── services/
│       └── dataOperationsApi.ts       # MODIFY: Add startRedeploy()
└── tests/
    └── dataOperationsRedeploy.test.ts # NEW: Frontend tests

docker-compose.prod.yml                # MODIFY: Add Docker socket/CLI volume mounts
```

**Structure Decision**: Follows existing web application structure. All new backend code goes in the existing `com.simonrowe.dataops` package alongside the other operation services.

## Complexity Tracking

No constitution violations to justify. The implementation uses zero new dependencies and follows all existing patterns exactly.
