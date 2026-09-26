#!/usr/bin/env bash
set -uo pipefail

# Off-Pi heartbeat for production. Run every 15 minutes by
# .github/workflows/prod-heartbeat.yml, on GitHub's runners, and exits non-zero
# when production looks wrong - GitHub then emails the failure.
#
# WHY IT RUNS SOMEWHERE ELSE
# --------------------------
# Every alarm this stack has lives on the Pi and needs the Pi's outbound network:
# monitor-prod.sh heals but reports to a local log file, the log watch reads
# Grafana Cloud Loki from software-factory, and it files through Linear, also
# from software-factory. When the Pi itself loses the network, or its containers
# lose DNS, all of them go quiet together and quiet reads as healthy. Two real
# outages had exactly that shape:
#
#   - August 2026: Grafana Cloud's free-tier allowance ran out and Loki accepted
#     nothing for three weeks while alloy reported `healthy`.
#   - 2026-09-24/26: the Pi rebooted with the Wi-Fi down, Docker started the stack
#     with no upstream DNS, and for ~34 hours alloy shipped nothing,
#     Dependency-Track's Auth0 sign-in was gone and software-factory could reach
#     neither Loki nor Linear. Nobody was told; it was noticed by hand.
#
# A heartbeat that runs off the Pi is the one alarm that cannot be taken out by
# the same fault it is meant to report.
#
# WHAT IT CHECKS
# --------------
# 1. Loki has received logs from the stack in the last LOG_SILENCE_MINUTES. The
#    stack logs continuously, so silence means shipping has stopped - quota,
#    credentials, alloy, DNS, or the Pi being offline. An error from Loki itself
#    fails too, as "cannot check": an unanswerable question is not a pass.
# 2. Each public hostname serves. Retried for ~RETRY_DELAY x (ATTEMPTS-1) so a
#    deploy's maintenance page (503 for a few minutes by design) is not an alarm.
# 3. Dependency-Track reports OIDC available. It returns 200 either way, and says
#    `false` when the apiserver cannot fetch Auth0's discovery document - which is
#    how the 2026-09 DNS outage presented, and is an end-to-end test of outbound
#    DNS from inside the stack that no status code shows.

LOKI_ENDPOINT=${GRAFANA_CLOUD_LOKI_ENDPOINT:-}
LOKI_USER=${GRAFANA_CLOUD_LOKI_USER:-}
LOKI_TOKEN=${GRAFANA_CLOUD_API_KEY:-}
LOKI_SELECTOR=${LOKI_SELECTOR:-'{container=~"simonrowe-dev-monorepo-.+"}'}
LOG_SILENCE_MINUTES=${LOG_SILENCE_MINUTES:-30}

ATTEMPTS=${ATTEMPTS:-6}
RETRY_DELAY=${RETRY_DELAY:-60}

# "<name>|<url>|<expectation>": an HTTP status, or `body=<text>` for a 200 whose
# trimmed body must equal <text>. Same probe URLs as monitor-prod.sh's ENDPOINTS,
# for the same reasons (api has no route at `/`, and so on).
ENDPOINTS=(
  "www|https://www.simonrowe.dev/|200"
  "api|https://api.simonrowe.dev/api/profile|200"
  "dependency-track api|https://dependency-track.simonrowe.dev/api/version|200"
  "dependency-track sign-in|https://dependency-track.simonrowe.dev/api/v1/oidc/available|body=true"
  "langfuse|https://langfuse.simonrowe.dev/|200"
  "temporal|https://temporal.simonrowe.dev/|200"
  "console|https://console.simonrowe.dev/|200"
  "coparents|https://coparents.simonrowe.dev/|200"
)

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

problems=()
summary=()

report() {
  summary+=("$1")
  echo "$1"
}

# ---------------------------------------------------------------------------
# 1. Log freshness
# ---------------------------------------------------------------------------
check_logs() {
  if [[ -z "$LOKI_ENDPOINT" || -z "$LOKI_USER" || -z "$LOKI_TOKEN" ]]; then
    problems+=("logs: cannot check - GRAFANA_CLOUD_LOKI_ENDPOINT/_USER/GRAFANA_CLOUD_API_KEY not set")
    report "FAIL  logs: Loki credentials are not configured"
    return
  fi

  # The configured endpoint is alloy's PUSH url and already ends in /loki/api/v1
  # (see LokiClient.queryBase() in software-factory for the same trap).
  local base="${LOKI_ENDPOINT%/push}"
  local query="sum(count_over_time(${LOKI_SELECTOR}[${LOG_SILENCE_MINUTES}m]))"
  local code
  code=$(curl -s -o "$TMP/loki.json" -w '%{http_code}' -m 30 \
    -u "$LOKI_USER:$LOKI_TOKEN" -G "$base/query" \
    --data-urlencode "query=$query") || true

  if [[ "$code" != "200" ]]; then
    problems+=("logs: cannot check - Loki answered HTTP ${code:-000}")
    report "FAIL  logs: Loki query failed (HTTP ${code:-000})"
    return
  fi

  local count
  count=$(python3 - "$TMP/loki.json" <<'PY'
import json, sys
try:
    with open(sys.argv[1]) as fh:
        doc = json.load(fh)
except Exception:
    print("error")
    sys.exit(0)
if doc.get("status") != "success":
    print("error")
    sys.exit(0)
result = doc.get("data", {}).get("result", [])
# An empty vector is what Loki returns when nothing matched at all - the
# silent case, not an error.
if not result:
    print(0)
    sys.exit(0)
try:
    print(int(float(result[0]["value"][1])))
except Exception:
    print("error")
PY
)
  # Anything that is not a plain number is an unreadable answer. Comparing it
  # with -gt would fall through to "nothing received", reporting a parsing
  # problem as a silent stack.
  if ! [[ "$count" =~ ^[0-9]+$ ]]; then
    problems+=("logs: cannot check - Loki returned an unreadable or failed response")
    report "FAIL  logs: unreadable Loki response"
  elif [[ "$count" -gt 0 ]]; then
    report "ok    logs: $count lines in the last ${LOG_SILENCE_MINUTES}m"
  else
    problems+=("logs: NOTHING received from the stack in ${LOG_SILENCE_MINUTES}m - shipping has stopped or the Pi is offline")
    report "FAIL  logs: no lines from the stack in the last ${LOG_SILENCE_MINUTES}m"
  fi
}

# ---------------------------------------------------------------------------
# 2 + 3. Public hostnames
# ---------------------------------------------------------------------------

# Prints "ok" or a short description of what was wrong.
probe() {
  local url="$1" expect="$2" code body
  # Cleared first: curl writes nothing on a timeout, and a stale body left by the
  # previous probe would be reported as this URL's answer.
  : > "$TMP/body"
  code=$(curl -s -o "$TMP/body" -w '%{http_code}' -m 20 "$url") || true
  [[ -z "$code" ]] && code="000"
  if [[ "$expect" == body=* ]]; then
    body="$(tr -d '[:space:]' < "$TMP/body" 2>/dev/null)"
    if [[ "$code" == "200" && "$body" == "${expect#body=}" ]]; then
      echo "ok"
    else
      echo "HTTP $code, body '${body:0:40}' (want '${expect#body=}')"
    fi
  elif [[ "$code" == "$expect" ]]; then
    echo "ok"
  else
    echo "HTTP $code (want $expect)"
  fi
}

# No associative arrays: macOS still ships bash 3.2, and this should run from a
# laptop as readily as from the workflow. `failed` holds "<entry>|<result>" for
# the attempt just made, so its results always line up with what is pending.
check_endpoints() {
  local pending=("${ENDPOINTS[@]}") failed=() attempt entry name url expect result
  for (( attempt = 1; attempt <= ATTEMPTS; attempt++ )); do
    failed=()
    for entry in "${pending[@]}"; do
      IFS='|' read -r name url expect <<< "$entry"
      result="$(probe "$url" "$expect")"
      if [[ "$result" == "ok" ]]; then
        report "ok    $name"
      else
        failed+=("$entry|$result")
      fi
    done
    [[ "${#failed[@]}" -eq 0 ]] && return
    pending=()
    for entry in "${failed[@]}"; do
      pending+=("${entry%|*}")
    done
    if (( attempt < ATTEMPTS )); then
      echo "      ${#pending[@]} not serving yet; retrying in ${RETRY_DELAY}s (attempt $attempt/$ATTEMPTS)"
      sleep "$RETRY_DELAY"
    fi
  done
  for entry in "${failed[@]}"; do
    result="${entry##*|}"
    IFS='|' read -r name url expect <<< "${entry%|*}"
    problems+=("$name: $result - $url")
    report "FAIL  $name: $result"
  done
}

check_logs
check_endpoints

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo "## Production heartbeat"
    echo
    printf -- '- %s\n' "${summary[@]}"
    if [[ "${#problems[@]}" -gt 0 ]]; then
      echo
      echo "See docs/runbooks/prod-heartbeat.md for what each failure usually means."
    fi
  } >> "$GITHUB_STEP_SUMMARY"
fi

if [[ "${#problems[@]}" -gt 0 ]]; then
  echo
  echo "PRODUCTION HEARTBEAT FAILED:"
  printf '  - %s\n' "${problems[@]}"
  exit 1
fi
echo
echo "Production heartbeat OK."
