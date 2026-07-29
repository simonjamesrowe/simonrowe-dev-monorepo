#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$PROJECT_DIR/docker-compose.prod.yml"
ENV_FILE="$PROJECT_DIR/.env"

REVIEWER_UNIT=temporal-reviewer-worker.service
REVIEWER_INSTALL_DIR=/opt/temporal-reviewer

warn() { echo "  WARNING: $*" >&2; }

# Reads a key from .env without sourcing it, so a stray shell metacharacter in a
# secret cannot execute anything here.
env_value() {
  [[ -f "$ENV_FILE" ]] || return 0
  sed -n "s/^$1=//p" "$ENV_FILE" | tail -n1
}

# The reviewer worker runs on the host, not in Compose, so `docker compose up` cannot
# reconcile it. Without this it silently keeps running an older jar than the image the
# API container was just upgraded to — the API/worker drift the runbook warns about.
ensure_reviewer_worker() {
  local image installed_image claude_command
  image="$(env_value REVIEWER_IMAGE)"
  if [[ -z "$image" ]]; then
    warn "REVIEWER_IMAGE is not set in .env; skipping the reviewer worker."
    return 0
  fi

  # Every step below needs root. Fail soft rather than hanging on a password prompt
  # when this runs unattended.
  if ! sudo -n true 2>/dev/null; then
    warn "no non-interactive sudo; skipping the reviewer worker."
    warn "run: sudo REVIEWER_IMAGE=\"$image\" $SCRIPT_DIR/install-reviewer-worker.sh"
    return 0
  fi

  # Deliberately not auto-installed: install-reviewer-host-deps.sh downloads a ~270MB
  # Claude binary and installs distribution packages, which is not something a restart
  # should do behind your back.
  claude_command="$(env_value CLAUDE_COMMAND)"
  claude_command="${claude_command:-/usr/local/bin/claude}"
  if [[ ! -x /usr/bin/java || ! -x "$claude_command" ]]; then
    warn "host prerequisites missing (need /usr/bin/java and $claude_command)."
    warn "run: sudo $SCRIPT_DIR/install-reviewer-host-deps.sh"
    return 0
  fi

  installed_image="$(sudo cat "$REVIEWER_INSTALL_DIR/installed-image" 2>/dev/null || true)"
  if [[ ! -f "/etc/systemd/system/$REVIEWER_UNIT" || "$installed_image" != "$image" ]]; then
    echo "Installing the reviewer worker from $image..."
    if ! (cd "$PROJECT_DIR" && sudo REVIEWER_IMAGE="$image" "$SCRIPT_DIR/install-reviewer-worker.sh"); then
      warn "reviewer worker install failed; containers are unaffected."
      return 0
    fi
  else
    echo "Reviewer worker already matches $image."
  fi

  # Starting without credentials just crash-loops the unit, so check first and leave a
  # clear instruction instead.
  local missing=()
  [[ -n "$(env_value GITHUB_APP_CLIENT_ID)" ]] || missing+=(GITHUB_APP_CLIENT_ID)
  if [[ -z "$(env_value CLAUDE_CODE_OAUTH_TOKEN)" && -z "$(env_value ANTHROPIC_API_KEY)" ]]; then
    missing+=("CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_API_KEY")
  fi
  local pem
  pem="$(env_value GITHUB_APP_PRIVATE_KEY_PATH)"
  pem="${pem:-$REVIEWER_INSTALL_DIR/github-app-private-key.pem}"
  sudo test -r "$pem" || missing+=("a readable private key at $pem")

  if ((${#missing[@]} > 0)); then
    warn "reviewer worker installed but not started; still needed in .env:"
    printf '    - %s\n' "${missing[@]}" >&2
    return 0
  fi

  echo "Starting the reviewer worker..."
  sudo systemctl enable --quiet --now "$REVIEWER_UNIT" || true
  sudo systemctl restart "$REVIEWER_UNIT"
  if ! sudo systemctl is-active --quiet "$REVIEWER_UNIT"; then
    warn "$REVIEWER_UNIT is not active; check: journalctl -u $REVIEWER_UNIT -n 50"
  fi
}

echo "Pulling latest production images..."
docker compose -f "$COMPOSE_FILE" pull

echo "Recreating production services..."
docker compose -f "$COMPOSE_FILE" up -d

# nginx now resolves container names at request time through Docker DNS, but it
# still needs a reload when the mounted config gains a new route (for example
# reviewer-api or temporal-ui). Restarting after reconciliation covers that case
# and remains safe because nginx no longer requires every upstream to resolve at
# startup.
echo "Restarting nginx to load the current proxy configuration..."
docker compose -f "$COMPOSE_FILE" restart nginx

echo "Reconciling host services..."
ensure_reviewer_worker

echo "Production services refreshed."
