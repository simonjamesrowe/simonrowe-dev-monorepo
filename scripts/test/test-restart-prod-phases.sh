#!/usr/bin/env bash
#
# Phase-level coverage of scripts/restart-prod.sh under DRY_RUN=1 with a throwaway
# STATE_DIR.
#
# This is not optional coverage. Every remediation path in the script shells out
# to `docker compose`, so a test that ran it for real would perform restarts and
# could recreate containers if the compose file had been edited since the last
# deploy. DRY_RUN is exported by scripts/test/run-tests.sh; it is re-asserted here
# so running this file directly is safe too.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCRIPT="$PROJECT_DIR/scripts/restart-prod.sh"

export DRY_RUN=1

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

failures=0
checks=0

fail() {
  failures=$((failures + 1))
  echo "    FAIL: $1"
}

ok() {
  echo "    ok: $1"
}

check() {
  local description="$1" condition="$2"
  checks=$((checks + 1))
  if eval "$condition"; then
    ok "$description"
  else
    fail "$description"
  fi
}

# Runs a phase in an isolated STATE_DIR, capturing combined output.
run_phase() {
  local state_dir="$1"
  shift
  STATE_DIR="$state_dir" bash "$SCRIPT" "$@" 2>&1
}

# ---------------------------------------------------------------------------
echo "  maintenance-on / maintenance-off"
# ---------------------------------------------------------------------------
state="$TMP/state-maintenance"
run_phase "$state" maintenance-on >/dev/null
check "maintenance-on creates the flag" "[[ -f '$state/maintenance.on' ]]"

run_phase "$state" maintenance-on >/dev/null
check "maintenance-on is idempotent" "[[ -f '$state/maintenance.on' ]]"

run_phase "$state" maintenance-off >/dev/null
check "maintenance-off removes the flag" "[[ ! -f '$state/maintenance.on' ]]"

run_phase "$state" maintenance-off >/dev/null
rc=$?
check "maintenance-off is idempotent when already absent" "[[ $rc -eq 0 ]]"

# ---------------------------------------------------------------------------
echo "  pull"
# ---------------------------------------------------------------------------
state="$TMP/state-pull"
out="$(run_phase "$state" pull)"
check "pull records one rollback line per service" \
  "[[ \$(grep -c . '$state/rollback-images') -eq 3 ]]"
check "pull records backend" "grep -q '^backend' '$state/rollback-images'"
check "pull never records deployer" "! grep -q 'deployer' '$state/rollback-images'"
check "pull with the default tag emits no docker tag" \
  "! grep -q 'DRY-RUN: docker tag' <<<\"\$out\""
check "pull emits a docker pull per service" \
  "[[ \$(grep -c 'DRY-RUN: docker pull' <<<\"\$out\") -eq 3 ]]"

# The regression this guards: an append would make a retried pull record the
# freshly-pulled image as the rollback target, so rollback would restore the
# broken version.
before="$(cat "$state/rollback-images")"
run_phase "$state" pull >/dev/null
after="$(cat "$state/rollback-images")"
check "pull truncates rather than appends across retries" "[[ '$before' == '$after' ]]"

state="$TMP/state-pull-sha"
out="$(IMAGE_TAG=abc123 run_phase "$state" pull)"
check "pull with a sha tag pulls that sha" \
  "grep -q 'DRY-RUN: docker pull ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend:abc123' <<<\"\$out\""
check "pull with a sha tag re-tags it to latest" \
  "grep -q 'DRY-RUN: docker tag ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend:abc123 ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend:latest' <<<\"\$out\""

state="$TMP/state-pull-one"
out="$(SERVICES=backend run_phase "$state" pull)"
check "pull honours a narrowed SERVICES" \
  "[[ \$(grep -c 'DRY-RUN: docker pull' <<<\"\$out\") -eq 1 ]]"

# ---------------------------------------------------------------------------
echo "  recreate"
# ---------------------------------------------------------------------------
state="$TMP/state-recreate"
out="$(run_phase "$state" recreate)"
check "recreate uses --no-deps --pull never per service" \
  "[[ \$(grep -c 'up -d --no-deps --pull never' <<<\"\$out\") -eq 3 ]]"
check "recreate never touches deployer" "! grep -q 'never deployer' <<<\"\$out\""
check "recreate restarts nginx" "grep -q 'restart nginx' <<<\"\$out\""
# The reconcile must name its services rather than running a bare `up -d`. A bare
# one recreates the deployer whenever the shared software-factory:latest tag has
# just been repointed by the pull phase - which SIGTERMs the container running
# this very script. It happened twice in production before this was fixed.
check "recreate reconciles the rest of the stack afterwards" \
  "grep -qE 'DRY-RUN: docker compose -f [^ ]+ up -d [a-z]' <<<\"\$out\""
check "recreate's reconcile EXCLUDES the deployer" \
  "! grep -E 'DRY-RUN: docker compose -f [^ ]+ up -d .*(^| )deployer( |\$)' <<<\"\$out\""
check "recreate's reconcile still covers the other services" \
  "grep -E 'DRY-RUN: docker compose -f [^ ]+ up -d ' <<<\"\$out\" | grep -q backend"

# ---------------------------------------------------------------------------
echo "  rollback"
# ---------------------------------------------------------------------------
state="$TMP/state-rollback"
mkdir -p "$state"
printf 'backend\tsha256:aaa\nfrontend\tsha256:bbb\n' >"$state/rollback-images"
out="$(run_phase "$state" rollback)"
check "rollback re-tags from the recorded image ids" \
  "grep -q 'DRY-RUN: docker tag sha256:aaa ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend:latest' <<<\"\$out\""
check "rollback recreates each recorded service" \
  "[[ \$(grep -c 'up -d --no-deps --pull never' <<<\"\$out\") -eq 2 ]]"

state="$TMP/state-rollback-empty"
mkdir -p "$state"
run_phase "$state" rollback >/dev/null 2>&1
rc=$?
check "rollback with nothing recorded fails rather than silently passing" "[[ $rc -eq 1 ]]"

# ---------------------------------------------------------------------------
echo "  verify / verify-public host split"
# ---------------------------------------------------------------------------
state="$TMP/state-verify"
out="$(run_phase "$state" verify)"
check "verify checks console" "grep -q 'console.simonrowe.dev' <<<\"\$out\""
check "verify checks temporal" "grep -q 'temporal.simonrowe.dev' <<<\"\$out\""
# The reason this split exists: the maintenance page returns 503 by design and the
# hostname check treats 503 as a failure, so checking www/api with the flag still
# set would fail every single deploy.
check "verify does NOT check www" "! grep -q 'www.simonrowe.dev' <<<\"\$out\""
check "verify does NOT check api" "! grep -q 'api.simonrowe.dev' <<<\"\$out\""

out="$(run_phase "$state" verify-public)"
check "verify-public checks www" "grep -q 'www.simonrowe.dev' <<<\"\$out\""
check "verify-public checks api" "grep -q 'api.simonrowe.dev' <<<\"\$out\""
check "verify-public does not check console" "! grep -q 'console.simonrowe.dev' <<<\"\$out\""

out="$(DRY_RUN_HTTP_CODE=503 run_phase "$state" verify-public)"
rc=$?
check "verify-public treats 503 as a failure" "[[ $rc -eq 1 ]]"

out="$(DRY_RUN_HTTP_CODE=502 run_phase "$state" verify)"
rc=$?
check "verify treats 502 on an ops hostname as a failure" "[[ $rc -eq 1 ]]"

# ---------------------------------------------------------------------------
echo "  all (the human path) still checks every hostname"
# ---------------------------------------------------------------------------
state="$TMP/state-all"
out="$(run_phase "$state" all)"
check "all pulls the whole compose file, not per-service" \
  "grep -qE 'DRY-RUN: docker compose -f [^ ]+ pull\$' <<<\"\$out\""
check "all reconciles the rest of the stack" \
  "grep -qE 'DRY-RUN: docker compose -f [^ ]+ up -d [a-z]' <<<\"\$out\""
check "all's reconcile EXCLUDES the deployer" \
  "! grep -E 'DRY-RUN: docker compose -f [^ ]+ up -d .*(^| )deployer( |\$)' <<<\"\$out\""
check "all uses no --no-deps form" "! grep -q -- '--no-deps' <<<\"\$out\""
check "all checks www" "grep -q 'www.simonrowe.dev' <<<\"\$out\""
check "all checks console" "grep -q 'console.simonrowe.dev' <<<\"\$out\""
check "all creates no maintenance flag" "[[ ! -f '$state/maintenance.on' ]]"
check "all reports the historical success message" \
  "grep -q 'Production services refreshed and verified.' <<<\"\$out\""

out="$(DRY_RUN_HTTP_CODE=502 run_phase "$state" all)"
rc=$?
check "all reports INCOMPLETE and exits 1 on a failed hostname" \
  "[[ $rc -eq 1 ]] && grep -q 'Production refresh INCOMPLETE' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  usage"
# ---------------------------------------------------------------------------
run_phase "$TMP/state-usage" not-a-phase >/dev/null 2>&1
rc=$?
check "an unknown phase exits 64" "[[ $rc -eq 64 ]]"

# ---------------------------------------------------------------------------
echo "  jq settle parser"
# ---------------------------------------------------------------------------
# A fixture rather than a live Docker, so the four classification cases are
# exercised deterministically.
fixture="$TMP/ps.jsonl"
cat >"$fixture" <<'EOF'
{"Name":"healthy-one","State":"running","Health":"healthy","ExitCode":0}
{"Name":"unhealthy-one","State":"running","Health":"unhealthy","ExitCode":0}
{"Name":"created-one","State":"created","Health":"","ExitCode":0}
{"Name":"clean-oneshot","State":"exited","Health":"","ExitCode":0}
{"Name":"failed-oneshot","State":"exited","Health":"","ExitCode":137}
{"Name":"no-healthcheck-running","State":"running","Health":"","ExitCode":0}
this line is not json
EOF

# Driven through the `verify` phase with VERIFY_TIMEOUT=0: the settle loop reports
# its pending list once and gives up, which is the cheapest way to see the
# parser's classification without a live stack.
out="$(PS_FIXTURE="$fixture" VERIFY_TIMEOUT=0 run_phase "$TMP/state-parse" verify)"
check "parser flags an unhealthy container" "grep -q 'unhealthy-one' <<<\"\$out\""
check "parser flags a created container" "grep -q 'created-one' <<<\"\$out\""
check "parser flags a failed one-shot" "grep -q 'failed-oneshot' <<<\"\$out\""
# Anchored: "healthy-one" is a substring of "unhealthy-one", so an unanchored
# grep here passes for the wrong reason.
check "parser ignores a healthy container" "! grep -qE '^ +healthy-one ' <<<\"\$out\""
check "parser ignores a clean one-shot" "! grep -q 'clean-oneshot' <<<\"\$out\""
check "parser ignores a running container with no healthcheck" \
  "! grep -q 'no-healthcheck-running' <<<\"\$out\""
check "parser survives a malformed line" "grep -q 'not settled yet\|did not reach' <<<\"\$out\""
check "parser explains the created state" \
  "grep -q \"built for this deploy but never started\" <<<\"\$out\""

# The array shape newer compose versions emit must classify identically - the old
# python parser only handled JSON Lines, so a compose upgrade would have made
# every container look settled.
fixture_array="$TMP/ps.json"
cat >"$fixture_array" <<'EOF'
[
  {"Name":"healthy-one","State":"running","Health":"healthy","ExitCode":0},
  {"Name":"created-one","State":"created","Health":"","ExitCode":0}
]
EOF
out="$(PS_FIXTURE="$fixture_array" VERIFY_TIMEOUT=0 run_phase "$TMP/state-parse2" verify)"
check "parser handles the JSON array shape too" "grep -q 'created-one' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo
printf '  %d checks, %d failures\n' "$checks" "$failures"
[[ "$failures" -eq 0 ]]
