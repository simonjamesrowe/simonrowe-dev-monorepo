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

echo
echo "  rule 3 — cannot change a shipped pixel or production infrastructure"
expect "backend source is auto-merge" \
  auto-merge false "backend/src/main/java/A.java"
expect "software-factory plus docs is auto-merge" \
  auto-merge false "software-factory/src/main/java/B.java" "docs/runbooks/x.md"
expect "frontend unit tests ship no pixel" \
  auto-merge false "frontend/tests/foo.test.ts"
expect "frontend e2e tests ship no pixel" \
  auto-merge false "frontend/e2e/chat.spec.ts"
expect "root markdown is auto-merge" \
  auto-merge false "README.md"
expect "a spec directory is auto-merge" \
  auto-merge false "specs/038-pr-governance/spec.md"

echo
echo "  rule 2 — a visitor can see it"
expect "frontend application source needs visual review" \
  ux-review true "frontend/src/App.tsx"
expect "public assets need visual review" \
  ux-review true "frontend/public/logo.svg"
expect "the entry document needs visual review" \
  ux-review true "frontend/index.html"

echo
echo "  rule 1 — needs a human"
expect "vite config changes the shipped bundle" \
  manual false "frontend/vite.config.ts"
expect "frontend dependencies change the shipped bundle" \
  manual false "frontend/package.json"
expect "the production compose file is infrastructure" \
  manual false "docker-compose.prod.yml"
expect "scripts are infrastructure" \
  manual false "scripts/monitor-prod.sh"
expect "workflows are infrastructure" \
  manual false ".github/workflows/ci.yml"
expect "nginx config is infrastructure" \
  manual false "config/nginx/nginx-proxy.conf"
expect "the gradle wrapper is infrastructure" \
  manual false "gradlew"

echo
echo "  precedence"
# The one that protects production: an auto-merge to main triggers Publish, which
# triggers an unattended infrastructure deploy against the Pi.
expect "rule 1 beats rule 3 — backend plus compose is manual" \
  manual false "backend/src/A.java" "docker-compose.prod.yml"
expect "rule 2 beats rule 3 — backend plus frontend source needs visual review" \
  ux-review true "backend/src/A.java" "frontend/src/App.tsx"
expect "rule 1 beats rule 2 — frontend source plus a script is manual" \
  manual false "frontend/src/App.tsx" "scripts/x.sh"
expect "order does not matter: the script first" \
  manual false "scripts/x.sh" "frontend/src/App.tsx"

echo
echo "  rule 4 — the default-deny that stops a new directory inheriting merge rights"
expect "an unrecognised top-level directory is manual" \
  manual false "newtoplevel/thing.txt"
expect "one unrecognised path is enough to demand a human" \
  manual false "specs/038-pr-governance/spec.md" "newtoplevel/x"
expect "an unrecognised root file is manual" \
  manual false "Makefile"
expect "an empty change arms nothing" \
  manual false

echo
echo "  $checks checks, $failures failures"
[[ "$failures" -eq 0 ]]
