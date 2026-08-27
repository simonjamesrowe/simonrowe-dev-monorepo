#!/usr/bin/env bash
#
# Entrypoint for the shell test suite. Runs every scripts/test/test-*.sh and
# reports pass/fail counts.
#
# DRY_RUN=1 is exported here rather than left to each test, on purpose. Every
# remediation path in restart-prod.sh and monitor-prod.sh shells out to
# `docker compose`, so merely running one of those scripts performs real restarts
# and can recreate containers if the compose file has been edited since the last
# deploy. A test that forgot to set DRY_RUN would not fail — it would deploy.
# Setting it once, here, means forgetting is not possible.
#
# Plain bash rather than bats: three test files do not justify adding a shell
# test framework as a project dependency (Constitution Principle V).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

export DRY_RUN=1

passed=0
failed=0
failures=()

shopt -s nullglob
tests=("$SCRIPT_DIR"/test-*.sh)
shopt -u nullglob

if [[ "${#tests[@]}" -eq 0 ]]; then
  echo "No tests found in $SCRIPT_DIR"
  exit 0
fi

for test in "${tests[@]}"; do
  name="$(basename "$test")"
  echo
  echo "=== $name"
  # Each test runs in its own bash so a `set -e` abort inside one does not stop
  # the suite, and so one test cannot leak shell state into the next.
  if bash "$test"; then
    passed=$((passed + 1))
    echo "--- PASS $name"
  else
    failed=$((failed + 1))
    failures+=("$name")
    echo "--- FAIL $name"
  fi
done

echo
echo "================================"
printf 'passed: %d  failed: %d\n' "$passed" "$failed"
if [[ "$failed" -ne 0 ]]; then
  printf 'failed tests:\n'
  printf '  %s\n' "${failures[@]}"
  exit 1
fi
