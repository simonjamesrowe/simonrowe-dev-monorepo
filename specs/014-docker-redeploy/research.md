# Research: Docker Redeploy from Admin Console

## Decision 1: Docker Client Approach

**Decision**: Use `ProcessBuilder` to invoke `docker compose up -d --pull always` rather than a Java Docker client library.

**Rationale**:
- The constitution mandates GraalVM native image via `bootBuildImage`. docker-java has documented GraalVM compatibility issues ([docker-java#2451](https://github.com/docker-java/docker-java/issues/2451)) requiring manual `reflect-config.json`, `--initialize-at-run-time` workarounds, and is not plug-and-play.
- `ProcessBuilder` is part of `java.lang` — zero GraalVM issues.
- Docker Compose already manages all container configuration (volumes, env vars, networks, health checks, dependencies). Using `docker compose up -d --pull always` is a single command that handles image pulling, container recreation, and config preservation automatically.
- Using the raw Docker Engine API (docker-java) would require manually inspecting containers, mapping their config, and recreating them — dozens of API calls with complex field mapping vs. one shell command.

**Alternatives considered**:
- **docker-java with httpclient5 transport**: Viable but requires GraalVM reflection config, `--initialize-at-run-time` flags for `NamedPipeSocket$Kernel32` and `NTLMEngineImpl`, and complex container recreation logic. Overkill for this use case.
- **Direct HTTP to Docker Engine API via Unix socket**: Java's `HttpClient` does not support Unix domain sockets. Would require reimplementing an HTTP client over raw `SocketChannel` — impractical.
- **docker-java-api (lightweight alternative)**: Less mature, same GraalVM concerns, same container recreation complexity.

---

## Decision 2: Self-Restart Strategy

**Decision**: Orchestrate restart in a specific order — pull all images first, restart frontend and nginx, persist operation status as COMPLETED to MongoDB, then restart backend last. The backend's `unless-stopped` restart policy ensures it comes back up automatically.

**Rationale**:
- The backend cannot observe its own restart completing. By persisting the final status to MongoDB before restarting itself, the admin will see "Completed" when they refresh the page after the backend comes back.
- Restarting frontend/nginx first ensures those services are already running the new version when the backend comes back up.
- The SSE connection will drop when the backend restarts. The frontend must handle this gracefully.

**Alternatives considered**:
- **External orchestrator (sidecar container)**: A separate container that receives the restart command and manages the process. More robust but violates Principle V (Simplicity) — unnecessary complexity for a single-admin personal site.
- **Async fire-and-forget with delay**: Use `ProcessBuilder` with `nohup` or a detached process so the restart command survives the backend shutdown. Adds complexity and is harder to debug.

---

## Decision 3: Docker CLI Availability in Native Image Container

**Decision**: Mount the Docker CLI binary and Docker Compose plugin from the host into the backend container, along with the Docker socket and the `docker-compose.prod.yml` file.

**Rationale**:
- Cloud Native Buildpacks (required by constitution) produce minimal runtime images without Docker CLI.
- Mounting the host's Docker binary is a well-established pattern for "Docker-in-Docker" scenarios.
- The `docker-compose.prod.yml` file must also be accessible for `docker compose -f` to work.

**Required docker-compose.prod.yml changes**:
```yaml
backend:
  volumes:
    - backend-uploads:/workspace/uploads
    - /var/run/docker.sock:/var/run/docker.sock:ro
    - /usr/local/bin/docker:/usr/local/bin/docker:ro
    - /usr/local/lib/docker/cli-plugins:/usr/local/lib/docker/cli-plugins:ro
    - ./docker-compose.prod.yml:/workspace/docker-compose.prod.yml:ro
    - ./.env:/workspace/.env:ro
```

**Alternatives considered**:
- **Custom buildpack with Docker CLI**: Adding a custom buildpack layer with Docker CLI. More complex build setup, deviates from standard buildpacks.
- **Install Docker CLI at container startup**: Using an init container or entrypoint script. Fragile and slows startup.

---

## Decision 4: Selective Service Restart

**Decision**: Use `docker compose up -d --pull always backend frontend nginx` to target only application services, not infrastructure.

**Rationale**:
- FR-009 requires only application containers (backend, frontend, nginx) to be redeployed.
- Infrastructure services (MongoDB, Kafka, Elasticsearch) use fixed version tags and should never be restarted during a code deployment.
- Docker Compose's service-specific `up` command handles this naturally.

---

## Decision 5: Progress Tracking for ProcessBuilder

**Decision**: Read stdout/stderr from the `docker compose` process line-by-line and map to progress updates via the existing SSE mechanism.

**Rationale**:
- `docker compose up -d --pull always` outputs progress for image pulls and container recreation.
- We can parse this output to provide meaningful progress messages (e.g., "Pulling backend image...", "Recreating frontend container...").
- Progress percentage can be estimated based on known steps (pull backend: 0-25%, pull frontend: 25-50%, restart frontend/nginx: 50-75%, restart backend: 75-100%).

---

## Decision 6: Frontend SSE Reconnection

**Decision**: Add automatic reconnection logic to the SSE progress client when the connection drops during a redeploy.

**Rationale**:
- When the backend restarts, the SSE connection will terminate.
- The frontend should detect this, show a "Reconnecting..." state, and poll until the backend is back.
- Once reconnected, it should fetch the final operation status from the `/status` endpoint.
