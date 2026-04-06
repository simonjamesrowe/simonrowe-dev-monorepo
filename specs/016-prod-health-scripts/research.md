# Research: Production Health Monitoring & Lifecycle Scripts

**Date**: 2026-04-06
**Feature**: 016-prod-health-scripts

## Decision 1: Docker Restart Policy Does NOT Restart Unhealthy Containers

**Decision**: A Docker HEALTHCHECK on the Pinggy container alone is insufficient. Docker's `restart: unless-stopped` only restarts containers that **exit**, not containers marked unhealthy. An unhealthy container remains running.

**Rationale**: Docker documentation and community sources confirm that health checks and restart policies are independent systems. An unhealthy container is still a running container — Docker takes no action beyond updating the health status label.

**Alternatives considered**:
- **docker-autoheal sidecar**: Adds another container to monitor health status and restart unhealthy containers. Rejected — adds infrastructure complexity for a single-service problem. Violates Principle V (simplicity).
- **Self-terminating health check** (`kill 1` on failure): The health check script kills PID 1 inside the container, forcing it to exit so the restart policy triggers. Viable but fragile — race conditions between health check retries and process death.
- **External monitor script** (chosen): A host-level script that checks external reachability and restarts the Pinggy container via `docker compose restart`. Simplest solution, covers all failure modes.

## Decision 2: Pinggy Container Health Check Feasibility

**Decision**: Do not add a traditional Docker HEALTHCHECK to the Pinggy container. Instead, rely entirely on the external monitor script.

**Rationale**: The `pinggy/pinggy` Docker image contents are not publicly documented. It likely does not include `curl`, `wget`, or other HTTP tools. The image is purpose-built for tunneling only. Adding a health check that can't actually verify tunnel connectivity provides false confidence. The external reachability check is the only reliable way to verify the tunnel works end-to-end.

**Alternatives considered**:
- **Process-level check** (`pgrep`): Only confirms the process is running, not that the tunnel is active. Rejected — doesn't detect the failure mode we care about.
- **TCP connect to nginx:80 from inside Pinggy**: Confirms nginx is reachable from within the Docker network, not that the tunnel to the internet works. Rejected — wrong layer.
- **Install curl in a custom image**: Requires maintaining a fork of the Pinggy image. Rejected — maintenance burden.

## Decision 3: External Reachability Check Method

**Decision**: The monitor script will use `curl` from the host to check `https://simonrowe.dev` with a short timeout. Multiple consecutive failures trigger a Pinggy container restart.

**Rationale**: This tests the full path: public DNS → Pinggy edge → SSH tunnel → nginx → frontend/backend. It's the same path real users take. Using `curl` from the host avoids depending on tools inside the Pinggy container. The host (Raspberry Pi, Debian-based) has `curl` available.

**Alternatives considered**:
- **DNS-only check**: Doesn't verify the tunnel is active, only that DNS records exist. Rejected.
- **Ping to Pinggy edge**: ICMP doesn't verify HTTP tunnel connectivity. Rejected.
- **Sidecar container for checking**: Adds complexity. The host already has curl. Rejected.

## Decision 4: Monitor Architecture

**Decision**: Implement the monitor as a bash script run via systemd timer (or nohup as fallback) on the host. Not as a Docker container.

**Rationale**: The monitor needs to run `docker compose restart pinggy`, which requires Docker socket access. Running it on the host is simpler than mounting the Docker socket into yet another container. A systemd timer ensures the monitor survives reboots and terminal disconnects. Bash aligns with Constitution Principle VIII (Shell Scripting Standards).

**Alternatives considered**:
- **Docker container with socket mount**: Adds complexity, requires building a custom image with curl + docker CLI. Rejected per Principle V.
- **Cron job**: Works but less observable than systemd. Cron has no built-in logging, retry, or status visibility. Rejected in favour of systemd if available, with cron as documented fallback.

## Decision 5: SSH Keep-Alive Configuration

**Decision**: Not applicable — the current Pinggy setup uses the Pinggy CLI (`--token`, `-l` flags), not raw SSH. The CLI may have its own reconnection logic. The external monitor provides defense-in-depth regardless of CLI behaviour.

**Rationale**: The docker-compose.prod.yml uses `pinggy/pinggy` image with `--token` and `-l` arguments, which is the Pinggy CLI format, not `ssh -p 443 ...`. The CLI's internal reconnection behaviour is undocumented, so we cannot rely on it alone.

## Decision 6: Restart Backoff Strategy

**Decision**: Allow a maximum of 3 restart attempts within a 10-minute window. After exhausting retries, log a critical message and wait for the backoff period to expire before trying again.

**Rationale**: Prevents restart storms when the issue isn't recoverable by restarting Pinggy (e.g., token expired, Pinggy service outage, host internet down). The 10-minute window gives time for transient issues to resolve while preventing rapid cycling.

**Alternatives considered**:
- **Exponential backoff**: More sophisticated but harder to reason about in bash. The simple window-based approach is sufficient for a single-service monitor.
- **No limit**: Risk of restart storms consuming resources. Rejected.
