#!/usr/bin/env bash
#
# Coverage for scripts/prod-heartbeat-alert.sh, which turns a failed heartbeat
# into a GitHub issue somebody is notified of. Like the heartbeat, it only ever
# runs on a schedule, so its faults show up as silence. The properties pinned:
# a failure files exactly one assigned issue with the heartbeat's problems in it,
# a continuing outage files nothing more, a refused assignee still files, and a
# pass closes whatever is open.
#
# `gh` is stubbed on PATH; nothing reaches GitHub.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCRIPT="$PROJECT_DIR/scripts/prod-heartbeat-alert.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
STUBS="$TMP/stubs"
mkdir -p "$STUBS"
CALLS="$TMP/calls"

# gh stub. Records each call's arguments, one per line, between "--- <subcommand>"
# markers, so a multi-line --body is still searchable.
#   FAKE_OPEN          = issue numbers `issue list` prints, space separated
#   FAKE_LIST_FAIL     = 1: `issue list` fails
#   FAKE_ASSIGNEE_FAIL = 1: `issue create --assignee` fails
cat >"$STUBS/gh" <<'EOF'
#!/usr/bin/env bash
echo "--- $1 $2" >> "$CALLS"
printf '%s\n' "$@" >> "$CALLS"
case "$1 $2" in
  "issue list")
    [[ "${FAKE_LIST_FAIL:-}" == 1 ]] && exit 1
    for n in ${FAKE_OPEN:-}; do echo "$n"; done ;;
  "issue create")
    if [[ "${FAKE_ASSIGNEE_FAIL:-}" == 1 ]] && printf '%s\n' "$@" | grep -qx -- '--assignee'; then
      echo "could not assign" >&2; exit 1
    fi
    echo "https://github.com/o/r/issues/7" ;;
esac
exit 0
EOF
chmod +x "$STUBS/gh"

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

# run <args...> [VAR=value ...] via env - sets $out and $rc
run() {
  : >"$CALLS"
  out="$(env PATH="$STUBS:$PATH" CALLS="$CALLS" GITHUB_REPOSITORY=o/r \
    GITHUB_SERVER_URL=https://github.com GITHUB_RUN_ID=99 ALERT_ASSIGNEE=simonrowe \
    ${ENV[@]+"${ENV[@]}"} bash "$SCRIPT" "$@" 2>&1)"
  rc=$?
}

creates() { grep -c '^--- issue create' "$CALLS"; }

REPORT="$TMP/report.txt"
cat >"$REPORT" <<'EOF'
ok    logs: 1199 lines in the last 30m
FAIL  www: HTTP 000 (want 200)
FAIL  pinggy: token held by another session (80 refusals in 15m)

PRODUCTION HEARTBEAT FAILED:
  - www: HTTP 000 (want 200) - https://www.simonrowe.dev/
  - pinggy: the tunnel token is held by another session (80 refusals in 15m)
EOF

# ---------------------------------------------------------------------------
echo "  a failure with nothing open files one assigned issue"
# ---------------------------------------------------------------------------
ENV=()
run open "$REPORT"
check "exits 0" "[[ $rc -eq 0 ]]"
check "creates exactly one issue" "[[ \$(creates) -eq 1 ]]"
check "assigned, labelled prod-down" \
  "grep -A1 -x -- '--assignee' '$CALLS' | grep -qx simonrowe && grep -A1 -x -- '--label' '$CALLS' | grep -qx prod-down"
check "the title leads with the first problem" \
  "grep -qx 'Production heartbeat failing: www: HTTP 000 (want 200) - https://www.simonrowe.dev/' '$CALLS'"
check "the body lists every problem and links the run" \
  "grep -qx -- '- pinggy: the tunnel token is held by another session (80 refusals in 15m)' '$CALLS' && grep -q 'Run: https://github.com/o/r/actions/runs/99' '$CALLS'"
check "the body mentions the assignee, so the mention notifies too" "grep -q '^@simonrowe the production heartbeat failed' '$CALLS'"
check "the label is ensured first" "grep -q '^--- label create' '$CALLS'"

# ---------------------------------------------------------------------------
echo "  a continuing outage files nothing more"
# ---------------------------------------------------------------------------
ENV=(FAKE_OPEN=41)
run open "$REPORT"
check "no issue is created while #41 is open" "[[ $rc -eq 0 && \$(creates) -eq 0 ]]"
check "it says so" "grep -q 'already open: #41' <<<\"\$out\""

ENV=(FAKE_LIST_FAIL=1)
run open "$REPORT"
check "if open issues cannot be listed, it fails rather than guessing" "[[ $rc -ne 0 && \$(creates) -eq 0 ]]"

# ---------------------------------------------------------------------------
echo "  a refused assignee does not cost the alert"
# ---------------------------------------------------------------------------
ENV=(FAKE_ASSIGNEE_FAIL=1)
run open "$REPORT"
check "falls back to an unassigned issue" "[[ $rc -eq 0 && \$(creates) -eq 2 ]]"

ENV=()
: >"$TMP/empty.txt"
run open "$TMP/empty.txt"
check "a report with no problem lines still files, pointing at the run" \
  "[[ $rc -eq 0 ]] && grep -q 'failed without listing a problem' '$CALLS'"

# ---------------------------------------------------------------------------
echo "  a pass closes whatever is open"
# ---------------------------------------------------------------------------
ENV=(FAKE_OPEN="41 42")
run close
check "closes both, with a recovery note" \
  "[[ $rc -eq 0 && \$(grep -c '^--- issue close' '$CALLS') -eq 2 ]] && grep -q '^Recovered: the production heartbeat passed' '$CALLS'"
ENV=()
run close
check "with nothing open, closes nothing" "[[ $rc -eq 0 && \$(grep -c '^--- issue close' '$CALLS') -eq 0 ]]"

ENV=()
run bogus
check "an unknown command is a usage error" "[[ $rc -eq 2 ]]"

echo
printf '  %d checks, %d failures\n' "$checks" "$failures"
[[ "$failures" -eq 0 ]]
