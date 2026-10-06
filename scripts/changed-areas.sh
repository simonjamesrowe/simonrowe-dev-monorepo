#!/usr/bin/env bash
#
# Decides which parts of the build a set of changed paths needs: which CI jobs run on a pull
# request, and which images Publish rebuilds on a merge to main.
#
# Usage:
#   scripts/changed-areas.sh <base-sha> <head-sha>     # diffs <base>...<head>
#   printf 'a\nb\n' | scripts/changed-areas.sh         # decides for exactly those paths
#
# The stdin form is what the tests use, as with classify-change.sh: it needs no repository,
# so the rules can be exercised against paths that do not exist yet - which is what the
# default rule below is about.
#
# Output is GITHUB_OUTPUT-shaped, one line per area, every area always present:
#
#   backend=true|false          CI: Backend Build & Test
#   frontend=true|false         CI: Frontend Build & Test
#   factory=true|false          CI: software-factory Gradle checks and its image build
#   shell=true|false            CI: scripts/test/run-tests.sh (the deploy shell tests)
#   image_backend=true|false    Publish: rebuild the backend image
#   image_frontend=true|false   Publish: rebuild the frontend image
#   image_factory=true|false    Publish: rebuild the software-factory image
#   reason=<one line>           why, for the job log
#
# THE DEFAULT IS EVERYTHING. A path no rule recognises turns every area on, as does a base
# that cannot be diffed (a new branch, a force-push, a shallow clone). Skipping work is the
# optimisation and must be earned by a rule; a new top-level directory gets the full build
# until somebody writes one. That is the same default-deny shape as classify-change.sh's rule 4,
# for the same reason: the failure mode of a missing rule is a slower build, never an untested
# change.
#
# Rules are about what each build actually READS, not where a file lives. The ones that look
# surprising are all of that kind:
#   - docker-compose.prod.yml is baked into the backend image (ProdImageCatalog) and read by
#     both Java test suites, so it rebuilds the backend.
#   - config/nginx/ is read by software-factory's FactoryPublicSurfaceTest.
#   - scripts/classify-change.sh and its fixtures are read by MergeDispositionTest.
#   - the shell tests read files from almost everywhere (nginx confs, the compose file, both
#     Dockerfiles, the workflows, the backend's logback config), so they run for any change
#     that is not documentation. They take about 25 seconds.
set -euo pipefail

AREAS=(backend frontend factory shell image_backend image_frontend image_factory)

# One variable per area (on_backend, ...) rather than an associative array, which the bash 3.2
# that macOS ships does not have.
for area in "${AREAS[@]}"; do
  printf -v "on_$area" false
done

reason=""

everything() {
  turn_on "${AREAS[@]}"
  reason="$1"
}

turn_on() {
  local area
  for area in "$@"; do
    printf -v "on_$area" true
  done
}

# Decides one path. Returns 1 when no rule recognises it.
classify() {
  case "$1" in
    # Documentation and design material: read by no build.
    docs/*|specs/*|designs/*|ideas/*|ideas.md|stitch/*) ;;
    .claude/*|.superpowers/*|.conductor/*) ;;
    .gitignore|.editorconfig|.env.example|.github/rulesets/*) ;;
    # Evals have their own workflow (evals.yml), triggered by its own paths.
    evals/*) ;;
    # Markdown at the root only. Markdown under backend/ or frontend/ can be shipped content
    # (the portfolio seed copy is .md), so it falls through to its module's rule.
    */*) classify_nested "$1" ;;
    *.md) ;;

    build.gradle.kts|settings.gradle.kts|gradle.properties|gradlew|gradlew.bat)
      turn_on backend factory shell image_backend image_factory ;;
    docker-compose.prod.yml)
      turn_on backend factory shell image_backend ;;
    docker-compose.yml)
      turn_on shell ;;
    Dockerfile.frontend)
      turn_on shell image_frontend ;;
    Dockerfile.software-factory)
      turn_on factory shell image_factory ;;
    .dockerignore)
      turn_on factory shell image_backend image_frontend image_factory ;;
    *) return 1 ;;
  esac
}

classify_nested() {
  case "$1" in
    backend/*)
      turn_on backend shell image_backend ;;
    frontend/*)
      turn_on frontend shell image_frontend ;;
    software-factory/*)
      turn_on factory shell image_factory ;;
    gradle/*|config/checkstyle/*)
      turn_on backend factory shell image_backend image_factory ;;
    config/nginx/*)
      turn_on factory shell ;;
    config/*)
      turn_on shell ;;
    scripts/classify-change.sh|scripts/test/fixtures/*)
      turn_on factory shell ;;
    scripts/*)
      turn_on shell ;;
    *) return 1 ;;
  esac
}

decide() {
  local path count=0 unknown=""
  while IFS= read -r path; do
    [[ -z "$path" ]] && continue
    count=$((count + 1))
    if ! classify "$path"; then
      unknown="$path"
      break
    fi
  done
  if [[ -n "$unknown" ]]; then
    everything "no rule for $unknown, so building everything"
  elif [[ "$count" -eq 0 ]]; then
    reason="no changed paths"
  else
    reason="$count changed path(s)"
  fi
}

if [[ -t 0 && $# -eq 0 ]]; then
  echo "usage: $0 <base-sha> <head-sha>   or   paths on stdin" >&2
  exit 64
fi

if [[ $# -eq 0 ]]; then
  decide
else
  base="${1:-}"
  head="${2:-HEAD}"
  # An all-zero base is what GitHub sends for the first push of a branch.
  if [[ -z "$base" || "$base" =~ ^0+$ ]]; then
    everything "no base commit to compare against, so building everything"
  # --no-renames is load-bearing: a rename is otherwise listed under its NEW path only, so
  # moving a file from backend/ into docs/ would read as a docs-only change and build nothing,
  # while the backend had just lost a file. Without rename detection both paths are listed.
  elif ! changed="$(git diff --name-only --no-renames "$base...$head" 2>/dev/null)"; then
    everything "cannot diff $base...$head (shallow clone or force-push?), so building everything"
  else
    decide <<<"$changed"
  fi
fi

for area in "${AREAS[@]}"; do
  value="on_$area"
  echo "$area=${!value}"
done
echo "reason=$reason"
