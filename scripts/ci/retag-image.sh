#!/usr/bin/env bash
#
# Publishes an unchanged image under a new commit's tag by re-tagging the previous commit's
# image in the registry. Used by Publish for every image a merge did not touch.
#
# Usage: scripts/ci/retag-image.sh <image> <previous-sha> <new-sha> [<platforms>]
#
# <platforms> is optional, a comma-separated list such as `linux/arm64,linux/amd64`. When it
# is given, the previous image is reused only if it is a multi-platform index listing every
# one of them. The multi-arch images (software-factory, page-reader) pass it; backend and
# frontend, which are single-platform arm64 manifests, do not.
#
# Prints `retagged=true` when <image>:<new-sha> now exists as an exact copy of the previous
# image, and `retagged=false` when the caller must build the image instead. It never fails the
# job for a reason a build would fix: a missing previous image (that commit's Publish failed or
# was cancelled, or there is no previous commit), a previous image lacking a required platform,
# a failed re-tag and a re-tag that did not come out byte-identical all answer `false`, so the
# worst case of skipping is the build that would have happened anyway. Narration goes to
# stderr.
#
# `docker buildx imagetools create` copies the manifest within the registry: no layers are
# pulled. `--prefer-index=false` is load-bearing for a single-platform manifest: without it, the
# manifest (which is what bootBuildImage pushes) is wrapped in a NEW image index with a
# different digest, and a Docker host on the containerd image store would see a different image
# and recreate the container - the restart this whole mechanism exists to avoid. With it, the
# copy is exact.
#
# The same invocation is also right for a multi-platform index, so there is one code path, not
# two. With a single source that is already an index, buildx returns the source's own bytes
# whatever --prefer-index says (the flag only decides whether a lone *manifest* gets wrapped),
# so the whole index - every platform's manifest - is carried over unchanged, and the index
# digest and each per-platform digest stay identical. That is what lets the deploy plan on BOTH
# hosts (the arm64 Pi and the amd64 NUC) see an unchanged image. Verified on 2026-11-09 against
# registry:2 with buildx 0.33.0: an index re-tagged with and without the flag kept its digest
# (byte-identical `--raw`); a single manifest kept its digest only with the flag.
#
# The digests are compared after the copy anyway rather than trusted to that reading of buildx:
# a mismatch answers `false`, and the build that follows overwrites the tag.
set -euo pipefail

image="${1:?image}"
previous="${2:-}"
new="${3:?new sha}"
platforms="${4:-}"

rebuild() {
  echo "$1" >&2
  echo "retagged=false"
  exit 0
}

if [[ -z "$previous" || "$previous" =~ ^0+$ ]]; then
  rebuild "No previous commit to re-tag from; building."
fi

if ! raw="$(docker buildx imagetools inspect --raw "$image:$previous" 2>/dev/null)" \
    || [[ -z "$raw" ]]; then
  rebuild "$image:$previous does not exist (its Publish failed or was cancelled); building."
fi

# An index carries a `manifests` array; a single-platform manifest carries `layers` instead.
# Read the shape, not `mediaType`, which the OCI spec leaves optional on an index.
if ! kind="$(jq -r 'if (.manifests | type) == "array" then "index" else "manifest" end' \
    <<<"$raw" 2>/dev/null)"; then
  rebuild "Could not read the manifest of $image:$previous; building."
fi

if [[ -n "$platforms" ]]; then
  # Without this, the first merge after an image goes multi-arch would re-tag the last
  # arm64-only build, and the amd64 host could not pull the new tag at all.
  if [[ "$kind" != "index" ]]; then
    rebuild "$image:$previous is a single-platform manifest, not an index of $platforms; building."
  fi
  listed="$(jq -r '.manifests[] | .platform | select(. != null)
      | [.os, .architecture] + (if .variant then [.variant] else [] end) | join("/")' \
    <<<"$raw")"
  # `linux/arm64` is satisfied by `linux/arm64` or by any variant of it (`linux/arm64/v8`):
  # registries and builders disagree on whether to write arm64's variant, and either form is
  # the image an arm64 host pulls.
  IFS=',' read -r -a wanted <<<"$platforms"
  for platform in "${wanted[@]}"; do
    found=false
    while IFS= read -r entry; do
      if [[ "$entry" == "$platform" || "$entry" == "$platform/"* ]]; then
        found=true
      fi
    done <<<"$listed"
    if [[ "$found" != true ]]; then
      rebuild "$image:$previous has no $platform image (it lists: $(tr '\n' ' ' <<<"$listed")); building."
    fi
  done
fi

if ! source_digest="$(docker buildx imagetools inspect --format '{{.Manifest.Digest}}' \
    "$image:$previous" 2>/dev/null)" || [[ -z "$source_digest" ]]; then
  rebuild "Could not read the digest of $image:$previous; building."
fi

if ! docker buildx imagetools create --prefer-index=false --tag "$image:$new" "$image:$previous" >&2; then
  rebuild "Re-tagging $image:$previous as $new failed; building instead."
fi

copied_digest="$(docker buildx imagetools inspect --format '{{.Manifest.Digest}}' \
  "$image:$new" 2>/dev/null || true)"
if [[ "$copied_digest" != "$source_digest" ]]; then
  rebuild "$image:$new came out as ${copied_digest:-nothing}, not $source_digest; building instead."
fi

echo "Published $image:$new as a re-tag of $image:$previous ($kind $source_digest)." >&2
echo "retagged=true"
