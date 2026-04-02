#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

ENV_FILE="$PROJECT_DIR/frontend/.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "Error: $ENV_FILE not found. Run the conductor setup script or copy ~/workspace/env to frontend/.env"
  exit 1
fi

cd "$PROJECT_DIR/frontend"
npm install --silent
exec npm run dev
