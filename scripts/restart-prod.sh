#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$PROJECT_DIR/docker-compose.prod.yml"

echo "Pulling latest production images..."
docker compose -f "$COMPOSE_FILE" pull

echo "Recreating production services..."
docker compose -f "$COMPOSE_FILE" up -d

echo "Production services refreshed."
