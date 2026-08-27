#!/usr/bin/env bash
#
# Classifies a set of changed paths into one merge disposition, so the pr-review-loop
# skill knows whether it may arm auto-merge, must capture screenshots, or must leave
# the merge to a human.
#
# Usage:
#   scripts/classify-change.sh [<base-ref>]        # diffs <base-ref>...HEAD (default origin/main)
#   printf 'a\nb\n' | scripts/classify-change.sh   # classifies exactly those paths
#
# The stdin form is what the tests use: it needs no repository state, so the rules can
# be exercised against paths that do not exist (which is the point of rule 4).
#
# Output is GITHUB_OUTPUT-shaped:
#
#   category=auto-merge|ux-review|manual
#   ux_affecting=true|false
#
# There is deliberately no `classify` job in ci.yml and no workflow consumes this. The
# key=value shape is kept anyway so the script stays usable if CI ever needs it, at no
# cost today.
#
# Exit status is 0 for every classification, INCLUDING manual. "Needs a human" is an
# answer, not an error; a non-zero exit is reserved for the script itself failing.
#
# This lives in a script rather than in skill prose because a default-deny path list is
# testable as a script and rots invisibly as prose.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

BASE_REF="${1:-origin/main}"

# ---------------------------------------------------------------------------
# Rule 1 — needs a human. Highest precedence, and it OUTRANKS rule 3 on purpose.
#
# An auto-merge to main triggers Publish, which triggers auto-deploy: an unattended
# infrastructure deploy against the Pi. 036-auto-deploy-rollout-fixes is a nine-item
# catalogue of ways those fail that no test catches, because they are all properties
# of running `docker compose` inside a container against the host daemon. So a change
# that touches infrastructure is manual even when the rest of it is backend-only.
# ---------------------------------------------------------------------------
is_manual_path() {
  case "$1" in
    docker-compose*.yml|docker-compose*.yaml) return 0 ;;
    scripts/*)                                return 0 ;;
    config/*)                                 return 0 ;;
    .github/*)                                return 0 ;;
    gradlew|gradlew.bat|gradle/*|gradle.properties) return 0 ;;
    settings.gradle*|build.gradle*)           return 0 ;;
    # frontend/vite.config.ts and friends change the shipped bundle, so they are manual
    # even though frontend/tests/** below is not.
    frontend/*.config.*)                      return 0 ;;
    frontend/package.json|frontend/package-lock.json) return 0 ;;
    *) return 1 ;;
  esac
}

# ---------------------------------------------------------------------------
# Rule 2 — changes a visitor can see. Needs screenshots and a human merge.
# ---------------------------------------------------------------------------
is_ux_path() {
  case "$1" in
    # Ordered before frontend/src/* so test paths under it are not swept up; in practice
    # frontend/tests and frontend/e2e are siblings of src, but the guard is cheap.
    frontend/tests/*|frontend/e2e/*) return 1 ;;
    frontend/src/*)                  return 0 ;;
    frontend/index.html)             return 0 ;;
    frontend/public/*)               return 0 ;;
    *) return 1 ;;
  esac
}

# ---------------------------------------------------------------------------
# Rule 3 — cannot change a shipped pixel or production infrastructure.
# frontend/tests/** and frontend/e2e/** qualify: they ship nothing.
# ---------------------------------------------------------------------------
is_auto_merge_path() {
  case "$1" in
    backend/*)          return 0 ;;
    software-factory/*) return 0 ;;
    docs/*)             return 0 ;;
    specs/*)            return 0 ;;
    frontend/tests/*)   return 0 ;;
    frontend/e2e/*)     return 0 ;;
    */*)                return 1 ;;   # any other nested path is unrecognised
    *.md)               return 0 ;;   # root-level markdown only, given the guard above
    *) return 1 ;;
  esac
}

# An explicit base ref always means "diff it". Otherwise piped stdin wins, and a bare
# invocation from a terminal falls back to diffing origin/main. Ordering it this way
# means passing a ref can never be silently ignored because something redirected stdin.
collect_paths() {
  if [[ $# -gt 0 ]]; then
    git -C "$PROJECT_DIR" diff --name-only "${BASE_REF}...HEAD"
  elif [[ ! -t 0 ]]; then
    cat
  else
    git -C "$PROJECT_DIR" diff --name-only "${BASE_REF}...HEAD"
  fi
}

category="auto-merge"
ux_affecting="false"
saw_any="false"

while IFS= read -r path; do
  [[ -z "$path" ]] && continue
  saw_any="true"

  if is_manual_path "$path"; then
    # Rule 1 is terminal: nothing outranks it, so stop looking.
    category="manual"
    ux_affecting="false"
    break
  elif is_ux_path "$path"; then
    category="ux-review"
    ux_affecting="true"
  elif is_auto_merge_path "$path"; then
    : # keeps whatever the highest-precedence match so far was
  else
    # ---------------------------------------------------------------------
    # Rule 4 — the default, and it is load-bearing.
    #
    # An unrecognised path is manual, NEVER auto-merge. A new top-level directory
    # added later therefore defaults to needing a human rather than silently
    # inheriting merge rights from a list nobody remembered to update.
    # ---------------------------------------------------------------------
    category="manual"
    ux_affecting="false"
    break
  fi
done < <(collect_paths "$@")

# An empty diff arms nothing. Failing closed on a change nobody can see is the same
# instinct as rule 4.
if [[ "$saw_any" != "true" ]]; then
  category="manual"
  ux_affecting="false"
fi

echo "category=${category}"
echo "ux_affecting=${ux_affecting}"
