# Langfuse Infrastructure & Integration Design

## Overview
This design outlines the integration of Langfuse into the existing application stack. The goal is to provide observability for Spring AI components by running Langfuse locally and in production via Docker Compose, integrating it with Auth0 for SSO, and routing OpenTelemetry (OTLP) traces from the backend via Grafana Alloy.

## 1. Architecture & Services
We will expand the existing Docker Compose stack to include Langfuse and its dependencies.

### Services to Add:
*   **`langfuse-db`**: A PostgreSQL 15 container dedicated to Langfuse data storage. Persistent volume mapping will be added to ensure data survives container restarts.
*   **`langfuse`**: The Langfuse application container.
    *   **Local (`docker-compose.yml`)**: Exposed directly on port `3000`.
    *   **Production (`docker-compose.prod.yml`)**: Placed on the internal network. The Nginx reverse proxy will be updated to route traffic from `langfuse.simonrowe.dev` to this container.

## 2. Authentication & Configuration
Langfuse will be secured via Auth0 SSO, and necessary cryptographic keys will be initialized.

*   **Environment Variables**: The `.env.example` file will be updated with the following placeholders:
    *   Auth0: `AUTH_AUTH0_CLIENT_ID`, `AUTH_AUTH0_CLIENT_SECRET`, `AUTH_AUTH0_ISSUER_BASE_URL`
    *   Langfuse Security: `NEXTAUTH_SECRET`, `SALT`, `ENCRYPTION_KEY`
    *   Langfuse Config: `NEXTAUTH_URL` (set to `https://langfuse.simonrowe.dev` in prod, `http://localhost:3000` locally).
*   **Documentation**: `docs/auth0-setup.md` will be updated to include instructions for setting up the Langfuse application in Auth0, emphasizing the required callback URL: `https://langfuse.simonrowe.dev/api/auth/callback/auth0` (and `http://localhost:3000/api/auth/callback/auth0` for local).

## 3. Telemetry Pipeline
Rather than adding a Langfuse-specific SDK to the Spring Boot application, we will leverage the existing OpenTelemetry standard and Grafana Alloy pipeline.

*   **Grafana Alloy (`config/alloy/config.alloy`)**:
    *   Add a new OTLP HTTP exporter block (`otelcol.exporter.otlphttp "langfuse"`) pointing to the local Langfuse endpoint (`http://langfuse:3000/api/public/otel`).
    *   Update the existing batch processor (`otelcol.processor.batch "default"`) so that its output array includes BOTH the Tempo exporter and the new Langfuse exporter. This effectively forks the traces, sending Spring AI metrics to Langfuse without requiring any changes to the backend codebase.
