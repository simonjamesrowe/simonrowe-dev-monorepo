# Data Model: Add Portainer Container Management Console

**Date**: 2026-04-06
**Feature**: [spec.md](spec.md)

## Overview

This feature introduces no application-level data model changes. Portainer manages its own internal data store (BoltDB at `/data` inside the container). The only data concern is ensuring the volume is persisted.

## Infrastructure Entities

### Portainer Service (Docker Compose)

- **Image**: `portainer/portainer-ce:latest`
- **Internal port**: 9000 (HTTP)
- **Volumes**: Docker socket (read-write), portainer-data volume
- **Restart policy**: `unless-stopped`
- **Dependencies**: None (standalone infrastructure tool)
- **Health check**: HTTP GET to port 9000

### Portainer Data Volume

- **Volume name**: `portainer-data`
- **Mount path**: `/data` inside container
- **Contents**: BoltDB database (users, settings, environment configs), TLS certificates (if any), custom templates
- **Lifecycle**: Created on first `docker compose up`, persists across restarts, only destroyed on explicit `docker volume rm`

### Nginx Server Block

- **Server name**: `console.simonrowe.dev`
- **Upstream**: `portainer:9000`
- **Features**: WebSocket upgrade support (required for container exec/logs)
- **Pattern**: Identical to existing `api.simonrowe.dev` server block

## Relationships

```text
Pinggy Tunnel (*.simonrowe.dev)
  └── Nginx Reverse Proxy (:80)
        ├── simonrowe.dev       → Frontend (:80)
        ├── api.simonrowe.dev   → Backend (:8080)
        └── console.simonrowe.dev → Portainer (:9000)   ← NEW
```

## No Application Data Changes

- No MongoDB collections added or modified
- No Elasticsearch indices affected
- No Kafka topics introduced
- No backend entity classes changed
