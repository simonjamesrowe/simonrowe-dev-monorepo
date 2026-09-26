#!/usr/bin/env bash
#
# Tests for scripts/classify-change.sh.
#
# Auto-discovered by run-tests.sh, which globs test-*.sh. That suite exports DRY_RUN=1
# because every remediation path in restart-prod.sh and monitor-prod.sh shells out to
# `docker compose`. The classifier shells out to nothing and never touches Docker, so it
# neither honours DRY_RUN nor needs it — and these tests must not come to depend on it,
# or they would silently stop being runnable on their own.
#
# Every case feeds paths on stdin rather than building a repository, so a path that does
# not exist can be classified. That is not a convenience: rule 4 is specifically about
# paths nobody has created yet, and it cannot be tested any other way.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CLASSIFY="$PROJECT_DIR/scripts/classify-change.sh"

checks=0
failures=0

# expect <description> <expected-category> <expected-ux-affecting> <path>...
expect() {
  local description="$1" expected_category="$2" expected_ux="$3"
  shift 3

  local input=""
  local path
  for path in "$@"; do
    input+="${path}"$'\n'
  done

  local output status
  set +e
  output="$(printf '%s' "$input" | "$CLASSIFY" 2>&1)"
  status=$?
  set -e

  local actual_category actual_ux
  actual_category="$(printf '%s\n' "$output" | sed -n 's/^category=//p')"
  actual_ux="$(printf '%s\n' "$output" | sed -n 's/^ux_affecting=//p')"

  checks=$((checks + 1))
  if [[ "$status" -ne 0 ]]; then
    echo "    FAIL: $description — exited $status, output: $output"
    failures=$((failures + 1))
  elif [[ "$actual_category" != "$expected_category" || "$actual_ux" != "$expected_ux" ]]; then
    echo "    FAIL: $description"
    echo "          expected category=$expected_category ux_affecting=$expected_ux"
    echo "          actual   category=$actual_category ux_affecting=$actual_ux"
    failures=$((failures + 1))
  else
    echo "    ok: $description"
  fi
}

echo "  the script itself"
checks=$((checks + 1))
if [[ -x "$CLASSIFY" ]]; then
  echo "    ok: classify-change.sh is executable"
else
  echo "    FAIL: classify-change.sh is not executable"
  failures=$((failures + 1))
fi

# The cases live in a fixture shared with the software factory's Java twin of the
# classifier (codereview/domain/MergeDisposition.java, tested by MergeDispositionTest), so
# the rules the factory arms auto-merge from cannot drift from the ones this script applies.
FIXTURE="$SCRIPT_DIR/fixtures/merge-disposition-cases.tsv"

while IFS=$'\t' read -r description category ux paths || [[ -n "$description" ]]; do
  case "$description" in
    '#!'*) echo; echo "  ${description#\#! }"; continue ;;
    '#'*|'') continue ;;
  esac
  # Word-splitting the path list is the intent: paths in the fixture contain no spaces.
  # shellcheck disable=SC2086
  expect "$description" "$category" "$ux" $paths
done < "$FIXTURE"

echo
echo "  $checks checks, $failures failures"
[[ "$failures" -eq 0 ]]
