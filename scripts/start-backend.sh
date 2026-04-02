#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

ENV_FILE="$PROJECT_DIR/backend/.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "Error: $ENV_FILE not found. Run the conductor setup script or copy ~/workspace/env to backend/.env"
  exit 1
fi

set -a
source "$ENV_FILE"
set +a

cd "$PROJECT_DIR/backend"
export UPLOADS_PATH=uploads/
exec ../gradlew bootRun
