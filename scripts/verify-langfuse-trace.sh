#!/usr/bin/env bash
set -euo pipefail

# Verify the Langfuse trace path end-to-end: query the Langfuse public API with the
# project keys and confirm at least one trace exists (optionally created within a recent
# window). Read-only — it never mutates Langfuse.
#
# Usage:
#   scripts/verify-langfuse-trace.sh                    # checks any trace exists
#   scripts/verify-langfuse-trace.sh --since-minutes 5
#   scripts/verify-langfuse-trace.sh --since-minutes 5 --expect-session --expect-io
#
# --expect-session  fail unless the newest matching trace has a sessionId (proves the
#                   chat-turn span survived Alloy's ai_only filter and Langfuse applied it)
# --expect-io       fail unless that trace has non-empty input AND output (proves content
#                   capture is working end to end)
#
# Requires python3 for the field assertions (present on macOS and Raspberry Pi OS).
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
EXPECT_SESSION="false"
EXPECT_IO="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --since-minutes)
      SINCE_MINUTES="${2:-}"
      shift 2
      ;;
    --expect-session)
      EXPECT_SESSION="true"
      shift
      ;;
    --expect-io)
      EXPECT_IO="true"
      shift
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

query="?limit=1&orderBy=timestamp.desc"
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

  if [[ "$EXPECT_SESSION" == "true" || "$EXPECT_IO" == "true" ]]; then
    summary="$(printf '%s' "$response" | python3 -c '
import json, sys
payload = json.load(sys.stdin)
traces = payload.get("data") or []
if not traces:
    print("NO_TRACE")
    sys.exit(0)
trace = traces[0]
def present(value):
    return "yes" if value not in (None, "", [], {}) else "no"
print("name=%s session=%s input=%s output=%s" % (
    trace.get("name") or "<unnamed>",
    present(trace.get("sessionId")),
    present(trace.get("input")),
    present(trace.get("output")),
))
')"
    echo "Newest trace: ${summary}"

    if [[ "$EXPECT_SESSION" == "true" && "$summary" != *"session=yes"* ]]; then
      echo "FAIL: newest trace has no sessionId. The chat-turn span carrying session.id was" >&2
      echo "      dropped, or Alloy is running a config without langfuse.trace.name in the" >&2
      echo "      ai_only keep-list. Restart Alloy after pulling config changes." >&2
      exit 1
    fi
    if [[ "$EXPECT_IO" == "true" ]]; then
      if [[ "$summary" != *"input=yes"* || "$summary" != *"output=yes"* ]]; then
        echo "FAIL: newest trace has empty input and/or output. Check that" >&2
        echo "      langfuse.content-capture-enabled is true and that LangfuseContentObservationFilter" >&2
        echo "      is registered." >&2
        exit 1
      fi
    fi
  fi

  exit 0
fi

echo "FAIL: no matching traces found. Send a chat message and retry, or check that"
echo "      LANGFUSE_PUBLIC_KEY/SECRET_KEY match the provisioned project keys and that"
echo "      Alloy is forwarding to ${LANGFUSE_HOST%/}/api/public/otel."
exit 1
