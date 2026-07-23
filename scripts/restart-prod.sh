#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$PROJECT_DIR/docker-compose.prod.yml"

echo "Pulling latest production images..."
docker compose -f "$COMPOSE_FILE" pull

echo "Recreating production services..."
docker compose -f "$COMPOSE_FILE" up -d

# nginx has no `resolver` directive, so it resolves the frontend/backend/portainer/
# langfuse hostnames once at container startup and caches those IPs for its
# lifetime. `up -d` only recreates containers whose image/config changed, so a
# plain image refresh of frontend/backend gives them new container IPs while
# nginx (unchanged) keeps running and silently proxies to the old, now-dead
# addresses (502s / connection refused). All upstreams are confirmed up at this
# point (the `up -d` above respects `depends_on`), so it's always safe to bounce
# nginx here to force it to re-resolve.
echo "Restarting nginx to pick up any new upstream container addresses..."
docker compose -f "$COMPOSE_FILE" restart nginx

echo "Production services refreshed."
