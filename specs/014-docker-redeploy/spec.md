# Feature Specification: Docker Redeploy from Admin Console

**Feature Branch**: `014-docker-redeploy`
**Created**: 2026-04-04
**Status**: Draft
**Input**: User description: "I want a function in the admin console, data ops that will redeploy the latest version of this site. There must be java docker libraries that can interface with docker to pull the latest images from remote and then restart."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Redeploy Site with Latest Images (Priority: P1)

As a site administrator, I want to trigger a full redeployment of the site from the admin Data Operations page so that I can roll out the latest published container images without SSH access or manual Docker commands.

**Why this priority**: This is the core feature — without the ability to pull and restart containers, nothing else matters. It delivers the primary value of one-click deployments from the admin UI.

**Independent Test**: Can be fully tested by clicking the "Redeploy Site" button and verifying that the backend and frontend containers are replaced with the latest images from the container registry, and the site comes back online.

**Acceptance Scenarios**:

1. **Given** the admin is on the Data Operations page, **When** they click the "Redeploy Site" button and confirm, **Then** the system pulls the latest backend and frontend images from the container registry, stops the running containers, and starts new containers with the updated images.
2. **Given** a redeployment is in progress, **When** the admin views the Data Operations page, **Then** they see real-time progress updates (e.g., "Pulling backend image…", "Restarting frontend…", "Deployment complete") with a progress indicator.
3. **Given** the redeployment completes successfully, **When** the admin views the result, **Then** they see a success summary including which images were updated.

---

### User Story 2 - Graceful Self-Restart of Backend (Priority: P1)

As a site administrator, I want the backend service to gracefully handle its own restart during redeployment so that the redeployment process completes reliably and the admin receives confirmation once the site is back online.

**Why this priority**: Since the backend is itself running inside a Docker container, the redeploy operation must handle the fact that it is restarting itself. This is critical for the feature to work at all.

**Independent Test**: Can be tested by triggering a redeploy and verifying the backend comes back online after restart, and the last operation status shows "Completed" on next page load.

**Acceptance Scenarios**:

1. **Given** a redeploy is triggered, **When** the backend container is about to be restarted, **Then** the system records the operation status before initiating the restart, so the admin can see the final status after refresh.
2. **Given** the backend has been restarted as part of a redeploy, **When** the admin refreshes the Data Operations page, **Then** the last operation summary shows the redeploy as completed with a timestamp.

---

### User Story 3 - Redeploy Failure Handling (Priority: P2)

As a site administrator, I want clear feedback when a redeployment fails so that I can understand what went wrong and take corrective action.

**Why this priority**: Failure handling is essential for a production feature but is secondary to the core deploy flow.

**Independent Test**: Can be tested by simulating failure conditions (e.g., unreachable registry, invalid credentials) and verifying error messages are shown.

**Acceptance Scenarios**:

1. **Given** the container registry is unreachable, **When** the admin triggers a redeploy, **Then** the system shows an error message indicating the image pull failed with a meaningful description.
2. **Given** a container fails to start after being updated, **When** the admin views the operation result, **Then** the error message includes which service failed and the reason.

---

### Edge Cases

- What happens if the admin triggers a redeploy while another data operation (backup, restore, etc.) is already in progress? The system rejects the request with a message indicating an operation is already running (consistent with existing behavior).
- What happens if the Docker socket is not accessible from the backend container? The system shows a clear error on the Data Operations page indicating Docker connectivity is unavailable.
- What happens if only one image (backend or frontend) has changed? The system still pulls and restarts both to ensure consistency, but the progress indicates which images were already up to date.
- What happens if the backend is restarting itself and the SSE connection drops? The frontend handles the disconnection gracefully and shows a "Reconnecting…" state, then displays the final result once the backend is back.
- What happens if the new image fails to start (bad deployment)? Docker's restart policy will attempt recovery, and the previous operation status persisted in the database will indicate the redeploy was in progress.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a "Redeploy Site" action card on the Data Operations admin page, consistent in style with the existing action cards (Backup, Restore, Clear, Rebuild Index).
- **FR-002**: System MUST require confirmation before starting a redeployment, showing a dialog that explains containers will be pulled and restarted.
- **FR-003**: System MUST pull the latest versions of the backend and frontend container images from the configured container registry.
- **FR-004**: System MUST stop and recreate the backend and frontend containers using the newly pulled images, preserving volume mounts and environment configuration.
- **FR-005**: System MUST report real-time progress via the existing SSE progress mechanism used by other data operations.
- **FR-006**: System MUST enforce the existing one-operation-at-a-time constraint — a redeploy cannot run concurrently with backup, restore, clear, or rebuild operations.
- **FR-007**: System MUST persist the redeploy operation status to the database so that after the backend restarts, the last operation result is available on the Data Operations page.
- **FR-008**: System MUST handle the self-restart scenario by ensuring the redeploy command completes even after the backend container is stopped (e.g., restart non-backend containers first, then restart itself last).
- **FR-009**: System MUST only redeploy the application containers (backend, frontend, nginx) and NOT the infrastructure containers (MongoDB, Kafka, Elasticsearch).
- **FR-010**: System MUST display an error if the Docker environment is not accessible (e.g., Docker socket not mounted).

### Key Entities

- **Redeploy Operation**: A data operation record (consistent with existing operation types) tracking the status, progress, and result of a site redeployment.
- **Container Image**: A deployable artifact identified by registry, repository, and tag used to run a site service.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Administrator can trigger a full site redeployment from the admin UI in under 3 clicks (navigate to page, click button, confirm).
- **SC-002**: Redeployment completes end-to-end (pull images, restart containers, site back online) within 5 minutes under normal network conditions.
- **SC-003**: The site experiences less than 60 seconds of downtime during a redeployment.
- **SC-004**: After redeployment, the admin can verify the operation completed successfully via the Data Operations page without needing terminal access.
- **SC-005**: Failed redeployments show a clear, actionable error message within 30 seconds of the failure occurring.

## Assumptions

- The backend container has access to the Docker socket (mounted as a volume) to control sibling containers. This is a deployment configuration prerequisite.
- The production Docker Compose setup uses GHCR images as defined in `docker-compose.prod.yml` for the backend and frontend services.
- The redeployment targets the application containers (backend, frontend, nginx) — not infrastructure (MongoDB, Kafka, Elasticsearch).
- Authentication to the container registry is handled at the Docker daemon level, not within the application.
- A Java Docker client library will be used to interact with the Docker daemon from the backend service.
- The backend orchestrates its own restart by restarting other containers first, persisting the final status, then restarting itself last — relying on Docker's restart policy to bring it back.
