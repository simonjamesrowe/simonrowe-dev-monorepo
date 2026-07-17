#!/usr/bin/env bash
set -euo pipefail

# Verify the Langfuse trace path end-to-end: query the Langfuse public API with the
# project keys and confirm at least one trace exists (optionally created within a recent
# window). Read-only — it never mutates Langfuse.
#
# Usage:
#   scripts/verify-langfuse-trace.sh                 # checks any trace exists
#   scripts/verify-langfuse-trace.sh --since-minutes 5
#
# Env (falls back to values in the deploy-dir .env if present):
#   LANGFUSE_HOST          default https://langfuse.simonrowe.dev
#   LANGFUSE_PUBLIC_KEY    project public key (basic-auth username)
#   LANGFUSE_SECRET_KEY    project secret key (basic-auth password)
#
# Typical flow: send a chat message on the site, then run this within a minute.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Source .env from the project dir if the keys are not already in the environment.
if [[ -z "${LANGFUSE_PUBLIC_KEY:-}" || -z "${LANGFUSE_SECRET_KEY:-}" ]]; then
  if [[ -f "$PROJECT_DIR/.env" ]]; then
    # shellcheck disable=SC1091
    set -a
    . "$PROJECT_DIR/.env"
    set +a
  fi
fi

LANGFUSE_HOST="${LANGFUSE_HOST:-https://langfuse.simonrowe.dev}"
SINCE_MINUTES=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --since-minutes)
      SINCE_MINUTES="${2:-}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ -z "${LANGFUSE_PUBLIC_KEY:-}" || -z "${LANGFUSE_SECRET_KEY:-}" ]]; then
  echo "ERROR: LANGFUSE_PUBLIC_KEY and LANGFUSE_SECRET_KEY must be set (env or $PROJECT_DIR/.env)." >&2
  exit 1
fi

query="?limit=1"
if [[ -n "$SINCE_MINUTES" ]]; then
  # Langfuse accepts an ISO-8601 fromTimestamp filter.
  from_ts="$(date -u -v-"${SINCE_MINUTES}"M +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
    || date -u -d "-${SINCE_MINUTES} minutes" +%Y-%m-%dT%H:%M:%SZ)"
  query="${query}&fromTimestamp=${from_ts}"
  echo "Checking for a Langfuse trace since ${from_ts} ..."
else
  echo "Checking for any Langfuse trace ..."
fi

url="${LANGFUSE_HOST%/}/api/public/traces${query}"

response="$(curl -sS -u "${LANGFUSE_PUBLIC_KEY}:${LANGFUSE_SECRET_KEY}" "$url")"

# The list endpoint returns { "data": [ ... ], "meta": { "totalItems": N } }.
total="$(printf '%s' "$response" | grep -o '"totalItems"[[:space:]]*:[[:space:]]*[0-9]*' \
  | grep -o '[0-9]*$' | head -n1 || true)"

if [[ -z "$total" ]]; then
  echo "ERROR: unexpected response from Langfuse API:" >&2
  printf '%s\n' "$response" >&2
  exit 1
fi

if [[ "$total" -gt 0 ]]; then
  echo "OK: found ${total} matching trace(s) in the Langfuse project."
  exit 0
fi

echo "FAIL: no matching traces found. Send a chat message and retry, or check that"
echo "      LANGFUSE_PUBLIC_KEY/SECRET_KEY match the provisioned project keys and that"
echo "      Alloy is forwarding to ${LANGFUSE_HOST%/}/api/public/otel."
exit 1
