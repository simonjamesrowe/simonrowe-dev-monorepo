# shellcheck shell=bash
#
# The one image prune both production scripts run. SOURCED, not executed, so the
# docker call goes through the caller's own run_cmd and therefore honours that
# caller's DRY_RUN semantics (restart-prod.sh: non-empty; monitor-prod.sh: != 0).
#
#   restart-prod.sh  at the end of a deploy that fully verified (verify-public, all)
#   monitor-prod.sh  when the filesystem holding /var/lib/docker crosses its threshold
#
# Why it exists: on 2026-09-28 the Pi's root filesystem hit 100% and took Kafka,
# Postgres and Mongo down with it, then the backend's writable layer (a truncated
# cacerts). `docker system df` showed 217 images, 25 in use, 45GB reclaimable.
# Every deploy pulls three new images and nothing ever deleted the old ones.
#
# WHAT IT CAN REMOVE: images no container references, running OR stopped. The
# daemon makes that check itself at delete time, which is why this is
# `docker image prune` rather than a list-then-`docker rmi` loop: a list goes
# stale the moment compose creates a container from an image on it, and the
# `rmi -f` that a multi-tagged image needs would then untag an image that has just
# come into use. Never containers (a stopped datastore Docker gave up restarting
# is exactly what the disk-full runbook `docker start`s), never volumes, never
# networks, never build cache (nothing builds on the Pi - the prod compose file
# has no `build:` - so there is none to reclaim).
#
# THE KEEP WINDOW: `until` filters on the image's CREATED timestamp - when it was
# BUILT - not when it was pulled or last run. 72h keeps every image built in the
# last three days, which covers a Friday deploy found broken on Monday, so a manual
# rollback can re-tag a local image instead of re-pulling. Images are mostly shared
# layers, so a few days of deploys costs little beyond the unique top layers.
#
# The backend image used to defeat this: Spring Boot's bootBuildImage stamps a
# fixed 1980-01-01 creation date unless `createdDate` is set, so every unused
# backend image looked 46 years old and no window could keep one. It is now set in
# backend/build.gradle.kts. Backend images already on the host still carry 1980
# and are removed as soon as nothing uses them - including the previous version
# after a deploy, which is why the rollback ordering below matters.
#
# WHAT THIS DOES NOT PROTECT: the pre-deploy image a rollback in the CURRENT deploy
# re-tags (`rollback-images`). It is unused from `recreate` onwards, so a prune
# at the wrong moment deletes it and the rollback fails. That is prevented by the
# callers, not here: restart-prod.sh prunes only after the deploy has verified and
# never after a rollback, and monitor-prod.sh stands down while a deploy is running
# and for DISK_PRUNE_DEPLOY_GRACE after its pull.

IMAGE_PRUNE_KEEP="${IMAGE_PRUNE_KEEP:-72h}"
# Bounded because a phase that outlives factory.deploy.phase-timeout (30m) FAILS,
# and a failed verify-public enters the rollback path - a slow prune must never be
# able to roll back a deploy that succeeded. The daemon rejects a second concurrent
# prune ("a prune operation is already running"), which is just another failure
# the callers already tolerate.
IMAGE_PRUNE_TIMEOUT="${IMAGE_PRUNE_TIMEOUT:-600}"

# prune_unused_images [keep]
#
#   keep  a docker duration for the `until` filter (default IMAGE_PRUNE_KEEP), or
#         `all` to remove every unused image regardless of age.
#
# Prints a short summary rather than docker's one `deleted:` line per layer (the
# first run on the Pi would print thousands, into a phase output the deploy
# record keeps). Returns docker's exit status; every caller treats it as
# best-effort.
prune_unused_images() {
  local keep="${1:-$IMAGE_PRUNE_KEEP}"
  local cmd=(docker image prune -af)
  if [[ "$keep" != "all" ]]; then
    cmd+=(--filter "until=${keep}")
  fi
  if command -v timeout >/dev/null 2>&1; then
    cmd=(timeout "$IMAGE_PRUNE_TIMEOUT" "${cmd[@]}")
  fi

  local output rc=0
  output="$(run_cmd "${cmd[@]}" 2>&1)" || rc=$?
  # Field comparisons, not a regex: this reads docker's output, which is unbounded.
  printf '%s\n' "$output" |
    awk '$1 != "deleted:" && $1 != "untagged:" && $0 != "Deleted Images:" && NF > 0'
  return "$rc"
}
