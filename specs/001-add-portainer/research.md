# Research: Add Portainer Container Management Console

**Date**: 2026-04-06
**Feature**: [spec.md](spec.md)

## R1: Portainer CE Docker Image for ARM64

**Decision**: Use `portainer/portainer-ce:latest` — multi-arch image supporting ARM64.

**Rationale**: The production host is a Raspberry Pi (ARM64). Portainer CE publishes multi-architecture images on Docker Hub that include `linux/arm64`. No special image variant is needed.

**Alternatives considered**:
- `portainer/portainer-ce:alpine` — lighter but less commonly documented; `latest` is the standard recommendation
- Building from source — unnecessary overhead for a pre-built tool

## R2: Portainer Authentication Strategy

**Decision**: Use Portainer's built-in authentication (username/password) with no Auth0 integration.

**Rationale**: Portainer CE includes built-in user management. On first launch, it presents an admin account creation wizard. This is the simplest approach for a single-admin setup. Portainer CE does support OAuth2 (including Auth0) via Settings > Authentication, but this adds configuration complexity with no benefit for one user.

**Alternatives considered**:
- Auth0 OAuth2 integration in Portainer CE — possible but adds unnecessary config (Client ID, Secret, Authorization URL, Token URL, Resource URL). Would need to create a separate Auth0 application for Portainer.
- Portainer Business Edition with full OIDC — paid product, overkill for single admin
- nginx basic auth in front of Portainer — would create a double-login experience and doesn't add meaningful security over Portainer's own auth

## R3: Portainer Internal Port Selection

**Decision**: Use HTTP port 9000 internally, not HTTPS 9443.

**Rationale**: TLS is terminated at the Pinggy tunnel layer (consistent with all other services — frontend, backend, and API all use HTTP internally). Running Portainer on HTTPS internally would require handling self-signed certificates in the nginx proxy (`proxy_ssl_verify off`), adding unnecessary complexity.

**Alternatives considered**:
- HTTPS port 9443 internally — would require `proxy_pass https://portainer:9443` and `proxy_ssl_verify off` in nginx config. No security benefit since traffic stays within the Docker network.

## R4: Nginx Reverse Proxy Configuration for Portainer

**Decision**: Add a new `server` block in `nginx-proxy.conf` for `console.simonrowe.dev` with WebSocket upgrade support.

**Rationale**: The existing nginx config already uses `server_name` based routing for `simonrowe.dev` and `api.simonrowe.dev`. Adding a third server block for `console.simonrowe.dev` follows the established pattern. WebSocket headers are required for Portainer's container console (exec) and log streaming features.

**Alternatives considered**:
- Separate nginx config file for Portainer — would require mounting a second config file and changing the nginx service volumes. Keeping everything in one file is simpler and consistent with current setup.
- Path-based routing (e.g., simonrowe.dev/console) — Portainer doesn't support running under a subpath without BASE_URL configuration, and subdomain routing is cleaner.

## R5: Docker Socket Access

**Decision**: Mount `/var/run/docker.sock` read-write into the Portainer container.

**Rationale**: Portainer needs Docker socket access to manage containers (start, stop, restart, view logs, exec). The backend container already mounts the Docker socket for the redeploy feature, so this is an established pattern in this stack. Portainer requires read-write access (not read-only) to perform management operations.

**Alternatives considered**:
- Docker TCP API — would require enabling Docker's TCP listener on the host, introducing additional attack surface. Socket mount is safer and already in use.
- Portainer Agent — used in multi-node setups, unnecessary for a single-host deployment.

## R6: Portainer Data Persistence

**Decision**: Use a named Docker volume `portainer-data` mapped to `/data` inside the container.

**Rationale**: Portainer stores its database (BoltDB), user accounts, environment configs, and settings in `/data`. A named volume ensures this survives container restarts and redeployments. This follows the same pattern as `mongodb-data`, `kafka-data`, and `elasticsearch-data` in the existing stack.

**Alternatives considered**:
- Bind mount to host directory — would work but named volumes are the established convention in this stack and are managed by Docker.
- No persistence — would require re-creating the admin account after every restart, which is unacceptable.

## R7: Pinggy Wildcard Domain Support

**Decision**: Assume `*.simonrowe.dev` wildcard is already configured in Pinggy and DNS.

**Rationale**: The existing setup routes `simonrowe.dev`, `www.simonrowe.dev`, and `api.simonrowe.dev` through Pinggy. This implies wildcard domain support is active. Cloudflare DNS likely has a CNAME wildcard or individual records for each subdomain. If `console.simonrowe.dev` is not yet routed, a DNS record may need to be added in Cloudflare.

**Action item**: Verify that `console.simonrowe.dev` resolves correctly before deploying. If not, add a CNAME record in Cloudflare pointing to the Pinggy endpoint.
