#!/usr/bin/env bash
# One-time migration from the split reviewer (reviewer-api container + the
# temporal-reviewer-worker host service) to the single software-factory container.
#
# Safe to re-run: every step checks its own end state first. Delete this script
# once the cutover has stuck.
#
#   sudo -v && ./scripts/cutover-software-factory.sh
#
# Needs non-interactive sudo for the PEM install and the systemd steps. Run
# `sudo -v` first rather than letting it prompt halfway through.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$PROJECT_DIR/docker-compose.prod.yml"
ENV_FILE="$PROJECT_DIR/.env"

OLD_UNIT=temporal-reviewer-worker.service
OLD_CONTAINER=simonrowe-dev-monorepo-reviewer-api-1
OLD_PEM=/opt/temporal-reviewer/github-app-private-key.pem
NEW_PEM_DIR=/opt/software-factory
NEW_PEM="$NEW_PEM_DIR/github-app-private-key.pem"
# Matches the uid/gid created in Dockerfile.software-factory.
CONTAINER_UID=10003

NAMESPACE=default
TASK_QUEUE=code-review
ADMIN_TOOLS=temporalio/admin-tools:1.31.2
NETWORK=simonrowe-dev-monorepo_default

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
fail() { printf '\n\033[31mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }

env_value() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -n1; }

rollback_hint() {
  cat >&2 <<'EOF'

To roll back to the split deployment:

  git checkout main -- docker-compose.prod.yml config/nginx/nginx-proxy.conf
  docker compose -f docker-compose.prod.yml up -d reviewer-api
  docker compose -f docker-compose.prod.yml restart nginx
  sudo systemctl enable --now temporal-reviewer-worker

The old .env keys (REVIEWER_TRIGGER_TOKEN, REVIEWER_IMAGE) were left in place
precisely so that this works. A .env backup is written by this script.
EOF
}

cd "$PROJECT_DIR"

# ---------------------------------------------------------------- preflight
step "Preflight"

[[ -f "$COMPOSE_FILE" ]] || fail "no docker-compose.prod.yml in $PROJECT_DIR"
[[ -f "$ENV_FILE" ]] || fail "no .env in $PROJECT_DIR — this must run from the deploy directory"

grep -q '^  software-factory:' "$COMPOSE_FILE" \
  || fail "docker-compose.prod.yml has no software-factory service; check out the branch first"

sudo -n true 2>/dev/null || fail "no non-interactive sudo; run 'sudo -v' first"

for key in FACTORY_TRIGGER_TOKEN GITHUB_WEBHOOK_SECRET GITHUB_APP_CLIENT_ID CLAUDE_CODE_OAUTH_TOKEN; do
  [[ -n "$(env_value "$key")" ]] || fail "$key is missing or empty in .env"
done
info "required .env keys present"

# The image is the usual reason this script cannot proceed. CI only publishes
# ghcr.io/...-software-factory after the PR merges, so before then the only
# available image is one built locally.
image="$(env_value FACTORY_IMAGE)"
image="${image:-ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-software-factory:latest}"
info "image: $image"
if ! docker image inspect "$image" >/dev/null 2>&1; then
  if ! docker pull --quiet "$image" >/dev/null 2>&1; then
    cat >&2 <<EOF

Cannot obtain $image.

If the PR has not merged yet, CI has not published it. Either merge first, or
build locally and point .env at that build:

  docker build -f Dockerfile.software-factory -t software-factory:local .
  # in .env:
  FACTORY_IMAGE=software-factory:local

EOF
    fail "image unavailable"
  fi
fi
info "image available"

docker compose -f "$COMPOSE_FILE" config --quiet || fail "compose file is invalid"
info "compose config valid"

# ---------------------------------------------------------------- PEM
step "Installing the GitHub App private key for the container"

# The container runs as uid/gid 10003. The old PEM is 0640 root:temporal-reviewer,
# which that user cannot read, so every review would fail when minting an
# installation token — with an error that does not obviously point back here.
if [[ -f "$NEW_PEM" ]] && sudo test -r "$NEW_PEM"; then
  info "$NEW_PEM already present"
else
  source_pem="$(env_value GITHUB_APP_PRIVATE_KEY_PATH)"
  source_pem="${source_pem:-$OLD_PEM}"
  # If .env already points at the new location, fall back to the old one as source.
  if [[ "$source_pem" == "$NEW_PEM" ]]; then
    source_pem="$OLD_PEM"
  fi
  sudo test -f "$source_pem" || fail "no private key found at $source_pem"

  sudo mkdir -p "$NEW_PEM_DIR"
  sudo install -o root -g "$CONTAINER_UID" -m 0640 "$source_pem" "$NEW_PEM"
  info "installed $NEW_PEM (root:$CONTAINER_UID, 0640)"
fi

step "Pointing .env at the new key path"

current_path="$(env_value GITHUB_APP_PRIVATE_KEY_PATH)"
if [[ "$current_path" == "$NEW_PEM" ]]; then
  info "GITHUB_APP_PRIVATE_KEY_PATH already set to $NEW_PEM"
else
  backup="$ENV_FILE.bak-$(date +%Y%m%d-%H%M%S)"
  cp -a "$ENV_FILE" "$backup"
  info "backed up .env to $backup"
  python3 - "$ENV_FILE" "$NEW_PEM" <<'PY'
import pathlib, re, sys
path, new = pathlib.Path(sys.argv[1]), sys.argv[2]
text = path.read_text()
text, count = re.subn(r'^GITHUB_APP_PRIVATE_KEY_PATH=.*$',
                      f'GITHUB_APP_PRIVATE_KEY_PATH={new}', text, flags=re.M)
if count != 1:
    raise SystemExit(f'expected exactly one GITHUB_APP_PRIVATE_KEY_PATH line, found {count}')
path.write_text(text)
PY
  info "GITHUB_APP_PRIVATE_KEY_PATH -> $NEW_PEM"
fi

# ---------------------------------------------------------------- host service
step "Retiring the host worker"

if systemctl list-unit-files "$OLD_UNIT" >/dev/null 2>&1 \
   && systemctl cat "$OLD_UNIT" >/dev/null 2>&1; then
  sudo systemctl disable --now "$OLD_UNIT" 2>/dev/null || true
  info "$OLD_UNIT stopped and disabled"
else
  info "$OLD_UNIT not installed; nothing to stop"
fi

# ---------------------------------------------------------------- containers
step "Starting software-factory"

# reviewer-api is no longer a service in the compose file, so compose will not
# manage it any more. Remove it explicitly rather than with --remove-orphans,
# which would act on anything else unrecognised in the project at the same time.
if docker ps -a --format '{{.Names}}' | grep -qx "$OLD_CONTAINER"; then
  docker rm -f "$OLD_CONTAINER" >/dev/null
  info "removed $OLD_CONTAINER"
fi

docker compose -f "$COMPOSE_FILE" up -d software-factory
docker compose -f "$COMPOSE_FILE" restart nginx
info "nginx restarted to pick up the new upstream"

# ---------------------------------------------------------------- verify
step "Verifying"

info "waiting for the container to report healthy..."
for _ in $(seq 1 30); do
  health="$(docker inspect --format '{{.State.Health.Status}}' \
    simonrowe-dev-monorepo-software-factory-1 2>/dev/null || echo missing)"
  [[ "$health" == healthy ]] && break
  sleep 5
done
[[ "${health:-}" == healthy ]] || {
  docker compose -f "$COMPOSE_FILE" logs --tail=50 software-factory >&2
  rollback_hint
  fail "software-factory did not become healthy (last state: ${health:-unknown})"
}
info "container healthy"

# The check that actually matters. A healthy container that registered no poller
# accepts webhooks, returns 202, and reviews nothing — the exact failure this
# whole change exists to make impossible. The actuator healthcheck cannot see it.
info "checking for a live poller on the $TASK_QUEUE task queue..."
pollers=""
for _ in $(seq 1 12); do
  pollers="$(docker run --rm --network "$NETWORK" "$ADMIN_TOOLS" \
    temporal task-queue describe --address temporal:7233 \
    --namespace "$NAMESPACE" --task-queue "$TASK_QUEUE" 2>/dev/null || true)"
  if grep -q 'software-factory\|@' <<<"$(sed -n '/Pollers:/,$p' <<<"$pollers")"; then
    break
  fi
  sleep 5
done

if ! sed -n '/Pollers:/,$p' <<<"$pollers" | grep -q '@'; then
  echo "$pollers" >&2
  rollback_hint
  fail "no poller registered on $TASK_QUEUE — webhooks would be accepted but never reviewed"
fi
sed -n '/Pollers:/,$p' <<<"$pollers" | sed 's/^/    /'
info "poller registered"

info "checking the webhook rejects an unsigned delivery..."
code="$(curl -s -o /dev/null -w '%{http_code}' -m 20 -X POST \
  https://api.simonrowe.dev/webhooks/github \
  -H 'X-GitHub-Event: pull_request' -d '{"action":"opened"}' || echo 000)"
[[ "$code" == 401 ]] || fail "unsigned webhook returned $code, expected 401"
info "unsigned delivery rejected with 401"

info "checking the internal API is not routed..."
for path in /api/reviews/probe /api/reviews; do
  code="$(curl -s -o /dev/null -w '%{http_code}' -m 20 "https://api.simonrowe.dev$path" || echo 000)"
  # Should hit the backend (404/405), never the software factory.
  [[ "$code" == 404 || "$code" == 405 ]] \
    || fail "https://api.simonrowe.dev$path returned $code; nginx may be routing more than the webhook"
done
info "internal API not reachable from outside"

cat <<EOF

$(printf '\033[32mCutover complete.\033[0m')

Still worth doing by hand:

  1. A dry-run review, which is the only thing that exercises Claude auth and
     the clone path end to end (consumes subscription usage, publishes nothing):

     docker run --rm --network $NETWORK curlimages/curl -s \\
       -X POST http://software-factory:8090/api/reviews \\
       -H "X-Factory-Token: \$(grep '^FACTORY_TRIGGER_TOKEN=' .env | cut -d= -f2-)" \\
       -H 'Content-Type: application/json' \\
       -d '{"owner":"simonjamesrowe","repository":"simonrowe-dev-monorepo","pullNumber":NN,"publish":false}'

  2. Once a real review has published a comment, clean up:
       - delete REVIEWER_TRIGGER_TOKEN and REVIEWER_IMAGE from .env
       - sudo rm -rf /opt/temporal-reviewer
       - sudo rm -f /etc/systemd/system/$OLD_UNIT && sudo systemctl daemon-reload
       - delete this script
EOF
