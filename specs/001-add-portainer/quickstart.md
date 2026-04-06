# Quickstart: Add Portainer Container Management Console

**Date**: 2026-04-06
**Feature**: [spec.md](spec.md)

## What Changed

Three files are modified to add Portainer to the production stack:

| File | Change |
|------|--------|
| `docker-compose.prod.yml` | Add `portainer` service + `portainer-data` volume |
| `config/nginx/nginx-proxy.conf` | Add `console.simonrowe.dev` server block |

## Files to Modify

### 1. `docker-compose.prod.yml`

**Add service** (after `alloy` service, before `volumes:`):

```yaml
  portainer:
    image: portainer/portainer-ce:latest
    restart: unless-stopped
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - portainer-data:/data
    healthcheck:
      test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:9000"]
      interval: 10s
      timeout: 5s
      retries: 3
```

**Add volume** (in `volumes:` section):

```yaml
  portainer-data:
```

**Key decisions**:
- No `ports:` directive — Portainer is only accessible through nginx (FR-007)
- No `depends_on:` — Portainer is independent of application services
- Health check uses `wget` (available in Portainer's Alpine-based image)
- Docker socket mounted read-write for container management operations

### 2. `config/nginx/nginx-proxy.conf`

**Add server block** (after the `api.simonrowe.dev` block):

```nginx
server {
    listen 80;
    server_name console.simonrowe.dev;

    location / {
        proxy_pass http://portainer:9000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # WebSocket upgrade support (for container console and log streaming)
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

**Key decisions**:
- WebSocket support is critical — without it, container exec and log streaming fail
- Pattern matches existing `api.simonrowe.dev` block

## Pre-deployment Checklist

1. Verify `console.simonrowe.dev` DNS resolves (check Cloudflare for CNAME record)
2. Deploy with `docker compose -f docker-compose.prod.yml up -d`
3. Navigate to `https://console.simonrowe.dev`
4. Complete Portainer initial setup (create admin account)
5. Add the local Docker environment in Portainer
6. Verify container list shows all running services

## Post-deployment Verification

- [ ] `console.simonrowe.dev` shows Portainer login page
- [ ] Admin account creation wizard completes successfully
- [ ] All production containers visible in dashboard
- [ ] Container logs viewable through UI
- [ ] Container exec (console) works through UI
- [ ] Portainer not accessible on host port 9000 directly
