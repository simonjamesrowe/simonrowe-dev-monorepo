# Feature Specification: Production Health Monitoring & Lifecycle Scripts

**Feature Branch**: `016-prod-health-scripts`
**Created**: 2026-04-06
**Status**: Draft
**Input**: User description: "Something funny going on with the production environment, the Pinggy container drops. Need scripts to start/stop production, plus health checking for the Pinggy container and simonrowe.dev reachability with auto-restart."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Start and Stop Production Environment (Priority: P1)

As the site operator, I want simple scripts to start and stop the entire production stack so that I have a single, reliable command for bringing the site online or offline without remembering Docker Compose flags or file paths.

**Why this priority**: Without a consistent way to start/stop production, every other feature (health checks, monitoring) has no foundation. This is the prerequisite for all operational workflows.

**Independent Test**: Can be tested by running the start script and verifying all 9 services come up healthy, then running the stop script and verifying all services shut down cleanly.

**Acceptance Scenarios**:

1. **Given** the production environment is not running, **When** I run the production start script, **Then** all services start in the correct dependency order and the script reports success once all services are healthy.
2. **Given** the production environment is running, **When** I run the production stop script, **Then** all services shut down gracefully and no orphan containers remain.
3. **Given** the production environment is already running, **When** I run the production start script again, **Then** it detects the existing environment and either no-ops or restarts cleanly without data loss.

---

### User Story 2 - Pinggy Container Health Check (Priority: P1)

As the site operator, I want the Pinggy container to have a health check so that Docker can detect when the SSH tunnel has dropped and automatically restart the container via its `restart: unless-stopped` policy.

**Why this priority**: This is the root cause of the reported issue — Pinggy is the only service in the production stack without a health check. When the tunnel silently drops, Docker has no way to detect the failure and the site goes offline until manually restarted. Adding a health check is the simplest, most impactful fix.

**Independent Test**: Can be tested by starting production, verifying the Pinggy health check reports healthy, then simulating a tunnel drop (e.g., network interruption) and confirming Docker detects the unhealthy state.

**Acceptance Scenarios**:

1. **Given** the Pinggy container is running with an active tunnel, **When** the health check runs, **Then** it reports the container as healthy.
2. **Given** the Pinggy tunnel has silently dropped, **When** the health check runs, **Then** it reports the container as unhealthy after the configured retries.
3. **Given** the Pinggy container is unhealthy, **When** Docker's restart policy triggers, **Then** the container restarts and re-establishes the tunnel automatically.

---

### User Story 3 - External Reachability Monitor with Auto-Restart (Priority: P2)

As the site operator, I want a background process that periodically checks whether simonrowe.dev is reachable from outside and automatically restarts the Pinggy container if the site is down, so that I don't need to manually monitor and intervene when the tunnel drops.

**Why this priority**: While the Pinggy health check (P1) addresses most tunnel failures, some failure modes (e.g., Pinggy edge issues, DNS problems) may not be detectable from inside the container. An external reachability check provides defense-in-depth by verifying the site is actually accessible to real users.

**Independent Test**: Can be tested by running the monitor, verifying it logs successful checks, then stopping the Pinggy container and confirming the monitor detects the outage and restarts the container within the configured interval.

**Acceptance Scenarios**:

1. **Given** the monitor is running and simonrowe.dev is reachable, **When** the check interval elapses, **Then** it logs a successful check and takes no action.
2. **Given** the monitor is running and simonrowe.dev becomes unreachable, **When** the check detects consecutive failures exceeding the threshold, **Then** it restarts the Pinggy container and logs the restart action.
3. **Given** the Pinggy container was restarted by the monitor, **When** the site comes back online, **Then** the monitor resumes normal periodic checks.
4. **Given** the monitor restarts Pinggy but the site remains unreachable, **When** the failure persists beyond a maximum retry count, **Then** the monitor logs a critical alert and stops retrying to avoid restart loops.

---

### User Story 4 - Production Status Check (Priority: P3)

As the site operator, I want a quick status command that shows me the health of all production services at a glance, including whether simonrowe.dev is externally reachable, so that I can quickly diagnose issues.

**Why this priority**: Useful for debugging but not critical for automated recovery. The start/stop scripts and health checks handle the main operational needs; this is a convenience for manual troubleshooting.

**Independent Test**: Can be tested by running the status script while production is up and verifying it shows health for all services, then stopping a service and confirming the status reflects the degraded state.

**Acceptance Scenarios**:

1. **Given** all production services are running and healthy, **When** I run the status script, **Then** it displays each service name, its status (running/stopped), health (healthy/unhealthy/none), and whether simonrowe.dev is externally reachable.
2. **Given** one or more services are unhealthy, **When** I run the status script, **Then** unhealthy services are clearly highlighted and the overall status indicates degraded.

---

### Edge Cases

- What happens when the Pinggy token expires or is revoked? The health check should detect this as a failure and the monitor should log it, but auto-restart won't help — the operator needs to be alerted.
- What happens when the host machine loses internet connectivity entirely? The monitor should detect unreachability but avoid restart loops since restarting Pinggy won't help.
- What happens when Docker itself is unresponsive? The monitor script should handle Docker command failures gracefully and log an error.
- What happens when multiple restart attempts occur in rapid succession? A cooldown period or maximum retry limit should prevent restart storms.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a production start script that brings up all services using the production Docker Compose configuration and waits for all health checks to pass before reporting success.
- **FR-002**: System MUST provide a production stop script that gracefully shuts down all production services.
- **FR-003**: System MUST add a health check to the Pinggy container in the production Docker Compose file that verifies the tunnel is active.
- **FR-004**: System MUST provide a health monitor script that periodically checks whether simonrowe.dev is externally reachable via HTTPS.
- **FR-005**: The health monitor MUST automatically restart the Pinggy container when the site is unreachable after a configurable number of consecutive failed checks.
- **FR-006**: The health monitor MUST implement a maximum restart limit to prevent infinite restart loops when the underlying issue is not recoverable by restarting Pinggy.
- **FR-007**: The health monitor MUST log all check results and restart actions with timestamps for operational visibility.
- **FR-008**: System MUST provide a production status script that displays the health and running state of all production services plus external reachability.
- **FR-009**: All scripts MUST be located in the existing `scripts/` directory following the naming convention of existing scripts.
- **FR-010**: The health monitor MUST be runnable as a background process that persists across terminal sessions.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The production environment can be fully started from cold with a single command, with all services healthy within 3 minutes.
- **SC-002**: The production environment can be fully stopped with a single command within 30 seconds.
- **SC-003**: When the Pinggy tunnel drops silently, the site is automatically restored to reachability within 2 minutes without manual intervention.
- **SC-004**: The health monitor detects simonrowe.dev unreachability within 60 seconds of the site going down.
- **SC-005**: Restart loops are prevented — no more than 3 restart attempts within a 10-minute window before the monitor backs off.
- **SC-006**: The operator can determine the health of all production services within 5 seconds using the status script.

## Assumptions

- The production environment runs on a single host (Raspberry Pi) with Docker and Docker Compose installed.
- The `.env` file with `PINGGY_TOKEN` and other secrets is already present on the production host.
- The Pinggy Pro tier supports some form of connection verification (e.g., the tunnel process exits or becomes unresponsive when the connection drops).
- The health monitor will run on the same host as the production environment.
- Internet connectivity from the host is generally reliable; brief interruptions should not trigger unnecessary restarts.
- Scripts target bash shell available on the Raspberry Pi (Debian/Ubuntu-based OS).
