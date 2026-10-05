#!/usr/bin/env bash
#
# Publishes an unchanged image under a new commit's tag by re-tagging the previous commit's
# image in the registry. Used by Publish for every image a merge did not touch.
#
# Usage: scripts/ci/retag-image.sh <image> <previous-sha> <new-sha>
#
# Prints `retagged=true` when <image>:<new-sha> now exists, and `retagged=false` when the
# caller must build the image instead. It never fails the job for a reason a build would fix:
# a missing previous image (that commit's Publish failed or was cancelled, or there is no
# previous commit) and a failed re-tag both answer `false`, so the worst case of skipping is
# the build that would have happened anyway. Narration goes to stderr.
#
# `docker buildx imagetools create` copies the manifest within the registry: no layers are
# pulled. `--prefer-index=false` is load-bearing: without it, a single-platform manifest (which
# is what bootBuildImage pushes) is wrapped in a NEW image index with a different digest, and a
# Docker host on the containerd image store would see a different image and recreate the
# container - the restart this whole mechanism exists to avoid. With it, the copy is exact
# (verified against registry:2: same media type, same digest).
set -euo pipefail

image="${1:?image}"
previous="${2:-}"
new="${3:?new sha}"

if [[ -z "$previous" || "$previous" =~ ^0+$ ]]; then
  echo "No previous commit to re-tag from; building." >&2
  echo "retagged=false"
  exit 0
fi

if ! docker buildx imagetools inspect "$image:$previous" >/dev/null 2>&1; then
  echo "$image:$previous does not exist (its Publish failed or was cancelled); building." >&2
  echo "retagged=false"
  exit 0
fi

if ! docker buildx imagetools create --prefer-index=false --tag "$image:$new" "$image:$previous" >&2; then
  echo "Re-tagging $image:$previous as $new failed; building instead." >&2
  echo "retagged=false"
  exit 0
fi

echo "Published $image:$new as a re-tag of $image:$previous." >&2
echo "retagged=true"
