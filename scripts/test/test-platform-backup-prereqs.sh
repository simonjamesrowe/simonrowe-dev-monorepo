#!/usr/bin/env bash
#
# Reconciles scripts/backup-platform.sh's check_prerequisites() against the
# packages Dockerfile.software-factory installs into its RUNTIME stage.
#
# WHY THIS EXISTS
#   backup-platform.sh runs inside the `deployer` container, which is a second
#   instance of the software-factory image. That image installed
#   `ca-certificates git curl jq` and nothing else, while the script requires
#   python3 and zip - so the nightly platform backup failed at its very first
#   check, every night, from the day 034 shipped:
#
#     java.lang.IllegalStateException: backup-platform.sh exited with 1:
#       [backup-platform] ERROR: python3 is required (JSON handling)
#
#   Nothing else noticed. Temporal retried the activity and gave up, the run was
#   recorded as failed in a collection nobody reads, and the Software Factory
#   console's platform-backup node showed a failure that looks identical to a
#   transient one. The gap was only ever visible in the container log, which is
#   how the log-watch module eventually filed it (SIM-30, SIM-34).
#
#   The two files are edited for unrelated reasons by unrelated changes - one is
#   a shell script, the other is an image definition - and neither build step
#   executes the other. This test is the only thing that ties them together.
#
# A shell test rather than a Java one because the subjects are a host script and
# a Dockerfile owned by neither Gradle module - the same reasoning as
# test-log-shipping.sh and test-frontend-nginx-shipping.sh, which this follows.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

BACKUP_SCRIPT="$PROJECT_DIR/scripts/backup-platform.sh"
DOCKERFILE="$PROJECT_DIR/Dockerfile.software-factory"
COMPOSE_FILE="$PROJECT_DIR/docker-compose.prod.yml"

# Commands the image is NOT expected to apt-get install, each with the reason it
# is nevertheless present at runtime. Anything not listed here and not installed
# by the Dockerfile fails the test, so a NEW prerequisite has to be deliberately
# accounted for rather than silently assumed.
#
#   docker     - not installed at all. The compose file bind-mounts the HOST's
#                docker CLI and plugins into the deployer (DOCKER_BINARY_PATH /
#                DOCKER_PLUGINS_PATH), because the container talks to the host
#                daemon through the mounted socket. Asserted below.
#   sha256sum  - GNU coreutils, present in the eclipse-temurin base image. The
#                script accepts either this or shasum and only needs one.
#   shasum     - the macOS spelling, for a human running the script on a laptop.
#
# A case statement rather than an associative array: bash 3.2 is what
# `/usr/bin/env bash` resolves to on macOS, and `declare -A` is a bash 4
# feature, so the array form fails on a laptop and passes in CI.
exemption_for() {
  case "$1" in
    docker)    printf 'bind-mounted from the host by docker-compose.prod.yml' ;;
    sha256sum) printf 'coreutils, in the base image' ;;
    shasum)    printf 'macOS spelling; sha256sum satisfies the same check' ;;
    *)         return 1 ;;
  esac
}

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

fail() {
  failures=$((failures + 1))
  checks=$((checks + 1))
  echo "    FAIL: $1"
}

pass() {
  checks=$((checks + 1))
  echo "    ok: $1"
}

echo "  Subjects:"
echo "    $BACKUP_SCRIPT"
echo "    $DOCKERFILE"

[[ -f "$BACKUP_SCRIPT" ]] || { echo "    FAIL: missing $BACKUP_SCRIPT"; exit 1; }
[[ -f "$DOCKERFILE" ]] || { echo "    FAIL: missing $DOCKERFILE"; exit 1; }

# ---------------------------------------------------------------------------
# What the script demands
# ---------------------------------------------------------------------------
# Read only check_prerequisites()'s body, not the whole file: `command -v` also
# appears in comments and in other helpers, and a prerequisite is precisely the
# thing that aborts the run before any work happens.
prereq_body="$(awk '/^check_prerequisites\(\) \{/{f=1} f{print} f&&/^\}/{exit}' "$BACKUP_SCRIPT")"

if [[ -z "$prereq_body" ]]; then
  fail "could not locate check_prerequisites() in backup-platform.sh"
  echo
  echo "  checks: $checks  failures: $failures"
  exit 1
fi

required="$(grep -oE 'command -v [a-z0-9_.-]+' <<<"$prereq_body" \
  | awk '{print $3}' | sort -u)"

check "check_prerequisites() names at least three commands" \
  "[[ \$(wc -l <<<\"\$required\") -ge 3 ]]"

# ---------------------------------------------------------------------------
# What the runtime image provides
# ---------------------------------------------------------------------------
# Only the FINAL stage counts. The `claude` stage installs curl and jq too, and
# it is thrown away - relying on it is the exact mistake that left jq out of the
# runtime image until 036 put it back.
runtime_stage="$(awk '/^FROM eclipse-temurin:[0-9]+-jre[[:space:]]*$/{f=1} f{print}' "$DOCKERFILE")"

check "Dockerfile has a final eclipse-temurin JRE stage" \
  "[[ -n \"\$runtime_stage\" ]]"

# Everything on the apt-get install line(s) of that stage, with the shell line
# continuations folded away and the flags dropped.
#
# Folding first is what makes this work at all: the install list is wrapped
# across lines, so a per-line grep sees `apt-get install -y
# --no-install-recommends` and none of the package names. That reads as "the
# image installs nothing", which is a false PASS in the direction that matters.
installed="$(printf '%s\n' "$runtime_stage" \
  | awk '{ line = line $0; if (sub(/\\$/, "", line)) next; print line; line = "" }' \
  | awk '
      /apt-get install/ {
        sub(/.*apt-get install/, "")
        sub(/&&.*/, "")
        n = split($0, words, /[[:space:]]+/)
        for (i = 1; i <= n; i++) {
          if (words[i] != "" && words[i] !~ /^-/) print words[i]
        }
      }' \
  | sort -u)"

echo "  Runtime stage installs: $(tr '\n' ' ' <<<"$installed")"

# ---------------------------------------------------------------------------
# Reconcile
# ---------------------------------------------------------------------------
# A prerequisite is satisfied when the image installs a package of that name, or
# a package whose name starts with it (python3 is provided by python3-minimal).
while read -r tool; do
  [[ -n "$tool" ]] || continue
  if grep -qE "^${tool}(-[a-z0-9.]+)?$" <<<"$installed"; then
    pass "$tool is installed by the runtime stage"
  elif reason="$(exemption_for "$tool")"; then
    pass "$tool is exempt ($reason)"
  else
    fail "backup-platform.sh requires '$tool', which the software-factory runtime stage neither installs nor exempts"
  fi
done <<<"$required"

# The two that broke it. Named explicitly as well as reconciled above, so the
# test still says what went wrong if someone rewrites check_prerequisites().
check "runtime stage installs python3 (backup-platform.sh's JSON handling)" \
  "grep -qE '^python3(-[a-z0-9.]+)?\$' <<<\"\$installed\""
check "runtime stage installs zip (backup-platform.sh builds the archive with zip -qr)" \
  "grep -qE '^zip\$' <<<\"\$installed\""

# jq must stay: restart-prod.sh's container settle loop parses
# `docker compose ps --format json` with it, and a missing jq there makes every
# container look settled.
check "runtime stage still installs jq (restart-prod.sh's settle loop)" \
  "grep -qE '^jq\$' <<<\"\$installed\""

# The docker exemption is only true while the compose file actually mounts the
# host CLI into the deployer. If that mount goes, `command -v docker` fails and
# the exemption above becomes a lie.
if [[ -f "$COMPOSE_FILE" ]]; then
  check "docker-compose.prod.yml still bind-mounts the host docker CLI (the 'docker' exemption)" \
    "grep -q 'DOCKER_BINARY_PATH' \"\$COMPOSE_FILE\""
fi

echo
echo "  checks: $checks  failures: $failures"
[[ "$failures" -eq 0 ]]
