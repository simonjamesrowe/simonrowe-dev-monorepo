#!/usr/bin/env bash
#
# Production deploy. One script, two callers.
#
#   ./scripts/restart-prod.sh                 the human path - unchanged behaviour
#   ./scripts/restart-prod.sh <phase> [sha]   one retryable unit, for the deployer
#
# Bare invocation runs pull -> recreate -> verify -> verify-public with no flag
# file present, which is exactly what this script has always done. It does NOT
# run sync-config: a human typing this after their own `git pull` must not have
# the script decide to move HEAD for them. Config sync is opt-in, and only the
# deployer opts in.
#
# EVERY PHASE MUST BE SAFE TO RE-RUN. Temporal retries activities, so a second
# identical invocation has to be a no-op or reach the same state. The one place
# this needs active care is `pull` - see the truncate note there.
#
# Exit codes:
#   0   the phase succeeded
#   1   the phase failed (verify/verify-public failing enters the rollback path)
#   2   the phase declined, with no side effects (sync-config only)
#  64   usage error
#
# TESTING: set DRY_RUN=1. Every mutating docker/git command goes through run_cmd,
# which echoes instead of executing. Without it, merely running this script
# performs real restarts and can recreate containers if the compose file has been
# edited since the last deploy. The same warning applies to monitor-prod.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_DIR/docker-compose.prod.yml}"

# How long to wait for every container to settle before declaring the deploy bad.
# Elasticsearch alone needs ~130s to bind 9200 on the Pi and the backend another
# ~90s after that, so this has to be generous or we report a failure that isn't one.
VERIFY_TIMEOUT="${VERIFY_TIMEOUT:-420}"
VERIFY_POLL="${VERIFY_POLL:-10}"

# Services whose images `pull` fetches and `recreate` replaces. The three images
# CI publishes on every merge to main.
#
# `deployer` is deliberately absent and must never be added: recreating the
# container that is mid-orchestration is how the backend's old redeploy path went
# wrong, and it needed an ephemeral helper container to work around it. The
# deployer excludes itself and is updated by hand.
SERVICES="${SERVICES:-backend frontend software-factory}"

# The tag `pull` fetches and then re-tags to :latest. The deployer passes the head
# sha so an exact commit is deployed rather than whatever :latest points at when
# the pull runs.
IMAGE_TAG="${IMAGE_TAG:-latest}"

# SERVICES may only name services whose image is ${IMAGE_PREFIX}<service>. That
# holds for exactly the three images CI publishes, which is the whole default set.
IMAGE_PREFIX="${IMAGE_PREFIX:-ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-}"

# Shared with nginx (read-only there) as the `deploy-state` volume. Holds the
# maintenance flag and the recorded rollback image ids.
STATE_DIR="${STATE_DIR:-/var/run/deploy-state}"
MAINTENANCE_FLAG="$STATE_DIR/maintenance.on"
ROLLBACK_FILE="$STATE_DIR/rollback-images"

# Never behind the maintenance flag: these are how a failing deploy gets fixed,
# so `verify` can check them while the page is up.
OPS_HOSTS=(
  console.simonrowe.dev
  langfuse.simonrowe.dev
  temporal.simonrowe.dev
  dependency-track.simonrowe.dev
)

# Behind the flag. Checked only by verify-public, after the page comes down - the
# maintenance page returns 503 by design and the check below treats 503 as a
# failure, correctly, so running it with the flag set would fail every deploy.
PUBLIC_HOSTS=(
  www.simonrowe.dev
  api.simonrowe.dev
)

# ---------------------------------------------------------------------------
# Plumbing
# ---------------------------------------------------------------------------

# Same name and shape as monitor-prod.sh's, so the two scripts read alike.
run_cmd() {
  if [[ -n "${DRY_RUN:-}" ]]; then
    echo "DRY-RUN: $*"
    return 0
  fi
  "$@"
}

compose() {
  run_cmd docker compose -f "$COMPOSE_FILE" "$@"
}

image_for() {
  echo "${IMAGE_PREFIX}$1"
}

# Read-only, so it does not go through run_cmd - but it still needs a DRY_RUN
# answer, because a test has no local images to inspect and the recorded value is
# what the rollback assertions read.
image_id() {
  local image="$1"
  if [[ -n "${DRY_RUN:-}" ]]; then
    echo "dry-run-image-id:$image"
    return 0
  fi
  docker image inspect --format '{{.Id}}' "$image" 2>/dev/null || true
}

# Any 2xx/3xx/4xx means nginx reached its upstream. 502/504 (or no response at
# all) means the upstream is down; 503 means the maintenance page, which is a
# failure everywhere this is called from because verify-public runs with the flag
# already cleared.
http_code() {
  local host="$1"
  if [[ -n "${DRY_RUN:-}" ]]; then
    echo "${DRY_RUN_HTTP_CODE:-200}"
    return 0
  fi
  curl -s -o /dev/null -w '%{http_code}' --max-time 20 "https://${host}/" || echo 000
}

check_hosts() {
  local failed=0 host code
  for host in "$@"; do
    code="$(http_code "$host")"
    case "$code" in
      000 | 502 | 503 | 504)
        printf '  %-34s %s  <-- FAILED\n' "$host" "$code"
        failed=1
        ;;
      *)
        printf '  %-34s %s\n' "$host" "$code"
        ;;
    esac
  done
  return "$failed"
}

# Containers that are neither healthy nor a cleanly-finished one-shot. `created`
# is the important one to catch: it means the container was built for this deploy
# and then never started because a dependency gate failed.
#
# jq rather than the inline python3 this used to use, so the deployer image needs
# only bash, curl and jq. The four cases below are exactly the ones the python
# covered and must stay covered:
#   - a container WITH a healthcheck whose health is not "healthy"
#   - an "exited" container with a non-zero exit code (a clean one-shot is fine)
#   - a container that is neither running nor exited  <- this is `created`
#   - a line that is not valid JSON, skipped rather than fatal
#
# PS_FIXTURE lets a test feed the parser known lines instead of a live Docker.
#
# Emits one JSON object per line whichever shape compose used. Compose has printed
# both: older versions emit JSON Lines, newer ones a single JSON array. The old
# python parser only ever handled the line-per-container shape, so an upgrade of
# the compose plugin on the Pi would have silently made every container look
# settled - a deploy that verifies nothing and reports success.
ps_json_lines() {
  local raw
  if [[ -n "${PS_FIXTURE:-}" ]]; then
    raw="$(cat -- "$PS_FIXTURE")"
  elif [[ -n "${DRY_RUN:-}" ]]; then
    # No fixture and no real stack.
    return 0
  else
    raw="$(docker compose -f "$COMPOSE_FILE" ps -a --format json 2>/dev/null || true)"
  fi

  if [[ "$(printf '%s' "$raw" | tr -d '[:space:]' | cut -c1)" == "[" ]]; then
    printf '%s' "$raw" | jq -c '.[]' 2>/dev/null || true
  else
    printf '%s\n' "$raw"
  fi
}

unsettled_containers() {
  # --raw-input plus fromjson? is what makes a malformed line skipped rather than
  # fatal: fromjson? emits nothing on a parse error instead of aborting jq. That
  # matters because docker occasionally prefixes warnings to its output.
  # `empty` on the no-match branches keeps settled containers out.
  ps_json_lines |
    jq -r --raw-input '
      (fromjson? // empty) as $c
      | ($c.Name // "?") as $name
      | ($c.State // "") as $state
      | (($c.Health // "") | tostring) as $health
      | (($c.ExitCode // 0) | tonumber) as $exit
      | if ($health | length) > 0 then
          if $health != "healthy" then [$name, $state, $health] else empty end
        elif $state == "exited" then
          if $exit != 0 then [$name, $state, "exit=\($exit)"] else empty end
        elif $state != "running" then
          [$name, $state, "-"]
        else
          empty
        end
      | @tsv
    '
}

settle() {
  echo
  echo "Waiting up to ${VERIFY_TIMEOUT}s for all containers to settle..."
  local elapsed=0 pending count
  pending="$(unsettled_containers)"
  while [[ -n "$pending" && "$elapsed" -lt "$VERIFY_TIMEOUT" ]]; do
    count="$(printf '%s\n' "$pending" | grep -c .)"
    echo "  [${elapsed}s/${VERIFY_TIMEOUT}s] ${count} container(s) not settled yet"
    sleep "$VERIFY_POLL"
    elapsed=$((elapsed + VERIFY_POLL))
    pending="$(unsettled_containers)"
  done

  if [[ -n "$pending" ]]; then
    echo
    echo "The following containers did not reach a healthy state:"
    printf '%s\n' "$pending" | while IFS=$'\t' read -r name state health; do
      printf '  %-52s %-10s %s\n' "$name" "$state" "$health"
    done
    if printf '%s\n' "$pending" | grep -q 'created'; then
      echo
      echo "  A container in 'created' was built for this deploy but never started,"
      echo "  because a service it depends_on never went healthy in time. Check that"
      echo "  dependency's healthcheck start_period, then re-run this script."
    fi
    return 1
  fi

  echo "All containers settled."
  return 0
}

# ---------------------------------------------------------------------------
# Phases
# ---------------------------------------------------------------------------

# The flag file is this deploy's own bookkeeping inside STATE_DIR, not a change to
# the running stack, so it is written for real even under DRY_RUN - the tests
# assert on it, and DRY_RUN exists to stop docker commands, not file writes to a
# state directory the caller chose. Both directions are idempotent (`touch`,
# `rm -f`), which they have to be because Temporal retries activities.
phase_maintenance_on() {
  mkdir -p "$STATE_DIR"
  touch "$MAINTENANCE_FLAG"
  echo "Maintenance page ON ($MAINTENANCE_FLAG)"
}

phase_maintenance_off() {
  rm -f "$MAINTENANCE_FLAG"
  echo "Maintenance page OFF"
}

# Records the current image id of each target service, then pulls IMAGE_TAG and
# re-tags it to :latest.
#
# Re-tagging rather than compose-level image indirection (${BACKEND_IMAGE:-...})
# is deliberate. monitor-prod.sh runs a bare `docker compose up -d` every minute
# whenever it sees anything unsettled, and that command resolves :latest with no
# variable passed - so with env indirection a rollback would be undone by the
# watchdog inside 60 seconds. Re-tagging :latest locally is what every other
# command on the box already resolves.
phase_pull() {
  mkdir -p "$STATE_DIR"

  # TRUNCATE, never append. Temporal retries activities: if this appended, a
  # retried `pull` would record the image it had just pulled as the rollback
  # target, and the rollback would then restore the broken version. Truncating
  # means a retry records the same pre-deploy state as the first attempt.
  #
  # Written for real even under DRY_RUN: it is the deploy's own bookkeeping in
  # STATE_DIR, not a change to the running stack, and the rollback assertions read
  # it back.
  : >"$ROLLBACK_FILE"

  local service image id
  for service in $SERVICES; do
    image="$(image_for "$service")"
    id="$(image_id "${image}:latest")"
    if [[ -n "$id" ]]; then
      printf '%s\t%s\n' "$service" "$id" >>"$ROLLBACK_FILE"
    else
      # Not an error: a service with no local image yet simply is not
      # rollback-able. Saying so beats a silent gap in the rollback file.
      echo "  no local image for ${image}:latest - not rollback-able"
    fi
    run_cmd docker pull "${image}:${IMAGE_TAG}"
    if [[ "$IMAGE_TAG" != "latest" ]]; then
      run_cmd docker tag "${image}:${IMAGE_TAG}" "${image}:latest"
    fi
  done
}

# --no-deps stops recreating a service from restarting the database underneath it.
# --pull never is what makes the local re-tag authoritative for this invocation.
#
# The full `up -d` reconcile afterwards evaluates the compose file as it is on
# disk and can therefore recreate anything - which is safe only because
# sync-config moves HEAD only when every affected service is allowlisted. The two
# are a pair; neither is safe without the other.
phase_recreate() {
  local service
  for service in $SERVICES; do
    compose up -d --no-deps --pull never "$service"
  done

  # nginx resolves container names at request time through Docker DNS, but it
  # still needs a reload when the mounted config gains a new route. Restarting
  # after reconciliation covers that and remains safe because nginx no longer
  # requires every upstream to resolve at startup.
  #
  # nginx is also what serves the maintenance page, so for a second or two here
  # there is nothing serving at all. Accepted: unavoidable while the page lives in
  # the same nginx that proxies the site, and a couple of seconds inside an
  # already-degraded window is not worth a second proxy container.
  echo "Restarting nginx to load the current proxy configuration..."
  compose restart nginx

  reconcile
}

# Deliberately not fatal. A dependency gate that expires (for example
# "dependency failed to start: container ... elasticsearch-1 is unhealthy") makes
# `up -d` exit non-zero *having already created* the services behind that gate, so
# they sit in Docker `created` and never start. Under `set -e` that aborted the
# script, skipping every check below - which is how a deploy could leave a 502 on
# www while the operator saw only a wall of "Container ... Running" lines. Record
# the failure and press on to the verification, which explains what is actually
# broken.
# Service names for the reconcile, deployer excluded, one per line.
#
# Two sources, because the comment below is a promise the single-source version did
# not keep: "enumerating must not be the step that fails". `docker compose config`
# is authoritative but needs a working docker, which the shell tests and CI do not
# have — there it returned nothing, the reconcile skipped itself, and the deploy
# quietly stopped reconciling anything.
#
# The fallback reads the top-level keys of the `services:` block straight out of the
# compose file: two-space-indented, non-comment, ends in a colon. That is enough to
# preserve the property that actually matters — an explicit list that never contains
# `deployer`, and never a bare `up -d`.
enumerate_services() {
  local names
  names="$(docker compose -f "$COMPOSE_FILE" config --no-interpolate --services 2>/dev/null || true)"

  if [[ -z "$names" ]]; then
    names="$(
      awk '
        /^services:/ { in_services = 1; next }
        /^[a-zA-Z_-]+:/ { in_services = 0 }
        in_services && /^  [a-zA-Z0-9._-]+:[[:space:]]*$/ {
          gsub(/^  |:[[:space:]]*$/, "")
          print
        }
      ' "$COMPOSE_FILE" 2>/dev/null || true
    )"
  fi

  printf '%s\n' "$names" | grep -vx deployer | grep -v '^$' | sort
}

reconcile() {
  echo "Reconciling production services..."
  local rc=0

  # EVERY SERVICE EXCEPT THE DEPLOYER, never a bare `up -d`.
  #
  # This runs *inside* the deployer, so a bare `up -d` that decides the deployer
  # needs recreating SIGTERMs the container executing this very script. What
  # follows is not a clean failure: the replacement is left in `created` because
  # the process that would have started it has just been killed, and the deploy
  # workflow sits with no worker until the activity heartbeat times out.
  #
  # It is not a rare edge case. `deployer` and `software-factory` share one image
  # reference, ${FACTORY_IMAGE} = software-factory:latest, and the `pull` phase a
  # few steps earlier RE-TAGS :latest to the newly pulled image. The running
  # deployer was created from the old :latest, so by the time this function runs
  # compose sees an image change and wants to recreate it - on EVERY deploy where
  # the factory image changed, whether or not the deployer's own service
  # definition was touched. Observed twice in production.
  #
  # `deployer` is already absent from FACTORY_DEPLOY_SERVICES and
  # FACTORY_DEPLOY_RECREATABLE; a bare `up -d` ignored both, so that exclusion was
  # incomplete. The deployer is updated by hand, by design - see
  # docs/runbooks/deploy.md "Keeping the deployer current".
  #
  # Listing services explicitly keeps the reconcile's real purpose intact: it
  # still starts anything stuck in `created` and applies any compose change to
  # everything else.
  #
  # --no-interpolate: this only needs the service NAMES, and asking compose to
  # interpolate would make the list depend on a fully-populated .env. Enumerating
  # must not be the step that fails.
  # NOT `mapfile`/`readarray`: those are bash 4 builtins and macOS ships bash 3.2,
  # where this function died with "mapfile: command not found" — taking the rest of
  # the `all` phase with it, so the script could not be tested on a Mac at all.
  # A read loop is portable and does the same job.
  local targets=()
  local service
  while IFS= read -r service; do
    [[ -n "$service" ]] && targets+=("$service")
  done < <(enumerate_services)

  if [[ "${#targets[@]}" -eq 0 ]]; then
    echo "WARNING: could not enumerate services; skipping reconcile rather than"
    echo "         running a bare 'up -d' that could recreate this container."
    return 0
  fi
  compose up -d "${targets[@]}" || rc=$?
  if [[ "$rc" -ne 0 ]]; then
    echo
    echo "WARNING: 'docker compose up -d' exited ${rc}. Continuing to verification"
    echo "         so the actual state of the stack gets reported."
  fi
}

# What can be checked while the maintenance page is up: the container settle loop
# (which is where nearly all the waiting happens, and which relies on each
# container's own healthcheck rather than on nginx) plus the four ops hostnames,
# which are never behind the flag.
phase_verify() {
  local failed=0
  settle || failed=1

  echo
  echo "Checking operations hostnames..."
  check_hosts "${OPS_HOSTS[@]}" || failed=1

  return "$failed"
}

phase_verify_public() {
  # A green 'docker compose ps' is not proof the site serves - on 2026-08-14 two
  # containers reported healthy while serving errors for 10 days. Always curl the
  # public hostnames.
  echo
  echo "Checking public hostnames..."
  check_hosts "${PUBLIC_HOSTS[@]}"
}

# ---------------------------------------------------------------------------
# Configuration sync
#
# docker-compose.prod.yml, config/nginx/*, frontend/nginx.conf and scripts/* are
# read from the deploy directory on the host, not from any image. A deploy that
# only shipped images would therefore deploy a merge touching those files HALF
# way - silently, while reporting success. So this fast-forwards the deploy
# directory as part of the deploy.
#
# This is the one part of the whole feature that mutates the host, so it is
# fenced. Every check below abandons the phase with NO side effects.
#
# BARE `restart-prod.sh` NEVER RUNS THIS. A human typing the script after their
# own `git pull` must not have it decide to move HEAD for them; before this
# feature the script touched git not at all, and that stays true for the default
# path. It is opt-in, and only the deployer opts in.
#
# Machine-readable output is key=value lines on stdout; human narration goes to
# stderr. See specs/036-auto-deploy-on-merge/contracts/restart-prod-phases.md.
# ---------------------------------------------------------------------------

# Services the automation is allowed to recreate. An allowlist, not a denylist:
# a service added to the compose file next year is held for a human by default
# rather than silently recreated by the first deploy that touches it.
RECREATABLE="${RECREATABLE:-backend frontend software-factory nginx alloy searxng temporal-ui dependencytrack-frontend}"

# Pinned in configuration rather than read from the checkout's own remote, so a
# tampered remote cannot redirect the fetch this phase validates against. The
# repository is public, so this needs no credential and no push access.
REPO_URL="${REPO_URL:-https://github.com/simonjamesrowe/simonrowe-dev-monorepo.git}"

git_in_repo() {
  git -C "$PROJECT_DIR" "$@"
}

# Per-service configuration hashes for a compose file, as `service<TAB>hash`.
# Parsed tolerantly - first and last whitespace-separated field - rather than
# assuming a tab, because the exact separator of `config --hash` is not a
# documented contract.
service_hashes() {
  local file="$1"
  # --project-directory, for the same reason as the config -q calls in
  # phase_sync_config: "$file" is a mktemp copy, and without this compose would
  # take /tmp as the project directory and find no .env. Here the failure is
  # SILENT - stderr is discarded and an empty hash list reads as "no service
  # changed", so a held-back service would sail through the allowlist check.
  docker compose --project-directory "$PROJECT_DIR" -f "$file" config --hash='*' 2>/dev/null |
    awk 'NF >= 2 { print $1 "\t" $NF }' | sort
}

# The services a change to the compose file would affect: every service whose
# configuration hash differs, plus every service present in one file and not the
# other.
affected_services() {
  local current="$1" candidate="$2"
  local current_hashes candidate_hashes
  current_hashes="$(service_hashes "$current")"
  candidate_hashes="$(service_hashes "$candidate")"
  # comm on the joined lines, then take the service name: a service appears here
  # if its whole line differs, which covers a changed hash, an added service and
  # a removed one.
  printf '%s\n%s\n' "$current_hashes" "$candidate_hashes" |
    sort | uniq -u | cut -f1 | sort -u | grep -v '^$' || true
}

phase_sync_config() {
  local target="${1:-}"
  if [[ -z "$target" ]]; then
    echo "sync-config needs a target commit" >&2
    return 64
  fi

  # 1. Record the current commit FIRST, before any check can abandon the phase.
  #    It is the rollback target, and the caller needs it even on a decline.
  local previous
  if ! previous="$(git_in_repo rev-parse HEAD 2>/dev/null)"; then
    echo "decision=failed"
    echo "$PROJECT_DIR is not a git checkout" >&2
    return 1
  fi
  echo "previous-sha=$previous"

  # 2. Already there. A success and a no-op - this is what a rehearsal deploy of
  #    the version already in production reports, and it must not read as a
  #    failure.
  if [[ "$previous" == "$target" ]]; then
    echo "decision=already-current"
    echo "The deploy directory is already at $target" >&2
    return 0
  fi

  # 3. A modified tracked file means someone is working on the box. --untracked-
  #    files=no is what makes a hand-edited (and gitignored) .env, and any other
  #    untracked file, not block the sync.
  local dirty
  dirty="$(git_in_repo status --porcelain --untracked-files=no)"
  if [[ -n "$dirty" ]]; then
    echo "decision=dirty-tree"
    echo "Tracked files are modified in $PROJECT_DIR; not moving HEAD:" >&2
    printf '%s\n' "$dirty" >&2
    return 2
  fi

  # 4. Anonymous, read-only, from the pinned URL.
  if ! run_cmd git -C "$PROJECT_DIR" fetch --no-tags "$REPO_URL" main; then
    echo "decision=failed"
    echo "Could not fetch main from $REPO_URL" >&2
    return 1
  fi

  # 5. THE assertion that bounds this whole capability: the working tree can only
  #    ever move to a commit that is genuinely on origin/main.
  if [[ -z "${DRY_RUN:-}" ]] && ! git_in_repo merge-base --is-ancestor "$target" FETCH_HEAD; then
    echo "decision=not-an-ancestor"
    echo "$target is not an ancestor of the fetched main; not moving HEAD" >&2
    return 2
  fi

  # 6. Decide which services the change affects WITHOUT moving HEAD.
  local candidate current_copy
  candidate="$(mktemp)"
  current_copy="$(mktemp)"
  # shellcheck disable=SC2064
  trap "rm -f '$candidate' '$current_copy'" RETURN

  if ! git_in_repo show "$target:docker-compose.prod.yml" >"$candidate" 2>/dev/null; then
    echo "decision=failed"
    echo "Could not read docker-compose.prod.yml at $target" >&2
    return 1
  fi
  cp "$COMPOSE_FILE" "$current_copy"

  # `docker compose config` interpolates .env, so a compose file referencing a
  # variable the host does not define makes this command FAIL rather than warn -
  # and that failure is exactly the signal we want. Declining here is far better
  # than discovering it after HEAD moved, at which point every subsequent
  # `docker compose` command on the box is broken.
  #
  # --project-directory IS LOAD-BEARING. Both files here are mktemp copies, and
  # compose derives the project directory - and therefore where it looks for
  # .env - from the compose file's own location. Without this, compose reads
  # /tmp/.env, finds nothing, and EVERY `${VAR:?}` in the file fails as "required
  # variable ... is missing a value". That is indistinguishable from the genuine
  # missing-variable case this check exists to catch, so sync-config declined
  # every single merge while reporting a variable that was present in .env all
  # along. Observed on three consecutive merges before it was found.
  local candidate_config_error
  if ! candidate_config_error="$(docker compose --project-directory "$PROJECT_DIR" -f "$candidate" config -q 2>&1)"; then
    if docker compose --project-directory "$PROJECT_DIR" -f "$current_copy" config -q >/dev/null 2>&1; then
      local missing
      # Best effort only. The decline does not depend on parsing the name.
      missing="$(printf '%s' "$candidate_config_error" |
        grep -oE 'required variable [A-Z_0-9]+' | head -1 | awk '{print $NF}')"
      echo "decision=missing-variable"
      [[ -n "$missing" ]] && echo "missing-variable=$missing"
      echo "The compose file at $target needs a variable this host's .env does not define" >&2
      printf '%s\n' "$candidate_config_error" >&2
      return 2
    fi
    # The CURRENT file does not render either, so this is a pre-existing problem
    # on the host rather than something the new commit introduced. Say so, and
    # do not blame the commit.
    echo "decision=failed"
    echo "The compose file already on this host does not render; fix that first" >&2
    printf '%s\n' "$candidate_config_error" >&2
    return 1
  fi

  local affected held_back=""
  affected="$(affected_services "$current_copy" "$candidate")"
  local service
  for service in $affected; do
    if ! printf '%s\n' $RECREATABLE | grep -qx "$service"; then
      held_back="${held_back:+$held_back }$service"
    fi
  done

  echo "affected=$(printf '%s' "$affected" | tr '\n' ' ' | sed 's/ *$//')"

  # 7. Anything outside the allowlist and the fast-forward does not happen AT ALL.
  #
  #    Deciding first and moving second is the whole point. Fast-forwarding and
  #    then declining to recreate would leave the deploy directory ahead of what
  #    is running - and monitor-prod.sh's next bare `up -d` would apply the
  #    held-back change within the minute, which is precisely the surprise this
  #    is meant to prevent.
  #
  #    It is also what keeps the `recreate` phase's full `up -d` reconcile safe:
  #    the reconcile evaluates the compose file as it is on disk and can recreate
  #    anything, but HEAD only ever moves when every affected service is
  #    allowlisted, so by then there is nothing outside the allowlist to change.
  if [[ -n "$held_back" ]]; then
    echo "decision=held-back"
    echo "held-back=$held_back"
    echo "manual-command=docker compose -f docker-compose.prod.yml up -d $held_back"
    echo "Held back for a human: $held_back" >&2
    return 2
  fi

  # 8. Fast-forward ONLY, and to the deployed commit rather than the tip of main:
  #    `git pull` would take whatever main points at now, which may be a newer
  #    commit whose images do not exist yet. Config and images have to come from
  #    the same commit or the deploy is a mix of two.
  if ! run_cmd git -C "$PROJECT_DIR" merge --ff-only "$target"; then
    echo "decision=failed"
    echo "Fast-forward to $target failed" >&2
    return 1
  fi

  echo "decision=applied"
  echo "Deploy directory fast-forwarded from $previous to $target" >&2
  return 0
}

# Restores the deploy directory to the commit sync-config recorded.
#
# `reset --hard` and not `merge --ff-only`, because the recorded commit is an
# ANCESTOR of the current HEAD and a fast-forward cannot go backwards. Safe
# precisely because sync-config's clean-tree check ran before anything moved, so
# there is nothing here to lose.
#
# Because this restores the previous commit, everything after it runs the
# PREVIOUS version of this script - which is what matters when the thing that
# broke the deploy was a change to the script itself.
phase_rollback_config() {
  local target="${1:-}"
  if [[ -z "$target" ]]; then
    echo "rollback-config needs a target commit" >&2
    return 64
  fi
  run_cmd git -C "$PROJECT_DIR" reset --hard "$target"
}

# ---------------------------------------------------------------------------
# Evidence gathering for the failure path. Read-only, and best-effort: it runs
# because something is already broken, so a command that fails here must not
# take the diagnosis down with it.
# ---------------------------------------------------------------------------

phase_compose_ps() {
  docker compose -f "$COMPOSE_FILE" ps -a 2>&1 || true
}

phase_container_logs() {
  local pending names
  pending="$(unsettled_containers)"
  if [[ -z "$pending" ]]; then
    echo "Every container was settled at the time of capture."
    return 0
  fi
  names="$(printf '%s\n' "$pending" | cut -f1)"
  local name
  for name in $names; do
    echo "===== $name"
    docker logs --tail 200 "$name" 2>&1 || echo "(could not read logs for $name)"
    echo
  done
}

phase_commit_range() {
  local from="${1:-}" to="${2:-HEAD}"
  if [[ -z "$from" ]]; then
    echo "No previous commit is known, so no commit range is available."
    return 0
  fi
  git_in_repo log --oneline "$from..$to" 2>&1 ||
    echo "Could not read the commit range $from..$to"
}

# Re-tag :latest back to the image ids recorded before the pull, then recreate
# with --pull never. Same mechanism as the deploy, in the other direction.
phase_rollback() {
  if [[ ! -s "$ROLLBACK_FILE" ]]; then
    echo "No recorded rollback images at $ROLLBACK_FILE - nothing to roll back." >&2
    return 1
  fi

  local service id image
  while IFS=$'\t' read -r service id; do
    [[ -z "$service" || -z "$id" ]] && continue
    image="$(image_for "$service")"
    echo "Rolling ${service} back to ${id}"
    run_cmd docker tag "$id" "${image}:latest"
    compose up -d --no-deps --pull never "$service"
  done <"$ROLLBACK_FILE"

  echo "Restarting nginx to load the current proxy configuration..."
  compose restart nginx
  reconcile
}

# The full sequence, exactly as this script has always behaved: whole-file pull,
# whole-stack up -d, restart nginx, settle, then all six hostnames in one list.
# Deliberately NOT the per-service --no-deps form the recreate phase uses, and
# deliberately no sync-config and no maintenance flag.
phase_all() {
  local failed=0

  echo "Pulling latest production images..."
  compose pull

  reconcile

  echo "Restarting nginx to load the current proxy configuration..."
  compose restart nginx

  settle || failed=1

  echo
  echo "Checking public hostnames..."
  check_hosts "${PUBLIC_HOSTS[@]}" "${OPS_HOSTS[@]}" || failed=1

  echo
  if [[ "$failed" -ne 0 ]]; then
    echo "Production refresh INCOMPLETE - see the failures above."
    return 1
  fi

  echo "Production services refreshed and verified."
  return 0
}

usage() {
  cat >&2 <<'EOF'
Usage: restart-prod.sh [PHASE] [TARGET_SHA]

Phases:
  all (default)      pull -> recreate -> verify -> verify-public, as today
                     (deliberately NOT sync-config: bare invocation never moves HEAD)
  sync-config <sha>  fast-forward the deploy directory to <sha>, if that is safe
  rollback-config <sha>
                     reset the deploy directory back to <sha>
  maintenance-on     create the maintenance flag file
  maintenance-off    remove the maintenance flag file
  pull               record current image ids, pull IMAGE_TAG, re-tag to :latest
  recreate           up -d --no-deps --pull never each SERVICE, restart nginx, reconcile
  verify             container settle loop + the four ops hostnames
  verify-public      www + api (run only after maintenance-off)
  rollback           re-tag :latest back to the recorded image ids and recreate

Read-only evidence gathering, for the failure path:
  compose-ps         `docker compose ps -a`
  container-logs     recent logs of every unsettled container
  commit-range <from> [to]
                     `git log --oneline <from>..<to>`

See specs/036-auto-deploy-on-merge/contracts/restart-prod-phases.md.
EOF
  exit 64
}

main() {
  local phase="${1:-all}"

  case "$phase" in
    all) phase_all ;;
    sync-config) phase_sync_config "${2:-}" ;;
    rollback-config) phase_rollback_config "${2:-}" ;;
    compose-ps) phase_compose_ps ;;
    container-logs) phase_container_logs ;;
    commit-range) phase_commit_range "${2:-}" "${3:-HEAD}" ;;
    maintenance-on) phase_maintenance_on ;;
    maintenance-off) phase_maintenance_off ;;
    pull) phase_pull ;;
    recreate) phase_recreate ;;
    verify) phase_verify ;;
    verify-public) phase_verify_public ;;
    rollback) phase_rollback ;;
    -h | --help) usage ;;
    *)
      echo "Unknown phase: $phase" >&2
      usage
      ;;
  esac
}

main "$@"
