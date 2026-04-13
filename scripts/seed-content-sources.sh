#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JS_FILE="seed-content-sources.js"
CONTAINER_NAME="mongodb"

# Validate
if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
  echo "ERROR: MongoDB container '${CONTAINER_NAME}' is not running" >&2
  exit 1
fi

if [ ! -f "${SCRIPT_DIR}/${JS_FILE}" ]; then
  echo "ERROR: Script file ${JS_FILE} not found" >&2
  exit 1
fi

# Copy and execute
docker cp "${SCRIPT_DIR}/${JS_FILE}" "${CONTAINER_NAME}:/tmp/${JS_FILE}"
docker exec "${CONTAINER_NAME}" mongosh --quiet "/tmp/${JS_FILE}"
docker exec "${CONTAINER_NAME}" rm -f "/tmp/${JS_FILE}"

echo "Content sources seeded successfully."
