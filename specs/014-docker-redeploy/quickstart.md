# Quickstart: Docker Redeploy Feature

## Overview

Adds a "Redeploy Site" button to the admin Data Operations page. When clicked, the backend uses `ProcessBuilder` to run `docker compose up -d --pull always` targeting the backend, frontend, and nginx services. The backend restarts itself last and persists the operation status to MongoDB before doing so.

## Prerequisites

1. Docker socket mounted into backend container (`/var/run/docker.sock`)
2. Docker CLI binary mounted from host (`/usr/local/bin/docker`)
3. Docker Compose plugin mounted from host (`/usr/local/lib/docker/cli-plugins`)
4. `docker-compose.prod.yml` accessible inside container (`/workspace/docker-compose.prod.yml`)

## Key Files to Create/Modify

### Backend (New)
- `backend/src/main/java/com/simonrowe/dataops/RedeployService.java` — Orchestrates the redeploy via ProcessBuilder
- `backend/src/main/java/com/simonrowe/dataops/RedeployProperties.java` — Configuration properties record

### Backend (Modify)
- `backend/src/main/java/com/simonrowe/dataops/OperationType.java` — Add `REDEPLOY` enum value
- `backend/src/main/java/com/simonrowe/dataops/DataOperationsController.java` — Add `POST /redeploy` endpoint
- `backend/src/main/java/com/simonrowe/dataops/DataOperationsService.java` — Add Docker availability check

### Frontend (Modify)
- `frontend/src/pages/admin/DataOperationsAdmin.tsx` — Add Redeploy card with confirmation dialog and SSE reconnection logic
- `frontend/src/services/dataOperationsApi.ts` — Add `startRedeploy()` function

### Infrastructure (Modify)
- `docker-compose.prod.yml` — Add Docker socket, CLI, and compose file volume mounts to backend service

## Implementation Order

1. Add `REDEPLOY` to `OperationType` enum
2. Create `RedeployProperties` configuration record
3. Create `RedeployService` with ProcessBuilder logic
4. Add controller endpoint
5. Update frontend API service
6. Add Redeploy card to Data Operations page
7. Add SSE reconnection logic for backend restart
8. Update `docker-compose.prod.yml` with volume mounts
9. Test end-to-end

## Architecture Decision

Uses `ProcessBuilder` + `docker compose` CLI instead of docker-java library because:
- Zero GraalVM native image compatibility issues (constitution requirement)
- Docker Compose handles config preservation automatically
- Single command replaces complex container inspection/recreation logic
