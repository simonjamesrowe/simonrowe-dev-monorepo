#!/usr/bin/env bash
set -uo pipefail

# Turns the heartbeat's result into a GitHub issue that a person is notified of.
# Run by .github/workflows/prod-heartbeat.yml after scripts/prod-heartbeat.sh:
#
#   prod-heartbeat-alert.sh open <report-file>   heartbeat failed
#   prod-heartbeat-alert.sh close                heartbeat passed
#
# WHY AN ISSUE
# ------------
# The heartbeat used to rely on GitHub emailing whoever last edited the workflow's
# cron line about a failed scheduled run. On 2026-10-22 it failed every run for
# 24 hours and no email arrived: the only mail from GitHub in those days was sudo
# codes. Assigning an issue always notifies the assignee (web, mobile and email,
# per their own settings), and it needs nothing beyond the workflow's GITHUB_TOKEN.
#
# ONE ISSUE PER OUTAGE
# --------------------
# The heartbeat runs every 15 minutes, so `open` does nothing while an issue with
# the label is already open. Otherwise a day-long outage would file 96 issues.
# `close` closes every open one with a note, so the next failure opens a new
# issue and notifies again.
#
# Needs: gh on PATH, GH_TOKEN, GITHUB_REPOSITORY. Optional: ALERT_ASSIGNEE,
# ALERT_LABEL, and GITHUB_SERVER_URL + GITHUB_RUN_ID for the run link.

ALERT_LABEL=${ALERT_LABEL:-prod-down}
ALERT_ASSIGNEE=${ALERT_ASSIGNEE:-}
REPO=${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must be set}
RUN_URL=""
if [[ -n "${GITHUB_SERVER_URL:-}" && -n "${GITHUB_RUN_ID:-}" ]]; then
  RUN_URL="$GITHUB_SERVER_URL/$REPO/actions/runs/$GITHUB_RUN_ID"
fi

open_issues() {
  gh issue list --repo "$REPO" --label "$ALERT_LABEL" --state open \
    --json number --jq '.[].number'
}

cmd_open() {
  local report_file="$1" existing body title problems
  if ! existing="$(open_issues)"; then
    echo "Could not list issues labelled $ALERT_LABEL" >&2
    return 1
  fi
  if [[ -n "$existing" ]]; then
    echo "Outage issue already open: #${existing%%$'\n'*}. Not filing another."
    return 0
  fi

  # The heartbeat ends with "PRODUCTION HEARTBEAT FAILED:" and one "  - " line per
  # problem. Those lines are the useful part, so they lead the issue.
  problems="$(sed -n '/^PRODUCTION HEARTBEAT FAILED:/,$p' "$report_file" 2>/dev/null | sed -n 's/^  - /- /p')"
  [[ -z "$problems" ]] && problems="- the heartbeat failed without listing a problem; see the run"

  title="Production heartbeat failing: $(head -1 <<<"$problems" | sed 's/^- //' | cut -c1-90)"
  body="$(cat <<EOF
${ALERT_ASSIGNEE:+@$ALERT_ASSIGNEE }the production heartbeat failed.

$problems

${RUN_URL:+Run: $RUN_URL}

What each failure usually means: \`docs/runbooks/prod-heartbeat.md\`. Triage: the \`prod-triage\` skill.
This issue closes itself when the heartbeat passes again.
EOF
)"

  # --force creates the label, or updates it if it already exists.
  gh label create "$ALERT_LABEL" --repo "$REPO" --color B60205 \
    --description "Opened by the production heartbeat; closes on recovery" --force >/dev/null || true

  if [[ -n "$ALERT_ASSIGNEE" ]] && gh issue create --repo "$REPO" --title "$title" \
      --body "$body" --label "$ALERT_LABEL" --assignee "$ALERT_ASSIGNEE"; then
    return 0
  fi
  # An assignee GitHub refuses (renamed, lost access) must not cost the alert:
  # file it unassigned. The @mention in the body still notifies.
  echo "Filing without an assignee" >&2
  gh issue create --repo "$REPO" --title "$title" --body "$body" --label "$ALERT_LABEL"
}

cmd_close() {
  local existing n rc=0
  if ! existing="$(open_issues)"; then
    echo "Could not list issues labelled $ALERT_LABEL" >&2
    return 1
  fi
  for n in $existing; do
    gh issue close "$n" --repo "$REPO" \
      --comment "Recovered: the production heartbeat passed at $(date -u +%Y-%m-%dT%H:%MZ).${RUN_URL:+ $RUN_URL}" || rc=1
  done
  return "$rc"
}

case "${1:-}" in
  open)
    [[ -n "${2:-}" ]] || { echo "usage: $0 open <report-file>" >&2; exit 2; }
    cmd_open "$2"
    ;;
  close) cmd_close ;;
  *) echo "usage: $0 open <report-file> | close" >&2; exit 2 ;;
esac
