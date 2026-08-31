#!/usr/bin/env bash
#
# Guards the two things that keep production logs reaching Grafana Cloud.
#
# In August 2026 Loki held NOTHING for three weeks while `alloy` reported
# `Up (healthy)` with RestartCount 0. Nothing was broken on the Pi: Alloy was
# tailing containers correctly and building batches, and Grafana Cloud was
# rejecting every one of them with
#
#     status=429 "ingestion rate limit exceeded for user 1539009
#                 (limit: 0 bytes/sec)"
#
# because the calendar-month free-tier logs allowance (50 GB) had been spent -
# 55 GB used against a workload measured at 0.58 GB/month. Nothing surfaced it:
# the container healthcheck is `alloy --version`, which passes while every batch
# is dropped, and the read credential kept working the whole time, so a query
# returned `{"status":"success"}` with an empty body rather than an error.
#
# The amplifier was structural, and both halves of it are asserted here.
#
# A shell test rather than a Java one because the subjects are a compose file
# and a host script owned by neither Gradle module - the same reasoning as
# test-frontend-nginx-shipping.sh and test-image-sbom-os-coverage.sh, which this
# follows closely.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

COMPOSE_FILE="$PROJECT_DIR/docker-compose.prod.yml"
ROTATION_SCRIPT="$PROJECT_DIR/scripts/enable-docker-log-rotation.sh"

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

# Comment lines are dropped for the reason test-image-sbom-os-coverage.sh gives:
# the comments here name what must NOT come back, so a scan over raw lines would
# fail on its own rationale and teach the next person to delete the explanation.
#
# Captured ONCE into a variable, and every check below greps a here-string rather
# than a pipeline. `uncommented file | grep -q pattern` looks equivalent and is
# not: `grep -q` exits the moment it matches, which can close the pipe before the
# upstream grep has finished writing. The upstream then dies of EPIPE, and with
# `pipefail` set (line 25) the pipeline reports FAILURE even though the pattern
# matched. It is a race, so it passes locally and fails on a loaded CI runner -
# which is exactly what it did on the first run of this file.
uncommented() {
  grep -vE '^[[:space:]]*#' "$1"
}

# ---------------------------------------------------------------------------
echo "  the subjects exist"
# ---------------------------------------------------------------------------
# Without these, every assertion below passes by reading nothing.
check "docker-compose.prod.yml exists" "[[ -f '$COMPOSE_FILE' ]]"
check "enable-docker-log-rotation.sh exists" "[[ -f '$ROTATION_SCRIPT' ]]"
check "enable-docker-log-rotation.sh is executable" "[[ -x '$ROTATION_SCRIPT' ]]"

if [[ ! -f "$COMPOSE_FILE" || ! -f "$ROTATION_SCRIPT" ]]; then
  echo "  subjects missing - aborting"
  exit 1
fi

COMPOSE_BODY="$(uncommented "$COMPOSE_FILE")"

# ---------------------------------------------------------------------------
echo "  Alloy's read cursors survive a recreate"
# ---------------------------------------------------------------------------
# loki.source.docker keeps one cursor per container in --storage.path. With that
# path unmounted it resolves to the container's writable layer, and `alloy` is in
# FACTORY_DEPLOY_RECREATABLE, so EVERY deploy destroyed the cursors and re-tailed
# every container from the start - re-shipping the whole history of the stack,
# each time, with no error logged anywhere. The cost landed only as ingested
# bytes, which is why it ran for a month unnoticed.
check "alloy declares --storage.path" \
  "grep -q -- '--storage.path=/var/lib/alloy/data' <<<\"\$COMPOSE_BODY\""
check "a named volume backs that path" \
  "grep -q 'alloy-data:/var/lib/alloy/data' <<<\"\$COMPOSE_BODY\""
check "the alloy-data volume is declared" \
  "grep -qE '^  alloy-data:' <<<\"\$COMPOSE_BODY\""

# The volume only reaches production if a deploy is allowed to recreate alloy.
# FACTORY_DEPLOY_RECREATABLE is an allowlist; a compose change to a service
# outside it makes sync-config decline as `held-back`, which freezes the deploy
# directory and is self-perpetuating - the wedge that stranded #130 through #136.
check "alloy is in the FACTORY_DEPLOY_RECREATABLE default" \
  "grep 'FACTORY_DEPLOY_RECREATABLE' <<<\"\$COMPOSE_BODY\" | grep -q 'alloy'"

# ---------------------------------------------------------------------------
echo "  rotation is NOT configured in the compose file"
# ---------------------------------------------------------------------------
# This is the inverse assertion, and it is the point of the separate script.
# Rotation has to cover all 22 containers, but adding `logging:` to a service
# changes its `docker compose config --hash`, and only nine services are in the
# recreate allowlist - so doing it here would decline the very deploy meant to
# apply it, then keep declining every deploy after. It belongs in daemon.json,
# which is host config and changes no service hash.
check "no logging: block in the compose file" \
  "! grep -qE '^[[:space:]]+logging:' <<<\"\$COMPOSE_BODY\""

# ---------------------------------------------------------------------------
echo "  the rotation script writes a valid daemon.json"
# ---------------------------------------------------------------------------
TMPDIR_TEST="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_TEST"' EXIT
DAEMON="$TMPDIR_TEST/daemon.json"

# SUDO= writes directly; the script defaults it to `sudo` for the real host.
run_rotation() {
  DAEMON_JSON="$DAEMON" SUDO= bash "$ROTATION_SCRIPT" "$@" 2>&1
}

run_rotation --apply >/dev/null
check "--apply creates the file when absent" "[[ -f '$DAEMON' ]]"
check "--apply writes valid JSON" \
  "python3 -c 'import json,sys; json.load(open(\"$DAEMON\"))'"
check "--apply sets max-size" \
  "python3 -c 'import json; assert json.load(open(\"$DAEMON\"))[\"log-opts\"][\"max-size\"]'"
# max-file must be a STRING. Docker rejects a JSON number here with
# "json: cannot unmarshal number into Go struct field", and the daemon then
# refuses to start - taking all 22 containers with it on the next restart.
check "--apply writes max-file as a string, not a number" \
  "python3 -c 'import json; v=json.load(open(\"$DAEMON\"))[\"log-opts\"][\"max-file\"]; assert isinstance(v, str), type(v)'"

# --verify exits non-zero when rotation is absent and zero when present. Both
# directions matter: an operator reads the exit code, and so could a future
# monitor-prod.sh check.
check "--verify succeeds once configured" "run_rotation --verify >/dev/null"

# ---------------------------------------------------------------------------
echo "  the rotation script preserves unrelated daemon settings"
# ---------------------------------------------------------------------------
# A daemon.json this script did not write may carry registry mirrors, a
# storage-driver or DNS settings. Clobbering those breaks the daemon on its next
# restart, at which point nothing comes back and the host is offline until
# someone finds the backup.
cat >"$DAEMON" <<'JSON'
{
  "storage-driver": "overlay2",
  "dns": ["1.1.1.1"]
}
JSON
run_rotation --apply >/dev/null
check "an unrelated key survives --apply" \
  "python3 -c 'import json; assert json.load(open(\"$DAEMON\"))[\"storage-driver\"] == \"overlay2\"'"
check "an unrelated list survives --apply" \
  "python3 -c 'import json; assert json.load(open(\"$DAEMON\"))[\"dns\"] == [\"1.1.1.1\"]'"
check "--apply still added the cap" \
  "python3 -c 'import json; assert json.load(open(\"$DAEMON\"))[\"log-opts\"][\"max-size\"]'"
check "--apply took a backup first" \
  "compgen -G '$DAEMON.bak.*' >/dev/null"

run_rotation --revert >/dev/null
check "--revert removes the cap" \
  "! python3 -c 'import json; json.load(open(\"$DAEMON\"))[\"log-opts\"][\"max-size\"]' 2>/dev/null"
check "--revert leaves unrelated keys alone" \
  "python3 -c 'import json; assert json.load(open(\"$DAEMON\"))[\"storage-driver\"] == \"overlay2\"'"

# ---------------------------------------------------------------------------
echo "  the rotation script refuses a malformed daemon.json"
# ---------------------------------------------------------------------------
# The daemon is running on its last-loaded config, so a file it cannot parse is
# a latent outage rather than a live one. Writing our own over the top would
# "fix" the symptom and silently discard whatever the operator was mid-edit on.
printf '{ this is not json' >"$DAEMON"
# Captured into a variable rather than checked with `$?` inside check(): by the
# time the eval runs, `$?` is check()'s own bookkeeping, not the script's.
malformed_status=0
run_rotation --apply >/dev/null 2>&1 || malformed_status=$?
check "--apply exits non-zero on malformed JSON" "[[ '$malformed_status' -ne 0 ]]"
check "--apply left the malformed file untouched" \
  "grep -q 'this is not json' '$DAEMON'"

# ---------------------------------------------------------------------------
echo
echo "  $((checks - failures))/$checks checks passed"
exit $((failures > 0 ? 1 : 0))
