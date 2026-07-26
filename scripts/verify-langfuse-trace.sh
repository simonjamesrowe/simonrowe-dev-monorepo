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
# --expect-session  fail unless the newest `chat-turn` trace in the window has a sessionId
#                   (proves the chat-turn span survived Alloy's ai_only filter and that
#                   Langfuse applied its session.id from a non-root span)
# --expect-io       fail unless that same trace has non-empty input AND output (proves
#                   ChatTurnTracer's langfuse.trace.input/.output reached Langfuse)
#
# Both flags assert on the newest trace **named chat-turn**, not the newest trace overall.
# That is deliberate: chat-turn is the only span that ever carries a session id, and tool-call
# / vector-store / embedding spans are currently orphaned into their own root traces (Defect B,
# lost observation context across Schedulers.boundedElastic). Those orphans land a few hundred
# milliseconds AFTER the chat-turn span they belong to, so "newest trace overall" is almost
# never chat-turn. The orphan count is reported on every run so that defect stays visible
# instead of being hidden by the name filter.
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

# Shared window filter, reused by the chat-turn lookup and the orphan census below.
window=""
if [[ -n "$SINCE_MINUTES" ]]; then
  # Langfuse accepts an ISO-8601 fromTimestamp filter.
  from_ts="$(date -u -v-"${SINCE_MINUTES}"M +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
    || date -u -d "-${SINCE_MINUTES} minutes" +%Y-%m-%dT%H:%M:%SZ)"
  window="&fromTimestamp=${from_ts}"
  echo "Checking for a Langfuse trace since ${from_ts} ..."
else
  echo "Checking for any Langfuse trace ..."
fi

query="?limit=1&orderBy=timestamp.desc${window}"

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
    # Report the orphan count first, so it is visible even on a run that then fails. Orphans are
    # traces that are not chat-turn and carry no session id, i.e. spans that lost their parent
    # and became their own root. Capped at the newest 100 traces in the window.
    census="$(curl -sS -u "${LANGFUSE_PUBLIC_KEY}:${LANGFUSE_SECRET_KEY}" \
      "${LANGFUSE_HOST%/}/api/public/traces?limit=100${window}")"
    printf '%s' "$census" | python3 -c '
import json, sys
payload = json.load(sys.stdin)
traces = payload.get("data") or []
total = (payload.get("meta") or {}).get("totalItems", len(traces))
orphans = [t for t in traces
           if t.get("name") != "chat-turn" and not t.get("sessionId")]
turns = [t for t in traces if t.get("name") == "chat-turn"]
sampled = " (sampled from the newest %d of %d)" % (len(traces), total) if total > len(traces) else ""
print("Note: %d orphaned span-trace(s) alongside %d chat-turn trace(s) in window%s"
      % (len(orphans), len(turns), sampled))
if orphans:
    print("      Expected while span orphaning is unresolved (Defect B): tool-call, vector-store,")
    print("      embedding and guardrail-classifier spans start their own trace instead of nesting")
    print("      under chat-turn. Not a failure of this check.")
' || echo "Note: could not compute the orphan count (non-fatal)."

    turn="$(curl -sS -u "${LANGFUSE_PUBLIC_KEY}:${LANGFUSE_SECRET_KEY}" \
      "${LANGFUSE_HOST%/}/api/public/traces?limit=1&orderBy=timestamp.desc&name=chat-turn${window}")"
    summary="$(printf '%s' "$turn" | python3 -c '
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

    if [[ "$summary" == "NO_TRACE" ]]; then
      echo "FAIL: no trace named 'chat-turn' in the window. The chat-turn span never reached" >&2
      echo "      Langfuse: either no chat message was sent, or Alloy is running a config without" >&2
      echo "      langfuse.trace.name in the ai_only keep-list and dropped it. Restart Alloy after" >&2
      echo "      pulling config changes, then send a chat message and retry." >&2
      exit 1
    fi

    echo "Newest chat-turn trace: ${summary}"

    if [[ "$EXPECT_SESSION" == "true" && "$summary" != *"session=yes"* ]]; then
      echo "FAIL: the chat-turn trace exists but has no sessionId, so Alloy delivered the span and" >&2
      echo "      the ai_only keep-list is fine. Check that ChatTurnTracer set the session.id" >&2
      echo "      attribute (LangfuseAttributes.SESSION_ID) on the observation and that the" >&2
      echo "      sessionId reaching it is not null or blank." >&2
      exit 1
    fi
    if [[ "$EXPECT_IO" == "true" ]]; then
      if [[ "$summary" != *"input=yes"* || "$summary" != *"output=yes"* ]]; then
        echo "FAIL: the chat-turn trace exists but has empty input and/or output. Check that" >&2
        echo "      ChatTurnTracer set langfuse.trace.input at start and langfuse.trace.output at" >&2
        echo "      finish, and that langfuse.content-capture-enabled is true" >&2
        echo "      (LANGFUSE_CONTENT_CAPTURE_ENABLED)." >&2
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
