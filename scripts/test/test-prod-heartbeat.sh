#!/usr/bin/env bash
#
# Coverage for scripts/prod-heartbeat.sh, the off-Pi alarm. It is the one check
# that only ever runs on a schedule, so a bug in it shows up as silence - the
# failure mode it exists to end. The properties worth pinning are therefore the
# ones that stop it passing when it should not: an empty Loki result, a Loki
# error, missing credentials and Dependency-Track's 200-with-`false` must all
# FAIL, and a hostname that recovers within the retry window must not.
#
# `curl` is stubbed on PATH; nothing touches the network.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCRIPT="$PROJECT_DIR/scripts/prod-heartbeat.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
STUBS="$TMP/stubs"
mkdir -p "$STUBS"

# curl stub. Writes a body to the -o file and prints a status for -w.
#   FAKE_LOKI    = count (default 42) | empty | 401 | failed
#   FAKE_OIDC    = true (default) | false
#   FAKE_DOWN    = substring of a URL that returns 503
#   FAKE_RECOVER = after this many calls to a FAKE_DOWN url, it serves again
# Every URL requested is appended to $CALLS.
cat >"$STUBS/curl" <<'EOF'
#!/usr/bin/env bash
out=/dev/null url=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) out="$2"; shift ;;
    http*) url="$1" ;;
  esac
  shift
done
echo "$url" >> "$CALLS"
if [[ "$url" == */loki/api/v1/query ]]; then
  case "${FAKE_LOKI:-42}" in
    empty)  echo '{"status":"success","data":{"resultType":"vector","result":[]}}' >"$out"; printf 200 ;;
    401)    echo 'invalid scope' >"$out"; printf 401 ;;
    failed) echo '{"status":"error","error":"parse error"}' >"$out"; printf 200 ;;
    *)      printf '{"status":"success","data":{"result":[{"metric":{},"value":[1,"%s"]}]}}' "${FAKE_LOKI:-42}" >"$out"; printf 200 ;;
  esac
  exit 0
fi
if [[ -n "${FAKE_DOWN:-}" && "$url" == *"$FAKE_DOWN"* ]]; then
  n=$(( $(cat "$COUNTER" 2>/dev/null || echo 0) + 1 ))
  echo "$n" >"$COUNTER"
  if [[ -z "${FAKE_RECOVER:-}" || "$n" -le "$FAKE_RECOVER" ]]; then
    echo 'maintenance' >"$out"; printf 503; exit 0
  fi
fi
if [[ "$url" == */oidc/available ]]; then
  printf '%s' "${FAKE_OIDC:-true}" >"$out"; printf 200; exit 0
fi
echo '<html>' >"$out"; printf 200
EOF
chmod +x "$STUBS/curl"

failures=0
checks=0

check() {
  local description="$1" condition="$2"
  checks=$((checks + 1))
  if eval "$condition"; then
    echo "    ok: $description"
  else
    failures=$((failures + 1))
    echo "    FAIL: $description"
  fi
}

# run [VAR=value ...] - sets $out and $rc
run() {
  : >"$TMP/calls"
  rm -f "$TMP/counter"
  out="$(env -u GITHUB_STEP_SUMMARY PATH="$STUBS:$PATH" CALLS="$TMP/calls" COUNTER="$TMP/counter" \
    GRAFANA_CLOUD_LOKI_ENDPOINT="https://logs.example.test/loki/api/v1/push" \
    GRAFANA_CLOUD_LOKI_USER="1" GRAFANA_CLOUD_API_KEY="token" \
    ATTEMPTS=3 RETRY_DELAY=0 "$@" bash "$SCRIPT" 2>&1)"
  rc=$?
}

# ---------------------------------------------------------------------------
echo "  everything healthy"
# ---------------------------------------------------------------------------
run
check "exits 0" "[[ $rc -eq 0 ]]"
check "reports the log count" "grep -q 'ok    logs: 42 lines in the last 30m' <<<\"\$out\""
check "queries Loki's query API, not the push URL" \
  "grep -qx 'https://logs.example.test/loki/api/v1/query' '$TMP/calls'"
check "probes every hostname once" "[[ \$(grep -vc loki '$TMP/calls') -eq 8 ]]"

# ---------------------------------------------------------------------------
echo "  log silence and Loki failures all fail"
# ---------------------------------------------------------------------------
run FAKE_LOKI=empty
check "an empty result (nothing shipped) fails" \
  "[[ $rc -ne 0 ]] && grep -q 'NOTHING received from the stack' <<<\"\$out\""
run FAKE_LOKI=0
check "a zero count fails" "[[ $rc -ne 0 ]] && grep -q 'NOTHING received' <<<\"\$out\""
run FAKE_LOKI=401
check "a Loki auth error fails as 'cannot check', not as silence" \
  "[[ $rc -ne 0 ]] && grep -q 'cannot check - Loki answered HTTP 401' <<<\"\$out\""
run FAKE_LOKI=failed
check "a 200 with status=error fails" \
  "[[ $rc -ne 0 ]] && grep -q 'unreadable or failed response' <<<\"\$out\""
run FAKE_LOKI=NaN-ish
check "a value that is not a count fails as unreadable, never as silence" \
  "[[ $rc -ne 0 ]] && grep -q 'unreadable or failed response' <<<\"\$out\" && ! grep -q 'NOTHING received' <<<\"\$out\""
run GRAFANA_CLOUD_API_KEY=
check "missing credentials fail rather than skip" \
  "[[ $rc -ne 0 ]] && grep -q 'credentials are not configured' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  Dependency-Track's sign-in check reads the body, not the status"
# ---------------------------------------------------------------------------
run FAKE_OIDC=false
check "200 with 'false' fails - the 2026-09 DNS outage's exact symptom" \
  "[[ $rc -ne 0 ]] && grep -q 'dependency-track sign-in: HTTP 200, body .false.' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  retries: a deploy's maintenance page is not an alarm, a stuck one is"
# ---------------------------------------------------------------------------
run FAKE_DOWN=www.simonrowe.dev FAKE_RECOVER=2
check "a hostname that recovers on the last attempt passes" "[[ $rc -eq 0 ]]"
check "only the failing hostname is retried" \
  "[[ \$(grep -c 'api.simonrowe.dev' '$TMP/calls') -eq 1 && \$(grep -c 'www.simonrowe.dev' '$TMP/calls') -eq 3 ]]"
run FAKE_DOWN=www.simonrowe.dev
check "a hostname still 503 after every attempt fails, naming it" \
  "[[ $rc -ne 0 ]] && grep -q 'www: HTTP 503 (want 200) - https://www.simonrowe.dev/' <<<\"\$out\""
check "and only that one" "[[ \$(grep -c '^FAIL' <<<\"\$out\") -eq 1 ]]"

echo
printf '  %d checks, %d failures\n' "$checks" "$failures"
[[ "$failures" -eq 0 ]]
