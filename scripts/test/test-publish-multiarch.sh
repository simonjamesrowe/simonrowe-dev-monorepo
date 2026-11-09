#!/usr/bin/env bash
#
# Guards the shape of .github/workflows/publish.yml that makes the software-factory and
# page-reader images multi-arch (arm64 for the Pi, amd64 for the NUC) while backend and
# frontend stay arm64-only.
#
# None of this can be exercised without GitHub Actions and a registry, and every way of getting
# it wrong is quiet until a deploy: an emulated amd64 build carries arm64 binaries (the AOT cache
# and page-reader's native bridge and Chromium are per-arch), a per-platform re-tag decision can
# publish half an index, and a re-tag without the platform requirement republishes the last
# arm64-only image for a host that cannot run it. So the structure is pinned here. The
# behaviour of the re-tag itself is tested in test-retag-image.sh.
#
# The workflow is parsed as YAML (Ruby's standard library, present on GitHub's Ubuntu runners
# and macOS) and queried with jq, rather than grepped, so that a check means "this job" and not
# "somewhere in the file".
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
# Overridable only so the checks can be mutation-tested against an edited copy.
WORKFLOW="${PUBLISH_WORKFLOW:-$PROJECT_DIR/.github/workflows/publish.yml}"

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

if ! command -v ruby >/dev/null 2>&1; then
  echo "    FAIL: ruby is needed to parse publish.yml"
  exit 1
fi

if ! WF="$(ruby -ryaml -rjson -e 'puts JSON.generate(YAML.load_file(ARGV[0]))' "$WORKFLOW")"; then
  echo "    FAIL: publish.yml does not parse as YAML"
  exit 1
fi

# q <jq filter> - query the workflow as JSON.
q() { jq -r "$1" <<<"$WF"; }
# steps_run <job> - every `run:` of a job, concatenated.
steps_run() { q ".jobs[\"$1\"].steps[]? | .run // empty"; }
# step <job> <uses prefix> - the `with:` of the first step using that action, as JSON.
step_with() { q "[.jobs[\"$1\"].steps[]? | select((.uses // \"\") | startswith(\"$2\"))][0].with // {} | tojson"; }

echo "  the jobs exist"
for job in changes publish-backend publish-frontend \
    software-factory-plan software-factory-build publish-software-factory \
    page-reader-plan page-reader-build publish-page-reader sbom; do
  check "job $job exists" "[[ \"\$(q '.jobs | has(\"$job\")')\" == true ]]"
done

echo "  backend and frontend are unchanged: arm64 only, single-platform re-tag"
for job in publish-backend publish-frontend; do
  check "$job builds on ubuntu-24.04-arm" "[[ \"\$(q '.jobs[\"$job\"][\"runs-on\"]')\" == ubuntu-24.04-arm ]]"
  check "$job re-tags with no platform requirement" \
    "steps_run $job | grep -qE 'retag-image\\.sh \"\\\$IMAGE\" \"\\\$BEFORE\" \"\\\$AFTER\" \\|'"
done

for img in software-factory page-reader; do
  plan="$img-plan" build="$img-build" publish="publish-$img"
  echo "  $img is built natively per platform and published as one index"

  check "$plan sets PLATFORMS to linux/arm64,linux/amd64" \
    "[[ \"\$(q '.jobs[\"$plan\"].env.PLATFORMS')\" == linux/arm64,linux/amd64 ]]"
  check "$plan re-tags only from an index of both platforms" \
    "steps_run $plan | grep -qF 'retag-image.sh \"\$IMAGE\" \"\$BEFORE\" \"\$AFTER\" \"\$PLATFORMS\"'"
  check "$plan exposes one build decision" \
    "[[ \"\$(q '.jobs[\"$plan\"].outputs.build')\" == *steps.decide.outputs.build* ]]"

  # The decision is made once: a build leg that re-tagged on its own could publish one
  # platform re-tagged and the other rebuilt.
  check "$build never re-tags (the decision is made once, in $plan)" \
    "! steps_run $build | grep -q 'retag-image'"
  check "$build needs $plan" "[[ \"\$(q '.jobs[\"$build\"].needs')\" == $plan ]]"
  check "$build runs only when $plan decided to build" \
    "[[ \"\$(q '.jobs[\"$build\"].if')\" == *needs.$plan.outputs.build*\'true\'* ]]"

  check "$build has an arm64 leg on ubuntu-24.04-arm" \
    "[[ \"\$(q '.jobs[\"$build\"].strategy.matrix.include[] | select(.arch == \"arm64\") | .runner')\" == ubuntu-24.04-arm ]]"
  check "$build has an amd64 leg on ubuntu-24.04" \
    "[[ \"\$(q '.jobs[\"$build\"].strategy.matrix.include[] | select(.arch == \"amd64\") | .runner')\" == ubuntu-24.04 ]]"
  check "$build has exactly those two legs" \
    "[[ \"\$(q '.jobs[\"$build\"].strategy.matrix.include | length')\" == 2 ]]"
  check "$build runs on the leg's own runner" \
    "[[ \"\$(q '.jobs[\"$build\"][\"runs-on\"]')\" == *matrix.runner* ]]"

  with="$(step_with "$build" docker/build-push-action@)"
  check "$build builds only the leg's own platform" \
    "[[ \"\$(jq -r .platforms <<<'$with')\" == 'linux/\${{ matrix.arch }}' ]]"
  check "$build pushes by digest" \
    "[[ \"\$(jq -r .outputs <<<'$with')\" == *push-by-digest=true* ]]"
  check "$build pushes no tag (only the finished index may carry :<sha>)" \
    "[[ \"\$(jq -r 'has(\"tags\")' <<<'$with')\" == false ]]"

  check "$publish needs $plan and $build" \
    "[[ \"\$(q '.jobs[\"$publish\"].needs | sort | join(\",\")')\" == $build,$plan ]]"
  check "$publish runs on the re-tag path as well as after a build" \
    "[[ \"\$(q '.jobs[\"$publish\"].if')\" == *needs.$plan.outputs.build*\'true\'*needs.$build.result*\'success\'* ]]"
  publish_run="$(steps_run "$publish")"
  check "$publish creates the index under :<sha> and :latest" \
    "grep -qF -- '--tag \"\$IMAGE:\$GITHUB_SHA\"' <<<\"\$publish_run\" && grep -qF -- '--tag \"\$IMAGE:latest\"' <<<\"\$publish_run\""
  check "$publish merges both legs' digests" \
    "grep -qF '\"\$IMAGE@\$(cat arm64)\"' <<<\"\$publish_run\" && grep -qF '\"\$IMAGE@\$(cat amd64)\"' <<<\"\$publish_run\""
  check "$publish asserts the published image serves both platforms" \
    "grep -qF 'for arch in arm64 amd64' <<<\"\$publish_run\""
done

echo "  the software-factory build keeps its build metadata"
with="$(step_with software-factory-build docker/build-push-action@)"
for arg in 'GIT_SHA=${{ github.sha }}' 'GIT_COMMIT_TIME=${{ steps.commit-meta.outputs.time }}' \
    'GIT_COMMIT_SUBJECT=${{ steps.commit-meta.outputs.subject }}'; do
  check "build-arg ${arg%%=*}" "jq -r '.\"build-args\"' <<<'$with' | grep -qxF '$arg'"
done

echo "  nothing is emulated"
check "no job sets up QEMU" "! grep -q 'setup-qemu-action' '$WORKFLOW'"
check "no build names more than one platform" \
  "! q '.jobs[].steps[]? | .with.platforms // empty' | grep -q ','"

echo "  the sbom job scans one platform of the software-factory index"
check "sbom still needs publish-software-factory" \
  "q '.jobs.sbom.needs[]' | grep -qx publish-software-factory"
check "publish-software-factory hands on the digest to scan" \
  "[[ \"\$(q '.jobs[\"publish-software-factory\"].outputs[\"scan-digest\"]')\" == *steps.assert.outputs.scan-digest* ]]"
check "the factory SBOM is generated from that single-platform digest" \
  "[[ \"\$(q '.jobs.sbom.steps[] | select(.name == \"Generate software factory image SBOM\") | .with[\"image-ref\"]')\" == *@\\\${{\\ needs.publish-software-factory.outputs.scan-digest\\ }} ]]"

echo
if [[ "$failures" -gt 0 ]]; then
  echo "  $failures of $checks checks failed"
  exit 1
fi
echo "  all $checks checks passed"
