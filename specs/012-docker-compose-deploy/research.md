# Research: Docker Compose Local Deployment

## Pinggy Custom Domain SSH Tunnel

**Decision**: Use `${PINGGY_TOKEN}@pro.pinggy.io` with single `-R0:nginx:80` forwarding rule.

**Rationale**: The Pro tier endpoint (`pro.pinggy.io`) supports custom domain authentication via the token in the SSH username. The wildcard domain `*.simonrowe.dev` is already configured in the Pinggy dashboard with CNAME validation and TLS certificate. Pinggy terminates TLS at their edge and forwards plain HTTP to the nginx reverse proxy, so no local TLS configuration is needed.

**Alternatives considered**:
- `tcp@a.pinggy.io` (free tier): No custom domain support, generates random URLs.
- Multiple `-R` flags for different subdomains: Unnecessary since the nginx reverse proxy handles hostname-based routing internally.

**Command syntax**:
```bash
ssh -p 443 -R0:nginx:80 -o StrictHostKeyChecking=no ${PINGGY_TOKEN}@pro.pinggy.io
```

## CORS Configuration

**Decision**: Set `CORS_ALLOWED_ORIGINS=https://simonrowe.dev,https://www.simonrowe.dev` as an environment variable for the backend container.

**Rationale**: The backend already reads `cors.allowed-origins` from the `CORS_ALLOWED_ORIGINS` environment variable (default: `http://localhost:5173`). Both the REST API CORS configuration (`WebConfig.java`) and WebSocket allowed origins (`WebSocketConfig.java`) use this same property. Since the frontend at `simonrowe.dev` makes direct API calls to `api.simonrowe.dev`, both origins must be allowed.

**Alternatives considered**:
- Modifying Java source code: Unnecessary — the existing env-based configuration is sufficient.
- Using `*` wildcard: Insecure and doesn't work with credentials.

## WebSocket Endpoint Path

**Decision**: The WebSocket STOMP endpoint is at `/ws/chat` (not `/ws`).

**Rationale**: `WebSocketConfig.java` registers the STOMP endpoint at `/ws/chat`. The frontend `chatService.ts` builds the WebSocket URL by converting `VITE_API_BASE_URL` from HTTP(S) to WS(S) and appending `/ws/chat`. The nginx reverse proxy's `api.simonrowe.dev` server block must include WebSocket upgrade headers for this path.

**Alternatives considered**:
- Routing WebSocket through `simonrowe.dev` (frontend nginx): Would require modifying `frontend/nginx.conf` and adding WebSocket proxy support there. Keeping all backend traffic on `api.simonrowe.dev` is simpler.

## Frontend VITE_API_BASE_URL Build Arg

**Decision**: Add `VITE_API_BASE_URL` as a Docker build argument in `Dockerfile.frontend` and pass it in the GitHub Actions publish workflow.

**Rationale**: `VITE_API_BASE_URL` is used at build time (baked into the Vite bundle). Currently only `VITE_RECAPTCHA_SITE_KEY` is a build arg. The frontend `config/api.ts` reads `import.meta.env.VITE_API_BASE_URL` and defaults to empty string if not set. For the Docker deployment, it must be `https://api.simonrowe.dev`.

**Alternatives considered**:
- Setting the value in `frontend/.env` before build: Works for local builds but doesn't apply to CI-built images from GHCR.
- Runtime injection via nginx sub_filter: Fragile and adds complexity.

## Nginx Reverse Proxy Configuration

**Decision**: Create a new `nginx-proxy.conf` file with three server blocks: `simonrowe.dev`/`www.simonrowe.dev` → frontend, `api.simonrowe.dev` → backend (with WebSocket upgrade support).

**Rationale**: The existing `frontend/nginx.conf` handles SPA routing and API/upload proxying within the frontend container. The new reverse proxy sits in front of both containers and routes based on `Host` header. The `api.simonrowe.dev` server block needs `proxy_http_version 1.1`, `Upgrade`, and `Connection` headers for WebSocket support on `/ws/chat`.

**Alternatives considered**:
- Modifying the existing `frontend/nginx.conf`: This would mix concerns — the frontend nginx should only handle SPA serving and its own backend proxying.
- Using Traefik or Caddy: Adds a new dependency; nginx is already in use and well-understood.

## Backend Uploads Volume

**Decision**: Use a named Docker volume for `backend/uploads/` mapped to the container's uploads path.

**Rationale**: The backend's `UPLOADS_PATH` defaults to `uploads/` relative to the working directory. The GraalVM native image runs from a specific directory in the container. A named volume ensures uploaded media persists across container restarts.

**Alternatives considered**:
- Bind mount to host directory: Less portable and requires knowing the host path.
- No volume (ephemeral): Files lost on restart — unacceptable per FR-006.

## Cloudflare DNS Configuration

**Decision**: Use DNS-only mode (grey cloud) for all CNAME records pointing to Pinggy.

**Rationale**: Cloudflare's proxy mode (orange cloud) would terminate TLS and re-encrypt to Pinggy, potentially causing certificate conflicts since Pinggy already manages TLS for `*.simonrowe.dev`. DNS-only mode passes traffic directly to Pinggy.

**Alternatives considered**:
- Cloudflare proxied mode: Could cause double TLS termination, certificate mismatch, or WebSocket issues.

## Container Dependency Chain

**Decision**: Use the following dependency chain with health checks:
- MongoDB, Kafka, Elasticsearch: No dependencies (start in parallel)
- Backend: Depends on MongoDB (healthy), Kafka (healthy), Elasticsearch (healthy)
- Frontend: Depends on backend (healthy)
- Nginx reverse proxy: Depends on frontend (healthy), backend (healthy)
- Pinggy: Depends on nginx (healthy)
- OTel collector: No dependencies

**Rationale**: Services must wait for their dependencies to be ready. The backend needs all data stores. The frontend needs the backend for API proxying. The nginx reverse proxy needs both frontend and backend. Pinggy needs nginx to be ready before tunneling.

**Alternatives considered**:
- Flat startup with retries: Less reliable, harder to debug startup issues.
