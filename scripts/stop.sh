#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "Stopping backend (port 8080)..."
lsof -ti:8080 2>/dev/null | xargs kill 2>/dev/null && echo "Backend stopped" || echo "Backend not running"

echo "Stopping frontend (port 5173)..."
lsof -ti:5173 2>/dev/null | xargs kill 2>/dev/null && echo "Frontend stopped" || echo "Frontend not running"

echo "Stopping infrastructure services (MongoDB, Kafka, Elasticsearch)..."
docker compose -f "$PROJECT_DIR/docker-compose.yml" down && echo "Infrastructure stopped" || echo "Infrastructure not running"
