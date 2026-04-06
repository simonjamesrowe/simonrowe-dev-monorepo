# Feature Specification: Add Portainer Container Management Console

**Feature Branch**: `001-add-portainer`
**Created**: 2026-04-06
**Status**: Draft
**Input**: User description: "I want to add portainer to the prod site underneath console.simonrowe.dev. Not sure how we could set this up with auth, whether it has its own built in auth mechanism, or we'd need to use auth0 for it."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Access Portainer via console.simonrowe.dev (Priority: P1)

As the site administrator, I want to access Portainer's container management UI at console.simonrowe.dev so that I can monitor and manage Docker containers running in production without SSH access.

**Why this priority**: This is the core functionality — without accessible UI at the correct domain, no other feature matters.

**Independent Test**: Can be fully tested by navigating to console.simonrowe.dev in a browser and seeing the Portainer login screen, delivering immediate value as a container management dashboard.

**Acceptance Scenarios**:

1. **Given** Portainer is deployed and running, **When** I navigate to console.simonrowe.dev, **Then** I see the Portainer login page served over HTTPS.
2. **Given** the production Docker Compose stack is running, **When** I check the Portainer service status, **Then** it reports healthy and is accessible via the nginx reverse proxy.
3. **Given** the Pinggy tunnel is active with wildcard domain support, **When** I access console.simonrowe.dev externally, **Then** the request is routed through nginx to the Portainer service.

---

### User Story 2 - Authenticate with Built-in Credentials (Priority: P1)

As the site administrator, I want to log in to Portainer using its built-in authentication so that only I can access the container management console.

**Why this priority**: Authentication is essential for security — Portainer must not be accessible without login. Portainer Community Edition includes built-in user management with username/password authentication, which is the simplest and most appropriate approach for a single-admin setup.

**Independent Test**: Can be fully tested by attempting to access Portainer without credentials (should be denied) and then logging in with the admin account (should succeed).

**Acceptance Scenarios**:

1. **Given** Portainer is freshly deployed, **When** I access console.simonrowe.dev for the first time, **Then** I am prompted to create an admin account with a secure password.
2. **Given** an admin account exists, **When** I navigate to console.simonrowe.dev, **Then** I am presented with a login form and must authenticate before accessing any management features.
3. **Given** I am not authenticated, **When** I attempt to access any Portainer API endpoint or page directly, **Then** I am redirected to the login page.

---

### User Story 3 - Manage Docker Containers (Priority: P2)

As the site administrator, I want to view, start, stop, and restart containers from the Portainer dashboard so that I can manage the production environment through a visual interface.

**Why this priority**: This is the primary ongoing value of Portainer — day-to-day container management. It depends on P1 stories being complete.

**Independent Test**: Can be fully tested by logging into Portainer, viewing the list of running containers, and performing a restart on a non-critical service.

**Acceptance Scenarios**:

1. **Given** I am logged into Portainer, **When** I view the dashboard, **Then** I see all containers defined in the production Docker Compose stack with their current status.
2. **Given** I am viewing the container list, **When** I select a container, **Then** I can view its logs, inspect its configuration, and see resource usage.
3. **Given** I am viewing a specific container, **When** I click restart, **Then** the container restarts and I see the updated status reflected in the UI.

---

### User Story 4 - Portainer Survives Redeployments (Priority: P2)

As the site administrator, I want Portainer's data (admin account, settings) to persist across container restarts and redeployments so that I don't have to reconfigure it each time.

**Why this priority**: Without persistence, every redeployment would require re-creating the admin account, making the tool unreliable.

**Independent Test**: Can be fully tested by logging in, restarting the Portainer container, and confirming the admin account still works.

**Acceptance Scenarios**:

1. **Given** Portainer is configured with an admin account, **When** the Docker Compose stack is restarted, **Then** the admin account and all settings are preserved.
2. **Given** a redeployment is triggered via the existing redeploy mechanism, **When** the stack comes back up, **Then** Portainer is accessible with the same credentials as before.

---

### Edge Cases

- What happens if the admin password is lost? Portainer supports password reset via CLI command against the running container.
- What happens if Portainer's data volume is accidentally deleted? The admin account must be re-created on first access (initial setup wizard).
- What happens if the Docker socket becomes unavailable? Portainer should show a connection error in the UI rather than crashing.
- What happens if console.simonrowe.dev is accessed before Portainer has finished starting? Nginx should return a 502 Bad Gateway, and the user can retry after a few seconds.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST run Portainer Community Edition as a service within the production Docker Compose stack.
- **FR-002**: System MUST route requests for console.simonrowe.dev through the nginx reverse proxy to the Portainer service.
- **FR-003**: System MUST support WebSocket connections through the proxy to enable Portainer's container console and log streaming features.
- **FR-004**: System MUST persist Portainer's configuration data (user accounts, settings) in a named Docker volume to survive restarts.
- **FR-005**: System MUST use Portainer's built-in authentication mechanism for access control (no Auth0 integration required).
- **FR-006**: System MUST configure Portainer with access to the Docker socket so it can manage containers on the host.
- **FR-007**: System MUST NOT expose Portainer's ports directly to the host — access MUST only be available through the nginx reverse proxy.
- **FR-008**: System MUST include a health check for the Portainer service consistent with other services in the stack.

### Key Entities

- **Portainer Service**: The container management application, connected to the Docker socket, serving its UI on an internal port.
- **Portainer Data Volume**: A named Docker volume storing Portainer's database (user accounts, settings, environment configuration).
- **Nginx Server Block**: A virtual host configuration routing console.simonrowe.dev to the Portainer service with WebSocket support.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Administrator can access the Portainer login page at console.simonrowe.dev within 30 seconds of the stack starting.
- **SC-002**: Administrator can log in and view all running containers within 10 seconds of authenticating.
- **SC-003**: Container console (exec) and log streaming work through the reverse proxy without connection drops.
- **SC-004**: Portainer credentials and settings persist across at least 10 consecutive stack restarts without data loss.
- **SC-005**: No Portainer ports are accessible directly on the host — only through console.simonrowe.dev via the reverse proxy.

## Assumptions

- Portainer Community Edition is sufficient (no need for Business Edition features like LDAP or advanced OIDC group mapping).
- Built-in Portainer authentication is appropriate since this is a single-administrator setup. Auth0 integration is not needed — Portainer CE does support OAuth2 if desired in the future, but built-in auth is simpler and avoids unnecessary complexity.
- The existing Pinggy wildcard domain configuration already supports console.simonrowe.dev subdomains without additional DNS changes.
- The existing nginx reverse proxy container can be extended with an additional server block for the new subdomain.
- Portainer will use the HTTP port (9000) internally, with TLS terminated at the Pinggy tunnel layer (consistent with how the existing services handle HTTPS).
