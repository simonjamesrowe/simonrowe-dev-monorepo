#!/usr/bin/env bash
#
# Tests for scripts/changed-areas.sh, which decides which CI jobs run on a pull request and
# which images Publish rebuilds.
#
# Auto-discovered by run-tests.sh. Every case feeds paths on stdin rather than building a
# repository, so paths that do not exist can be decided - the default-to-everything rule is
# about exactly those. The diff form is covered once, against a throwaway repository.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
AREAS_SCRIPT="$PROJECT_DIR/scripts/changed-areas.sh"

ALL="backend frontend factory shell image_backend image_frontend image_factory"

checks=0
failures=0

# The areas the script turned on, space-separated in output order.
areas_on() {
  sed -n 's/^\([a-z_]*\)=true$/\1/p' | tr '\n' ' ' | sed 's/ *$//'
}

# expect <description> <expected areas, space-separated, or "" for none> <path>...
expect() {
  local description="$1" expected="$2"
  shift 2
  local input="" path
  for path in "$@"; do
    input+="${path}"$'\n'
  done
  local output actual status
  set +e
  output="$(printf '%s' "$input" | "$AREAS_SCRIPT" 2>&1)"
  status=$?
  set -e
  actual="$(printf '%s\n' "$output" | areas_on)"
  checks=$((checks + 1))
  if [[ "$status" -ne 0 ]]; then
    echo "    FAIL: $description - exited $status: $output"
    failures=$((failures + 1))
  elif [[ "$actual" != "$expected" ]]; then
    echo "    FAIL: $description"
    echo "          expected: [$expected]"
    echo "          actual:   [$actual]"
    failures=$((failures + 1))
  else
    echo "    ok: $description"
  fi
}

echo "  documentation builds nothing"
expect "docs only" "" docs/runbooks/deploy.md specs/050-x/spec.md
expect "root markdown" "" README.md CLAUDE.md AGENTS.md
expect "design material" "" designs/a.png ideas/b.md stitch/c.html ideas.md
expect "agent and editor config" "" .claude/settings.json .conductor/x .editorconfig .gitignore
expect "evals have their own workflow" "" evals/promptfooconfig.yaml
expect "the ruleset is applied by hand, not built" "" .github/rulesets/main.json

echo "  one module"
expect "frontend source" "frontend shell image_frontend" frontend/src/App.tsx
expect "frontend tests still rebuild the image" "frontend shell image_frontend" \
  frontend/tests/StatusPage.test.tsx
expect "backend source" "backend shell image_backend" \
  backend/src/main/java/com/simonrowe/Application.java
expect "markdown inside a module is shipped content, not docs" "backend shell image_backend" \
  backend/src/main/resources/seed/portfolio/term-time/page.md
expect "software-factory source" "factory shell image_factory" \
  software-factory/src/main/java/com/simonrowe/factory/FactoryApplication.java

echo "  files read across modules"
expect "the prod compose file is baked into the backend image" \
  "backend factory shell image_backend" docker-compose.prod.yml
expect "shared Gradle build" "backend factory shell image_backend image_factory" \
  build.gradle.kts
expect "version catalogue" "backend factory shell image_backend image_factory" \
  gradle/libs.versions.toml
expect "checkstyle rules" "backend factory shell image_backend image_factory" \
  config/checkstyle/google_checks.xml
expect "nginx proxy conf is read by FactoryPublicSurfaceTest" "factory shell" \
  config/nginx/nginx-proxy.conf
expect "merge-disposition fixtures are read by MergeDispositionTest" "factory shell" \
  scripts/test/fixtures/merge-disposition-cases.tsv
expect "the classifier is read by MergeDispositionTest" "factory shell" scripts/classify-change.sh
expect "deploy scripts only need the shell tests" "shell" scripts/restart-prod.sh
expect "other config only needs the shell tests" "shell" config/alloy/config.alloy
expect "the frontend Dockerfile rebuilds only that image" "shell image_frontend" Dockerfile.frontend
expect "the factory Dockerfile is built in CI too" "factory shell image_factory" \
  Dockerfile.software-factory
expect ".dockerignore shapes every image" \
  "factory shell image_backend image_frontend image_factory" .dockerignore

echo "  the default is everything"
expect "a workflow change exercises everything" "$ALL" .github/workflows/ci.yml
expect "a new top-level directory" "$ALL" infra/terraform/main.tf
expect "a new root file" "$ALL" Makefile
expect "one unknown path among known ones" "$ALL" frontend/src/App.tsx newthing/x
expect "union of two modules" "backend frontend shell image_backend image_frontend" \
  frontend/src/App.tsx backend/build.gradle.kts

echo "  reading a diff"
checks=$((checks + 1))
if [[ "$(printf '' | "$AREAS_SCRIPT" | sed -n 's/^reason=//p')" == "no changed paths" ]]; then
  echo "    ok: no paths builds nothing and says why"
else
  echo "    FAIL: an empty change should report 'no changed paths'"
  failures=$((failures + 1))
fi

repo="$(mktemp -d)"
trap 'rm -rf "$repo"' EXIT
(
  cd "$repo"
  git init -q
  git config user.email t@example.com
  git config user.name t
  mkdir -p frontend/src docs
  echo a >frontend/src/a.ts
  git add . && git commit -qm base
  echo b >docs/b.md
  echo c >frontend/src/a.ts
  git add . && git commit -qm change
)
base="$(git -C "$repo" rev-parse HEAD~1)"
head="$(git -C "$repo" rev-parse HEAD)"

check_diff() {
  local description="$1" expected="$2"
  shift 2
  local actual
  actual="$(cd "$repo" && "$AREAS_SCRIPT" "$@" | areas_on)"
  checks=$((checks + 1))
  if [[ "$actual" == "$expected" ]]; then
    echo "    ok: $description"
  else
    echo "    FAIL: $description - expected [$expected], got [$actual]"
    failures=$((failures + 1))
  fi
}

check_diff "diffs base...head" "frontend shell image_frontend" "$base" "$head"

# A file moved out of a module into docs/ removed something from that module's build.
(
  cd "$repo"
  git mv frontend/src/a.ts docs/a.ts
  git commit -qm move
)
moved="$(git -C "$repo" rev-parse HEAD)"
check_diff "a rename out of a module counts against that module" "frontend shell image_frontend" \
  "$head" "$moved"
check_diff "an all-zero base (first push of a branch) builds everything" "$ALL" \
  0000000000000000000000000000000000000000 "$head"
check_diff "a base that is not in the clone builds everything" "$ALL" \
  1111111111111111111111111111111111111111 "$head"
check_diff "an empty base builds everything" "$ALL" "" "$head"

echo
echo "  $checks checks, $failures failures"
[[ "$failures" -eq 0 ]]
