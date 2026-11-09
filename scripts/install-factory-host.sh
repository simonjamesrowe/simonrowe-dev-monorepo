#!/usr/bin/env bash
set -euo pipefail

# Prepare an Ubuntu x86_64 machine (the Intel NUC) to run the software factory:
# docker-compose.factory.yml, project simonrowe-factory. See
# docs/runbooks/factory-host.md for what runs there and why.
#
#   scripts/install-factory-host.sh            # dry run: print the plan, change nothing
#   scripts/install-factory-host.sh --apply    # do it
#
# Idempotent: every step checks before it acts, so re-running on a configured host
# changes nothing (or repairs what drifted). Run it as the login user that will own
# the checkout and the cron jobs; it uses sudo for what needs root.
#
# Steps, in order:
#   1. platform      x86_64 Ubuntu, or refuse
#   2. packages      Docker Engine + compose plugin from Docker's apt repo if missing,
#                    plus git, python3, cron
#   3. docker group  the login user joins it (cron and compose run without sudo)
#   4. checkout      clone the pinned repo into DEPLOY_DIR if it is not there
#   5. .env          refuse to go on without $DEPLOY_DIR/.env and its required names
#   6. daemon.json   log rotation and pinned DNS (the Pi's two scripts, which are
#                    host-generic: they only merge keys into /etc/docker/daemon.json)
#   7. Wi-Fi         power saving off (NetworkManager drop-in)
#   8. cron          monitor-prod.sh every minute in the factory role, the Mongo
#                    backup at 02:30
#   9. firewall      enable-factory-host-firewall.sh --role factory --peer $SITE_HOST
#  10. verify        LAST. Exits non-zero if anything critical is not in place, and
#                    leaves every earlier step that succeeded installed: a firewall
#                    that verified is worth keeping even if, say, cron did not.
#
# It never prints a value from .env except SITE_HOST (an address, needed to show the
# firewall plan). Everything else is reported by name only.
#
# It does not start the stack: `docker compose -f docker-compose.factory.yml up -d`
# is the cutover's step, after Mongo has been restored (see the runbook).
#
# DRY_RUN=1 in the environment forces a dry run even with --apply, so the test suite
# (which exports it) can never install anything.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

REPO_URL="https://github.com/simonjamesrowe/simonrowe-dev-monorepo.git"
DEPLOY_DIR=${DEPLOY_DIR:-/srv/simonrowe-dev-monorepo}
COMPOSE_PROJECT="simonrowe-factory"
INSTALL_USER=${INSTALL_USER:-${SUDO_USER:-$(id -un)}}
OS_RELEASE_FILE=${OS_RELEASE_FILE:-/etc/os-release}
DAEMON_JSON=${DAEMON_JSON:-/etc/docker/daemon.json}
NM_CONF_DIR=${NM_CONF_DIR:-/etc/NetworkManager/conf.d}
# `zz-` so it sorts after Ubuntu's own default-wifi-powersave-on.conf (powersave = 3):
# NetworkManager reads conf.d in name order and the last setting wins.
NM_POWERSAVE_FILE="$NM_CONF_DIR/zz-factory-wifi-powersave-off.conf"
LOG_DIR=${LOG_DIR:-/var/log/prod-health}
BACKUP_DIR=${BACKUP_DIR:-/var/backups/software-factory}
BACKUP_LOG="$LOG_DIR/factory-mongo-backup.log"
BACKUP_LOGROTATE=${BACKUP_LOGROTATE:-/etc/logrotate.d/factory-mongo-backup}
SUDO=${SUDO-sudo}

ENV_FILE="$DEPLOY_DIR/.env"
COMPOSE_FILE="$DEPLOY_DIR/docker-compose.factory.yml"
MONITOR_SCRIPT="$DEPLOY_DIR/scripts/monitor-prod.sh"
BACKUP_SCRIPT="$DEPLOY_DIR/scripts/backup-factory-mongo.sh"
MONITOR_ENV="MONITOR_ROLE=factory COMPOSE_FILE=$COMPOSE_FILE COMPOSE_PROJECT=$COMPOSE_PROJECT"
BACKUP_CRON="30 2 * * * BACKUP_DIR=$BACKUP_DIR $BACKUP_SCRIPT >> $BACKUP_LOG 2>&1"

# Required in .env: docker-compose.factory.yml's `${VAR:?}` names (compose refuses to
# start without them), plus the four the two-host split needs (docs, decisions).
REQUIRED_VARS=(
  SITE_HOST
  FACTORY_BIND_ADDRESS
  TEMPORAL_ADDRESS
  DEPLOY_DIR
  DOCKER_GID
  GITHUB_WEBHOOK_SECRET
  FACTORY_TRIGGER_TOKEN
  GITHUB_APP_CLIENT_ID
  GITHUB_APP_PRIVATE_KEY_HOST_PATH
  CLAUDE_CODE_OAUTH_TOKEN
)
# Defaulted in the compose file to empty or a placeholder: the stack starts without
# them, but the named module is inert. Reported, not refused.
RECOMMENDED_VARS=(
  DEPLOY_ENV_GID
  FACTORY_READ_TOKEN
  LINEAR_API_KEY
  FACTORY_LINEAR_TEAM_KEY
  DEPENDENCYTRACK_API_KEY
  GRAFANA_CLOUD_LOKI_ENDPOINT
  GRAFANA_CLOUD_LOKI_USER
  GRAFANA_CLOUD_API_KEY
)

MODE="dry-run"
for arg in "$@"; do
  case "$arg" in
    --apply) MODE="apply" ;;
    -h|--help)
      sed -n '4,40p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "Unknown argument: $arg (use --apply, or nothing for a dry run)" >&2; exit 2 ;;
  esac
done
if [[ "${DRY_RUN:-0}" != "0" && "$MODE" == "apply" ]]; then
  echo "DRY_RUN=${DRY_RUN} is set: forcing a dry run despite --apply."
  MODE="dry-run"
fi

step() { echo; echo "==> $*"; }
info() { echo "    $*"; }
warn() { echo "    WARN: $*"; }
die() { echo "    ERROR: $*" >&2; exit 1; }

# run <cmd...>: execute, or say what would be executed.
run() {
  if [[ "$MODE" != "apply" ]]; then
    echo "    would run: $*"
    return 0
  fi
  "$@"
}

# write_root_file <path> <mode>: content on stdin.
write_root_file() {
  local path="$1" mode="$2" content
  content="$(cat)"
  if [[ "$MODE" != "apply" ]]; then
    echo "    would write $path (mode $mode):"
    printf '%s\n' "$content" | sed 's/^/      | /'
    return 0
  fi
  $SUDO mkdir -p "$(dirname "$path")"
  printf '%s\n' "$content" | $SUDO tee "$path" >/dev/null
  $SUDO chmod "$mode" "$path"
}

# env_has <name>: the .env defines it with a non-empty value. Never prints the value.
env_has() {
  local name="$1"
  grep -qE "^[[:space:]]*(export[[:space:]]+)?${name}=[^[:space:]#]" "$ENV_FILE" 2>/dev/null
}

# env_value <name>: for the few non-secret values this script needs (SITE_HOST,
# DOCKER_GID, DEPLOY_DIR). Last definition wins, as in compose. Quotes stripped.
env_value() {
  local name="$1"
  grep -E "^[[:space:]]*(export[[:space:]]+)?${name}=" "$ENV_FILE" 2>/dev/null \
    | tail -n 1 | sed -E "s/^[[:space:]]*(export[[:space:]]+)?${name}=//; s/^[\"']//; s/[\"'][[:space:]]*$//; s/[[:space:]]+#.*$//"
}

have_docker() {
  command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1
}

# ---------------------------------------------------------------------------
check_platform() {
  step "1. Platform"
  local arch id
  arch="$(uname -m)"
  id="$( . "$OS_RELEASE_FILE" 2>/dev/null && echo "${ID:-}" )" || id=""
  [[ "$arch" == "x86_64" ]] || die "this host is $arch; the factory host is x86_64 (the Pi is not a factory host)"
  [[ "$id" == "ubuntu" ]] || die "this host is '${id:-unknown}', not Ubuntu ($OS_RELEASE_FILE)"
  info "x86_64 Ubuntu: ok"
}

install_packages() {
  step "2. Packages"
  local missing=() pkg
  for pkg in git python3 crontab gzip; do
    command -v "$pkg" >/dev/null 2>&1 || missing+=("$pkg")
  done
  if (( ${#missing[@]} > 0 )); then
    info "missing: ${missing[*]}"
    run $SUDO apt-get update
    run $SUDO apt-get install -y git python3 cron gzip ca-certificates curl
  else
    info "git, python3, cron, gzip: present"
  fi

  if have_docker; then
    info "Docker Engine and the compose plugin: present"
    return 0
  fi
  info "Docker Engine or the compose plugin is missing: installing from Docker's apt repository"
  local codename
  codename="$( . "$OS_RELEASE_FILE" 2>/dev/null && echo "${UBUNTU_CODENAME:-${VERSION_CODENAME:-}}" )" || codename=""
  [[ -n "$codename" ]] || die "cannot read the Ubuntu codename from $OS_RELEASE_FILE"
  run $SUDO apt-get update
  run $SUDO apt-get install -y ca-certificates curl
  run $SUDO install -m 0755 -d /etc/apt/keyrings
  run $SUDO curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  run $SUDO chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $codename stable" \
    | write_root_file /etc/apt/sources.list.d/docker.list 0644
  run $SUDO apt-get update
  run $SUDO apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  run $SUDO systemctl enable --now docker
}

join_docker_group() {
  step "3. docker group"
  if id -nG "$INSTALL_USER" 2>/dev/null | tr ' ' '\n' | grep -qx docker; then
    info "$INSTALL_USER is in the docker group"
    return 0
  fi
  run $SUDO usermod -aG docker "$INSTALL_USER"
  info "$INSTALL_USER must log in again for an interactive shell to see the group (cron picks it up on its own)"
}

clone_checkout() {
  step "4. Checkout"
  if [[ -d "$DEPLOY_DIR/.git" ]]; then
    local origin
    origin="$(git -C "$DEPLOY_DIR" remote get-url origin 2>/dev/null || true)"
    if [[ "$origin" == "$REPO_URL" ]]; then
      info "$DEPLOY_DIR is a checkout of $REPO_URL"
    else
      warn "$DEPLOY_DIR's origin is '$origin', not $REPO_URL - the deployer fetches from the pinned URL regardless"
    fi
    return 0
  fi
  [[ -e "$DEPLOY_DIR" && -n "$(ls -A "$DEPLOY_DIR" 2>/dev/null)" ]] \
    && die "$DEPLOY_DIR exists, is not empty and is not a git checkout - refusing to clone over it"
  run $SUDO mkdir -p "$DEPLOY_DIR"
  run $SUDO chown "$INSTALL_USER:" "$DEPLOY_DIR"
  # Anonymous https, the same pinned URL sync-config fetches from: the host holds no
  # GitHub credential.
  run git clone "$REPO_URL" "$DEPLOY_DIR"
}

check_env_file() {
  step "5. $ENV_FILE"
  if [[ ! -f "$ENV_FILE" ]]; then
    echo "    ERROR: $ENV_FILE is missing. Create it (mode 0640, group DEPLOY_ENV_GID) with:" >&2
    printf '      %s   (required)\n' "${REQUIRED_VARS[@]}" >&2
    printf '      %s   (recommended)\n' "${RECOMMENDED_VARS[@]}" >&2
    echo "    See docs/runbooks/factory-host.md (\"The .env\"). Nothing past this point was done." >&2
    exit 1
  fi
  local name missing=()
  for name in "${REQUIRED_VARS[@]}"; do
    env_has "$name" || missing+=("$name")
  done
  if (( ${#missing[@]} > 0 )); then
    echo "    ERROR: $ENV_FILE has no value for: ${missing[*]}" >&2
    echo "    Nothing past this point was done." >&2
    exit 1
  fi
  info "all ${#REQUIRED_VARS[@]} required names are set"
  for name in "${RECOMMENDED_VARS[@]}"; do
    env_has "$name" || warn "$name is not set (the module that needs it stays inert)"
  done
  local declared
  declared="$(env_value DEPLOY_DIR)"
  [[ "$declared" == "$DEPLOY_DIR" ]] \
    || warn ".env's DEPLOY_DIR is '$declared' but this run uses $DEPLOY_DIR - the deployer mounts the .env value"
  # Secrets: the deployer reads .env through its owning group, nobody else may.
  local perms
  perms="$(stat -c '%a' "$ENV_FILE" 2>/dev/null || stat -f '%Lp' "$ENV_FILE" 2>/dev/null || echo "")"
  if [[ -n "$perms" && "${perms: -1}" != "0" ]]; then
    warn "$ENV_FILE is readable by others (mode $perms)"
    run chmod o-rwx "$ENV_FILE"
  fi
}

configure_daemon() {
  step "6. Docker daemon: log rotation and pinned DNS"
  local rotation="$DEPLOY_DIR/scripts/enable-docker-log-rotation.sh"
  local dns="$DEPLOY_DIR/scripts/enable-docker-dns.sh"
  # Both scripts are host-generic despite their Pi-flavoured comments: they merge
  # one key each into daemon.json with python3 and restart nothing. Pinned public
  # DNS matters twice here: a boot with the Wi-Fi down must not strand containers
  # without upstream servers, and page-reader's firewall drops LAN resolvers.
  local before after
  before="$( { cksum < "$DAEMON_JSON"; } 2>/dev/null || echo none)"
  # --verify with SUDO= : its exit status reads only daemon.json, and its per-container
  # report would otherwise ask for a sudo password during a dry run.
  if DAEMON_JSON="$DAEMON_JSON" SUDO= bash "$rotation" --verify >/dev/null 2>&1; then
    info "log rotation already configured"
  else
    run env DAEMON_JSON="$DAEMON_JSON" SUDO="$SUDO" bash "$rotation" --apply
  fi
  if DAEMON_JSON="$DAEMON_JSON" SUDO= bash "$dns" --verify >/dev/null 2>&1; then
    info "DNS already pinned"
  else
    run env DAEMON_JSON="$DAEMON_JSON" SUDO="$SUDO" bash "$dns" --apply
  fi
  [[ "$MODE" == "apply" ]] || return 0
  after="$( { cksum < "$DAEMON_JSON"; } 2>/dev/null || echo none)"
  [[ "$before" != "$after" ]] || return 0
  # daemon.json only takes effect on a daemon restart, which restarts every
  # container. Before the stack exists that costs nothing; after, it is a
  # maintenance-window decision this script does not make.
  if [[ -z "$(docker ps -q 2>/dev/null)" ]]; then
    info "no containers running: restarting Docker to load daemon.json"
    run $SUDO systemctl restart docker
  else
    warn "daemon.json changed but containers are running: restart Docker in a maintenance window (sudo systemctl restart docker)"
  fi
}

wifi_powersave() {
  step "7. Wi-Fi power saving"
  if [[ ! -d "$(dirname "$NM_CONF_DIR")" ]] && ! command -v nmcli >/dev/null 2>&1; then
    warn "NetworkManager is not installed, so its powersave setting cannot be used. If the Wi-Fi is managed by netplan/networkd, turn power saving off with 'iw dev <wlan> set power_save off' from a systemd unit instead."
    return 0
  fi
  # 2 = disable. Takes effect when the connection is next activated (a reboot, or a
  # reconnect, which this script deliberately does not do: it would drop an SSH session).
  printf '[connection]\nwifi.powersave = 2\n' | write_root_file "$NM_POWERSAVE_FILE" 0644
}

install_cron() {
  step "8. Cron: watchdog every minute, Mongo backup at 02:30"
  # Reuses the Pi's installer (log dir, logrotate, crontab entry, a first run),
  # pointed at the factory role through MONITOR_ENV.
  run env MONITOR_ENV="$MONITOR_ENV" bash "$DEPLOY_DIR/scripts/install-prod-monitoring.sh"

  run $SUDO mkdir -p "$BACKUP_DIR"
  run $SUDO chown "$INSTALL_USER:" "$BACKUP_DIR"
  run $SUDO chmod 0750 "$BACKUP_DIR"
  write_root_file "$BACKUP_LOGROTATE" 0644 <<EOF
$BACKUP_LOG {
    weekly
    rotate 8
    compress
    missingok
    notifempty
    copytruncate
}
EOF
  if [[ "$MODE" != "apply" ]]; then
    echo "    would install crontab line: $BACKUP_CRON"
    return 0
  fi
  local existing filtered
  existing="$(crontab -l 2>/dev/null || true)"
  filtered="$(printf '%s\n' "$existing" | grep -Fv "$BACKUP_SCRIPT" || true)"
  {
    printf '%s\n' "$filtered" | sed '/^[[:space:]]*$/d'
    printf '%s\n' "$BACKUP_CRON"
  } | crontab -
  info "installed: $BACKUP_CRON"
}

install_firewall() {
  step "9. Firewall (DOCKER-USER)"
  local peer
  peer="$(env_value SITE_HOST)"
  info "peer (SITE_HOST): $peer"
  if [[ "$MODE" == "apply" ]]; then
    SUDO="$SUDO" bash "$DEPLOY_DIR/scripts/enable-factory-host-firewall.sh" --role factory --peer "$peer" --apply
  else
    echo "    would run: $DEPLOY_DIR/scripts/enable-factory-host-firewall.sh --role factory --peer $peer --apply"
  fi
}

# ---------------------------------------------------------------------------
verify() {
  step "10. Verify"
  if [[ "$MODE" != "apply" ]]; then
    info "would verify: docker + compose, docker group, checkout, .env, daemon.json, cron, firewall"
    echo
    echo "DRY RUN complete - nothing was changed. Re-run with --apply to install."
    return 0
  fi
  local failed=0
  ok() { echo "    ok:   $*"; }
  bad() { echo "    FAIL: $*"; failed=$((failed + 1)); }

  if systemctl is-active --quiet docker && have_docker; then ok "Docker is running, with compose"; else bad "Docker is not running, or the compose plugin is missing"; fi
  if id -nG "$INSTALL_USER" | tr ' ' '\n' | grep -qx docker; then ok "$INSTALL_USER is in the docker group"; else bad "$INSTALL_USER is not in the docker group"; fi
  if [[ "$(git -C "$DEPLOY_DIR" remote get-url origin 2>/dev/null)" == "$REPO_URL" ]]; then ok "checkout at $DEPLOY_DIR"; else bad "$DEPLOY_DIR is not a checkout of $REPO_URL"; fi
  if [[ -f "$COMPOSE_FILE" ]]; then ok "compose file present"; else bad "$COMPOSE_FILE is missing (is the checkout on main?)"; fi

  local docker_gid env_gid key_path
  docker_gid="$(getent group docker | cut -d: -f3)"
  env_gid="$(env_value DOCKER_GID)"
  if [[ -n "$docker_gid" && "$env_gid" == "$docker_gid" ]]; then ok "DOCKER_GID matches the docker group ($docker_gid)"; else bad "DOCKER_GID in .env ('$env_gid') is not the docker group's gid ('$docker_gid'): the deployer could not use the socket"; fi
  key_path="$(env_value GITHUB_APP_PRIVATE_KEY_HOST_PATH)"
  if [[ -n "$key_path" && -f "$key_path" ]]; then ok "the GitHub App key named by GITHUB_APP_PRIVATE_KEY_HOST_PATH exists"; else bad "the file named by GITHUB_APP_PRIVATE_KEY_HOST_PATH does not exist"; fi

  if DAEMON_JSON="$DAEMON_JSON" SUDO= bash "$DEPLOY_DIR/scripts/enable-docker-log-rotation.sh" --verify >/dev/null 2>&1; then ok "log rotation configured"; else bad "log rotation not configured in $DAEMON_JSON"; fi
  if DAEMON_JSON="$DAEMON_JSON" SUDO= bash "$DEPLOY_DIR/scripts/enable-docker-dns.sh" --verify >/dev/null 2>&1; then ok "DNS pinned"; else bad "DNS not pinned in $DAEMON_JSON (page-reader's DNS would hit the firewall)"; fi

  local cron
  cron="$(crontab -l 2>/dev/null || true)"
  if grep -qF "MONITOR_ROLE=factory" <<<"$cron" && grep -qF "$MONITOR_SCRIPT" <<<"$cron"; then ok "watchdog cron (factory role)"; else bad "no factory-role watchdog in crontab"; fi
  if grep -qF "$BACKUP_SCRIPT" <<<"$cron"; then ok "Mongo backup cron"; else bad "no Mongo backup in crontab"; fi
  if [[ -d "$BACKUP_DIR" && -w "$BACKUP_DIR" ]]; then ok "$BACKUP_DIR writable"; else bad "$BACKUP_DIR missing or not writable by $INSTALL_USER"; fi

  if SUDO="$SUDO" bash "$DEPLOY_DIR/scripts/enable-factory-host-firewall.sh" --role factory --peer "$(env_value SITE_HOST)" --verify >/dev/null 2>&1; then
    ok "firewall in place"
  else
    bad "firewall not in place (run enable-factory-host-firewall.sh --verify for detail)"
  fi

  # Not critical: the factory works with power saving on, it just drops off the
  # network more often.
  if [[ -f "$NM_POWERSAVE_FILE" ]]; then ok "Wi-Fi power saving off (from the next connection)"; else warn "Wi-Fi power saving not configured"; fi

  echo
  if (( failed > 0 )); then
    echo "FAILED: $failed critical check(s). Steps that succeeded stay installed; fix the above and re-run."
    return 1
  fi
  echo "Factory host ready. Next: the cutover in docs/runbooks/factory-host.md."
}

echo "install-factory-host ($MODE): DEPLOY_DIR=$DEPLOY_DIR, user $INSTALL_USER"
# 1-5 are prerequisites: nothing after them can work without them, so a failure ends
# the run. 6-9 are independent of each other, so a failure in one is reported and the
# rest still run; step 10 is what decides the exit status.
check_platform
install_packages
join_docker_group
clone_checkout
check_env_file
configure_daemon || warn "the daemon.json step failed (see above); carrying on"
wifi_powersave || warn "the Wi-Fi step failed (see above); carrying on"
install_cron || warn "the cron step failed (see above); carrying on"
install_firewall || warn "the firewall step did not verify (see above); carrying on"
verify
