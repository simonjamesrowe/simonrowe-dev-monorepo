# Implementation Plan: Add Portainer Container Management Console

**Branch**: `001-add-portainer` | **Date**: 2026-04-06 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/001-add-portainer/spec.md`

## Summary

Add Portainer Community Edition to the production Docker Compose stack, accessible at console.simonrowe.dev via the existing nginx reverse proxy and Pinggy tunnel. Portainer uses its own built-in authentication (not Auth0) and persists data in a named Docker volume. This is a pure infrastructure change — no application code modifications required.

## Technical Context

**Language/Version**: N/A (infrastructure-only change — Docker Compose YAML + Nginx config)
**Primary Dependencies**: Portainer CE Docker image (`portainer/portainer-ce:latest`), existing nginx reverse proxy
**Storage**: Named Docker volume for Portainer data (user accounts, settings, environment config)
**Testing**: Manual verification — navigate to console.simonrowe.dev, complete initial setup, verify container management
**Target Platform**: Raspberry Pi / ARM64 Docker host (same as existing production)
**Project Type**: Infrastructure addition to existing Docker Compose stack
**Performance Goals**: Portainer UI loads within 5 seconds, container list renders within 3 seconds
**Constraints**: Must not expose ports directly; must work through Pinggy tunnel; ARM64 compatible image required
**Scale/Scope**: Single admin user, ~10 containers to manage

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Monorepo with Separate Containers | PASS | Portainer added as a new service in docker-compose.prod.yml |
| II. Modern Java & React Stack | DEVIATION | Auth0 clause states "Auth0 MUST be the sole authentication provider" — see Complexity Tracking |
| III. Quality Gates | N/A | No application code changes; no tests, coverage, or style checks needed |
| IV. Observability & Operability | PASS | Portainer itself improves operability by providing container management UI |
| V. Simplicity & Incremental Delivery | PASS | Minimal change — 3 files modified, no new abstractions |
| VI. Admin CMS UX Standards | N/A | Not a CMS change |
| VII. Backup & Restore | N/A | Portainer data is self-contained and not part of application backups |
| VIII. Shell Scripting Standards | N/A | No new scripts |

**Post-Phase 1 Re-check**: No changes — design remains infrastructure-only.

## Project Structure

### Documentation (this feature)

```text
specs/001-add-portainer/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output (minimal — no application data model)
├── quickstart.md        # Phase 1 output
└── contracts/           # Phase 1 output (empty — no API contracts)
```

### Source Code (repository root)

```text
docker-compose.prod.yml              # Add portainer service + volume
config/nginx/nginx-proxy.conf        # Add console.simonrowe.dev server block
```

**Structure Decision**: This is an infrastructure-only change. No application source code is modified. Only the Docker Compose file and nginx configuration are updated.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Auth0 not used for Portainer auth | Portainer is an infrastructure ops tool, not part of the application. It has robust built-in auth with username/password. Using Auth0 would require Portainer Business Edition for full OIDC group mapping, adding cost and complexity for a single-admin tool. | Auth0 integration in Portainer CE is possible but adds unnecessary configuration complexity. The Auth0 principle applies to the application's user-facing authentication, not to third-party infrastructure tooling. |
