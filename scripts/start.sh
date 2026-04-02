#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "Starting backend and frontend..."

"$SCRIPT_DIR/start-backend.sh" &
BACKEND_PID=$!

"$SCRIPT_DIR/start-frontend.sh" &
FRONTEND_PID=$!

trap '"$SCRIPT_DIR/stop.sh"' EXIT INT TERM

wait $BACKEND_PID $FRONTEND_PID
