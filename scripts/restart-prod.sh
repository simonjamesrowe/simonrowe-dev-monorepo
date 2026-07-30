#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$PROJECT_DIR/docker-compose.prod.yml"

echo "Pulling latest production images..."
docker compose -f "$COMPOSE_FILE" pull

echo "Recreating production services..."
docker compose -f "$COMPOSE_FILE" up -d

# nginx now resolves container names at request time through Docker DNS, but it
# still needs a reload when the mounted config gains a new route (for example
# software-factory or temporal-ui). Restarting after reconciliation covers that
# case and remains safe because nginx no longer requires every upstream to
# resolve at startup.
echo "Restarting nginx to load the current proxy configuration..."
docker compose -f "$COMPOSE_FILE" restart nginx

echo "Production services refreshed."
