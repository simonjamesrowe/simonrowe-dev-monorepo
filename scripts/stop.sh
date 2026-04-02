#!/usr/bin/env bash
set -euo pipefail

echo "Stopping backend (port 8080)..."
lsof -ti:8080 2>/dev/null | xargs kill 2>/dev/null && echo "Backend stopped" || echo "Backend not running"

echo "Stopping frontend (port 5173)..."
lsof -ti:5173 2>/dev/null | xargs kill 2>/dev/null && echo "Frontend stopped" || echo "Frontend not running"
