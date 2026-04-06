#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "Stopping production services..."
docker compose -f "$PROJECT_DIR/docker-compose.prod.yml" down
echo "Production services stopped. Data volumes preserved."

exit 0
