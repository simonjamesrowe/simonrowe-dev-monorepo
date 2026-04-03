# Feature Specification: Docker Compose Local Deployment with Pinggy and Nginx Reverse Proxy

**Feature Branch**: `012-docker-compose-deploy`
**Created**: 2026-04-02
**Status**: Draft
**Input**: User description: "Run the full stack locally using Docker Compose with Pinggy tunneling and an nginx reverse proxy"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Launch Full Stack Locally (Priority: P1)

As a developer, I want to start the entire application stack with a single `docker compose` command so that all services (frontend, backend, databases, messaging, search) run together locally and are accessible via my custom domain.

**Why this priority**: This is the core capability. Without a working Docker Compose setup that brings up all services, nothing else functions.

**Independent Test**: Run `docker compose -f docker-compose.prod.yml up -d` and verify all containers reach healthy status. Confirm the frontend serves pages and the backend responds to API requests on the Docker network.

**Acceptance Scenarios**:

1. **Given** the Docker Compose file exists and images are available, **When** I run `docker compose -f docker-compose.prod.yml up -d`, **Then** all services (MongoDB, Kafka, Elasticsearch, backend, frontend, nginx, otel-collector, pinggy) start and reach healthy status within 5 minutes.
2. **Given** all services are running, **When** I run `docker compose -f docker-compose.prod.yml down`, **Then** all containers stop gracefully and named volumes are preserved for the next startup.
3. **Given** the stack has been previously started and stopped, **When** I start it again, **Then** data in MongoDB, Elasticsearch, and Kafka is preserved from the previous session.

---

### User Story 2 - Domain-Based Routing via Nginx Reverse Proxy (Priority: P1)

As a developer, I want an nginx reverse proxy that routes traffic based on hostname so that `simonrowe.dev` and `www.simonrowe.dev` serve the frontend, while `api.simonrowe.dev` routes to the backend API.

**Why this priority**: Domain-based routing is essential for the deployment to match production behavior. Without it, the tunnel only exposes a single service.

**Independent Test**: With all services running, send HTTP requests with different `Host` headers to the nginx container and verify correct routing.

**Acceptance Scenarios**:

1. **Given** the nginx reverse proxy is running, **When** a request arrives with `Host: simonrowe.dev`, **Then** the request is proxied to the frontend container.
2. **Given** the nginx reverse proxy is running, **When** a request arrives with `Host: www.simonrowe.dev`, **Then** the request is proxied to the frontend container.
3. **Given** the nginx reverse proxy is running, **When** a request arrives with `Host: api.simonrowe.dev`, **Then** the request is proxied to the backend container on its application port.
4. **Given** the frontend is serving a single-page application, **When** a user navigates to a deep-linked route (e.g., `/blog/my-post`), **Then** the frontend returns `index.html` and the client-side router handles the path.
5. **Given** the frontend receives a request to `/api/*` or `/uploads/*`, **When** the frontend nginx processes it, **Then** it proxies those requests to the backend (existing frontend nginx.conf behavior is preserved).

---

### User Story 3 - Public Access via Pinggy Tunnel (Priority: P1)

As a developer, I want the Pinggy tunnel to expose the nginx reverse proxy publicly using my custom wildcard domain (`*.simonrowe.dev`) so that the site is accessible from the internet at `simonrowe.dev`, `www.simonrowe.dev`, and `api.simonrowe.dev`.

**Why this priority**: Public accessibility via the custom domain is the primary purpose of this deployment setup.

**Independent Test**: With the stack running, open `https://simonrowe.dev` in a browser from an external network and verify the frontend loads. Then call `https://api.simonrowe.dev/api/profile` and verify a response.

**Acceptance Scenarios**:

1. **Given** the Pinggy tunnel container is running with a valid token, **When** I visit `https://simonrowe.dev` from any browser, **Then** the frontend application loads successfully.
2. **Given** the Pinggy tunnel is active, **When** I make an API request to `https://api.simonrowe.dev/api/profile`, **Then** the backend responds with valid data.
3. **Given** the Pinggy tunnel is active, **When** I visit `https://www.simonrowe.dev`, **Then** the frontend loads identically to the root domain.
4. **Given** the Pinggy token is missing or invalid, **When** I start the stack, **Then** the Pinggy container fails with a clear error indicating the token issue, while other services continue running.

---

### User Story 4 - Environment Configuration (Priority: P2)

As a developer, I want environment variables to be sourced from my local env file so that secrets (API keys, SMTP passwords, OAuth tokens) are injected into the correct containers without being committed to version control.

**Why this priority**: Proper environment configuration is required for the backend and Pinggy to function, but the mechanism (env_file or variable substitution) is a configuration detail.

**Independent Test**: Start the stack with the env file in place and verify the backend can connect to external services (e.g., Auth0 JWT validation, Google Drive, Brevo SMTP).

**Acceptance Scenarios**:

1. **Given** the env file exists at `~/workspace/simonjamesrowe/env`, **When** the stack starts, **Then** the backend receives all required environment variables (BREVO_SMTP_PASSWORD, RECAPTCHA_SECRET_KEY, GROQ_API_KEY, GOOGLE_DRIVE_*, etc.).
2. **Given** the env file contains `PINGGY_TOKEN`, **When** the Pinggy container starts, **Then** it uses the token to authenticate with the Pinggy service.
3. **Given** the backend container starts, **Then** service discovery environment variables (MongoDB URI, Kafka bootstrap servers, Elasticsearch URI) override defaults to point to container hostnames.

---

### User Story 5 - Data Persistence Across Restarts (Priority: P2)

As a developer, I want all data stores to use named Docker volumes so that data survives container restarts and `docker compose down` without `--volumes`.

**Why this priority**: Data loss on restart would make the local deployment impractical for ongoing development and testing.

**Independent Test**: Seed MongoDB with test data, restart the stack, and verify the data persists.

**Acceptance Scenarios**:

1. **Given** data has been written to MongoDB, **When** I run `docker compose down && docker compose up -d`, **Then** all MongoDB data is preserved.
2. **Given** Elasticsearch indices have been populated, **When** containers restart, **Then** search indices and data remain intact.
3. **Given** Kafka has topic data, **When** containers restart, **Then** topic data and consumer offsets are preserved.
4. **Given** the backend has uploaded media files, **When** containers restart, **Then** uploaded files are still accessible via `/uploads/`.

---

### User Story 6 - Cloudflare DNS Configuration (Priority: P2)

As a developer, I want clear guidance on Cloudflare DNS setup so that my custom domain points to the Pinggy tunnel correctly.

**Why this priority**: DNS configuration is a one-time setup but is required for the custom domain to work.

**Independent Test**: After DNS records are configured, run `dig simonrowe.dev` and verify the CNAME resolves to the Pinggy endpoint.

**Acceptance Scenarios**:

1. **Given** Cloudflare manages the `simonrowe.dev` domain, **When** CNAME records are configured for the root domain, `www`, and `api` subdomains, **Then** all three hostnames resolve to the Pinggy tunnel endpoint.
2. **Given** Cloudflare proxy (orange cloud) may interfere with Pinggy's TLS, **When** configuring DNS records, **Then** documentation specifies whether to use DNS-only mode or proxied mode.

---

### Edge Cases

- What happens when the backend container takes longer than expected to start (e.g., slow native image startup, database migration)?
- What happens when the Pinggy tunnel disconnects temporarily? The container auto-restarts via `unless-stopped` policy.
- What happens when one infrastructure service (e.g., Elasticsearch) fails its healthcheck? Do dependent services wait or fail?
- What happens when Docker images are not yet published to GHCR (first run before CI has built)?
- What happens when the env file is missing or has incorrect values?
- What happens when port 443 outbound is blocked (corporate network)?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST provide a single Docker Compose file that starts all services (MongoDB, Kafka, Elasticsearch, backend, frontend, nginx reverse proxy, OpenTelemetry collector, Pinggy tunnel).
- **FR-002**: The nginx reverse proxy MUST route requests with `Host: simonrowe.dev` or `Host: www.simonrowe.dev` to the frontend container.
- **FR-003**: The nginx reverse proxy MUST route requests with `Host: api.simonrowe.dev` to the backend container on its application port, including WebSocket upgrade support for the `/ws` endpoint.
- **FR-004**: The Pinggy tunnel MUST connect the nginx reverse proxy to the public internet using the `*.simonrowe.dev` wildcard custom domain and the `PINGGY_TOKEN` from the environment.
- **FR-005**: All data stores (MongoDB, Kafka, Elasticsearch) MUST use named Docker volumes for persistence across restarts.
- **FR-006**: The backend MUST have a named volume for uploaded media assets so files persist across restarts.
- **FR-007**: The backend MUST receive environment variables for service discovery (database URIs, message broker addresses, search engine addresses) pointing to container hostnames.
- **FR-008**: The backend MUST receive secret environment variables (API keys, SMTP credentials, OAuth tokens) from the local env file.
- **FR-009**: All services MUST have health checks so that dependent services wait for their dependencies to be ready.
- **FR-010**: The frontend container's existing nginx configuration (which proxies `/api/` and `/uploads/` to the backend) MUST continue to function correctly within the Docker network.
- **FR-011**: The OpenTelemetry collector MUST be configured to receive traces from the backend.
- **FR-012**: The Pinggy tunnel MUST depend on the nginx reverse proxy being healthy before starting.
- **FR-013**: The system MUST include documentation or configuration guidance for setting up Cloudflare DNS CNAME records pointing to the Pinggy tunnel.
- **FR-014**: The backend MUST be configured with CORS allowed origins including `https://simonrowe.dev` and `https://www.simonrowe.dev`, since the frontend at those domains makes direct API calls to `https://api.simonrowe.dev`.
- **FR-015**: The frontend image MUST be built with `VITE_API_BASE_URL=https://api.simonrowe.dev` so that all API calls target the backend's dedicated subdomain.
- **FR-016**: All containers MUST use an `unless-stopped` restart policy to automatically recover from crashes, including transient Pinggy tunnel disconnections.

### Key Entities

- **Nginx Reverse Proxy**: Entry point for all external traffic. Routes based on hostname to either frontend or backend. Sits between the Pinggy tunnel and the application services.
- **Pinggy Tunnel**: Establishes an SSH tunnel from the local nginx reverse proxy to Pinggy's public infrastructure, making the local stack accessible at `*.simonrowe.dev`.
- **Frontend Service**: React SPA served by nginx (within the frontend container). Proxies API and upload requests to the backend.
- **Backend Service**: Spring Boot application providing REST APIs, WebSocket endpoints, and serving uploaded media.
- **Infrastructure Services**: MongoDB (primary data store), Kafka (async messaging), Elasticsearch (search), OpenTelemetry Collector (observability).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All containers reach healthy status within 5 minutes of running the startup command.
- **SC-002**: The frontend loads successfully at `https://simonrowe.dev` and `https://www.simonrowe.dev` from an external browser.
- **SC-003**: The backend API responds to requests at `https://api.simonrowe.dev/api/profile` from an external browser.
- **SC-004**: Data in MongoDB, Elasticsearch, and Kafka persists across a full stop-start cycle (`docker compose down` then `docker compose up`).
- **SC-005**: Uploaded media files persist across container restarts and are accessible via `https://simonrowe.dev/uploads/`.
- **SC-006**: The entire stack can be started with a single command and no manual intervention beyond having the env file in place.
- **SC-007**: The WebSocket endpoint for chat functionality works through the tunnel at `wss://api.simonrowe.dev/ws/chat`.

## Clarifications

### Session 2026-04-02

- Q: Does the frontend use relative `/api/` paths (proxied by frontend nginx) or absolute `https://api.simonrowe.dev` URLs? → A: Frontend uses `https://api.simonrowe.dev` for all API calls. Backend needs CORS configuration for `https://simonrowe.dev` and `https://www.simonrowe.dev`.
- Q: What restart policy should containers use? → A: All containers use `unless-stopped` restart policy for automatic recovery from crashes including tunnel drops.
- Q: Should WebSocket connections go through `api.simonrowe.dev` or `simonrowe.dev`? → A: WebSocket connects via `wss://api.simonrowe.dev/ws`. All backend traffic (REST + WebSocket) uses the `api` subdomain.

## Assumptions

- The wildcard domain `*.simonrowe.dev` is already configured in Pinggy with CNAME validated and certificate issued (confirmed from screenshot, expiry 20/06/2026).
- The `PINGGY_TOKEN` is present in the env file at `~/workspace/simonjamesrowe/env`.
- Docker images are published to GHCR by GitHub Actions on merge to main and are publicly accessible (or the user is authenticated with `docker login ghcr.io`).
- The existing `frontend/nginx.conf` handles SPA routing and API/upload proxying and does not need modification for this feature.
- Cloudflare DNS is already managing the `simonrowe.dev` domain.
- The backend's GraalVM native image includes `bash` and `curl` (or equivalent) for healthcheck commands.
- The Pinggy SSH tunnel supports the `PINGGY_TOKEN` being passed as part of the SSH connection for custom domain authentication.
- Cloudflare DNS records should use DNS-only mode (grey cloud) to avoid interfering with Pinggy's TLS termination.
