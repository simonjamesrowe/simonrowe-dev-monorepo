#!/usr/bin/env bash
#
# Coverage for install-factory-host.sh, in dry-run mode only: it prints the plan and
# changes nothing, it refuses a host that is not x86_64 Ubuntu, and it refuses to go
# past the checkout without a complete $DEPLOY_DIR/.env - without ever printing a
# secret from it.
#
# Every command that could change the machine (sudo, apt-get, usermod, crontab,
# systemctl, git, iptables) is stubbed on PATH and recorded; `uname`, `id`, `docker`
# and `getent` are stubbed so the answers do not depend on the machine running the
# test. The --apply path is not exercised here: it needs a real Ubuntu host.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCRIPT="$PROJECT_DIR/scripts/install-factory-host.sh"

export DRY_RUN=1

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
STUBS="$TMP/stubs"
CALLS="$TMP/calls"
mkdir -p "$STUBS"

for cmd in sudo apt-get usermod crontab systemctl iptables nmcli; do
  cat >"$STUBS/$cmd" <<EOF
#!/usr/bin/env bash
echo "$cmd \$*" >> "\$CALLS"
[[ "$cmd" == crontab && "\${1:-}" == "-l" ]] && exit 0
exit 99
EOF
done
cat >"$STUBS/uname" <<'EOF'
#!/usr/bin/env bash
echo "${FAKE_ARCH:-x86_64}"
EOF
cat >"$STUBS/id" <<'EOF'
#!/usr/bin/env bash
[[ "$1" == "-un" ]] && { echo simon; exit 0; }
[[ "$1" == "-nG" ]] && { echo "simon adm"; exit 0; }
exit 1
EOF
cat >"$STUBS/git" <<'EOF'
#!/usr/bin/env bash
echo "git $*" >> "$CALLS"
if [[ "$1" == "-C" && "$3" == "remote" ]]; then
  echo "https://github.com/simonjamesrowe/simonrowe-dev-monorepo.git"; exit 0
fi
exit 99
EOF
cat >"$STUBS/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $*" >> "$CALLS"
[[ "$1" == "compose" && "$2" == "version" ]] && { [[ "${FAKE_NO_DOCKER:-0}" == 1 ]] && exit 1; echo "v2"; exit 0; }
[[ "$1" == "ps" ]] && exit 0
exit 0
EOF
cat >"$STUBS/getent" <<'EOF'
#!/usr/bin/env bash
echo "docker:x:988:"
EOF
chmod +x "$STUBS"/*

cat >"$TMP/os-release-ubuntu" <<'EOF'
NAME="Ubuntu"
ID=ubuntu
VERSION_CODENAME=noble
UBUNTU_CODENAME=noble
EOF
cat >"$TMP/os-release-debian" <<'EOF'
ID=debian
VERSION_CODENAME=trixie
EOF

SECRET="s3cr3t-do-not-print-$$"
full_env() {
  cat <<EOF
# factory host
SITE_HOST=192.168.1.10
FACTORY_BIND_ADDRESS=192.168.1.20
TEMPORAL_ADDRESS=192.168.1.10:7233
DEPLOY_DIR=$1
DOCKER_GID=988
DEPLOY_ENV_GID=1000
GITHUB_WEBHOOK_SECRET=$SECRET
FACTORY_TRIGGER_TOKEN="$SECRET"
GITHUB_APP_CLIENT_ID=Iv1.$SECRET
GITHUB_APP_PRIVATE_KEY_HOST_PATH=/srv/keys/app.pem
CLAUDE_CODE_OAUTH_TOKEN=$SECRET
LINEAR_API_KEY=$SECRET
EOF
}

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

# new_deploy_dir <name> [checkout]: a deploy dir whose scripts/ are this repo's, so
# the installer's read-only --verify calls run the real daemon.json scripts.
new_deploy_dir() {
  local dir="$TMP/$1"
  mkdir -p "$dir"
  if [[ "${2:-}" == checkout ]]; then
    mkdir -p "$dir/.git"
    ln -s "$PROJECT_DIR/scripts" "$dir/scripts"
  fi
  echo "$dir"
}

ROOT="$TMP/root"
run_install() {
  local deploy="$1"
  shift
  : > "$CALLS"
  env PATH="$STUBS:$PATH" CALLS="$CALLS" DEPLOY_DIR="$deploy" INSTALL_USER=simon \
    OS_RELEASE_FILE="${OS_RELEASE:-$TMP/os-release-ubuntu}" \
    DAEMON_JSON="$ROOT/etc/docker/daemon.json" NM_CONF_DIR="$ROOT/etc/NetworkManager/conf.d" \
    LOG_DIR="$ROOT/var/log/prod-health" BACKUP_DIR="$ROOT/var/backups/software-factory" \
    BACKUP_LOGROTATE="$ROOT/etc/logrotate.d/factory-mongo-backup" \
    FAKE_NO_DOCKER="${FAKE_NO_DOCKER:-0}" FAKE_ARCH="${FAKE_ARCH:-x86_64}" \
    bash "$SCRIPT" "$@" 2>&1
}

# Mutating calls: anything recorded except the read-only ones.
mutations() {
  grep -vE '^(docker compose version|docker ps|git -C .* remote get-url origin|crontab -l)' "$CALLS" || true
}

# ---------------------------------------------------------------------------
echo "  dry run with a complete .env: the whole plan, nothing changed"
# ---------------------------------------------------------------------------
dir="$(new_deploy_dir ready checkout)"
full_env "$dir" > "$dir/.env"
chmod 0640 "$dir/.env"
out="$(run_install "$dir")"
rc=$?
check "exits 0" "[[ $rc -eq 0 ]]"
check "walks all ten steps" "[[ \$(grep -c '^==> ' <<<\"\$out\") -eq 10 ]]"
check "says nothing was changed" "grep -q 'DRY RUN complete - nothing was changed' <<<\"\$out\""
check "no mutating command was run" "[[ -z \"\$(mutations)\" ]]"
check "sudo was never invoked" "! grep -q '^sudo' '$CALLS'"
check "no file was written under the fake root" "[[ ! -e '$ROOT' ]]"
check "no secret from .env is printed" "! grep -q '$SECRET' <<<\"\$out\""
check "plans to join the docker group" "grep -q 'would run: sudo usermod -aG docker simon' <<<\"\$out\""
check "plans log rotation and DNS via the existing scripts" \
  "grep -q 'would run: env DAEMON_JSON=.* bash $dir/scripts/enable-docker-log-rotation.sh --apply' <<<\"\$out\" \
   && grep -q 'would run: env DAEMON_JSON=.* bash $dir/scripts/enable-docker-dns.sh --apply' <<<\"\$out\""
check "plans the Wi-Fi drop-in with powersave = 2, sorting after Ubuntu's default" \
  "grep -q 'would write .*/conf.d/zz-factory-wifi-powersave-off.conf' <<<\"\$out\" && grep -q '| wifi.powersave = 2' <<<\"\$out\""
check "plans the watchdog in the factory role through install-prod-monitoring.sh" \
  "grep -qF 'would run: env MONITOR_ENV=MONITOR_ROLE=factory COMPOSE_FILE=$dir/docker-compose.factory.yml COMPOSE_PROJECT=simonrowe-factory bash $dir/scripts/install-prod-monitoring.sh' <<<\"\$out\""
check "plans the nightly backup at 02:30" \
  "grep -qF \"would install crontab line: 30 2 * * * BACKUP_DIR=$ROOT/var/backups/software-factory $dir/scripts/backup-factory-mongo.sh\" <<<\"\$out\""
check "plans the firewall with the factory role and SITE_HOST as peer" \
  "grep -qF 'would run: $dir/scripts/enable-factory-host-firewall.sh --role factory --peer 192.168.1.10 --apply' <<<\"\$out\""
check "Docker already present: no apt-get planned" "! grep -q 'apt-get' <<<\"\$out\""
check "a recommended name that is missing is reported, by name only" \
  "grep -q 'WARN: GRAFANA_CLOUD_API_KEY is not set' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  daemon.json already done: the existing scripts' --verify is honoured"
# ---------------------------------------------------------------------------
mkdir -p "$TMP/done-root/etc/docker"
printf '{"log-driver":"json-file","log-opts":{"max-size":"20m","max-file":"5"},"dns":["1.1.1.1","8.8.8.8"]}\n' \
  > "$TMP/done-root/etc/docker/daemon.json"
out="$(ROOT_OVERRIDE=1 env PATH="$STUBS:$PATH" CALLS="$CALLS" DEPLOY_DIR="$dir" INSTALL_USER=simon \
  OS_RELEASE_FILE="$TMP/os-release-ubuntu" DAEMON_JSON="$TMP/done-root/etc/docker/daemon.json" \
  NM_CONF_DIR="$ROOT/etc/NetworkManager/conf.d" LOG_DIR="$ROOT/l" BACKUP_DIR="$ROOT/b" \
  BACKUP_LOGROTATE="$ROOT/lr" bash "$SCRIPT" 2>&1)"
check "log rotation reported as already configured" "grep -q 'log rotation already configured' <<<\"\$out\""
check "DNS reported as already pinned" "grep -q 'DNS already pinned' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  no Docker yet: the plan installs it from Docker's apt repository"
# ---------------------------------------------------------------------------
out="$(FAKE_NO_DOCKER=1 run_install "$dir")"
check "adds Docker's apt source for the host's codename" \
  "grep -qF 'deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu noble stable' <<<\"\$out\""
check "installs the engine and the compose plugin" \
  "grep -qF 'would run: sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin' <<<\"\$out\""
check "and still runs nothing" "[[ -z \"\$(mutations)\" ]]"

# ---------------------------------------------------------------------------
echo "  no checkout yet: the pinned anonymous clone is planned"
# ---------------------------------------------------------------------------
dir="$(new_deploy_dir fresh)"
rmdir "$dir"
out="$(run_install "$dir")"
rc=$?
check "plans git clone of the pinned https URL" \
  "grep -qF 'would run: git clone https://github.com/simonjamesrowe/simonrowe-dev-monorepo.git $dir' <<<\"\$out\""
check "git clone was not run" "! grep -q '^git clone' '$CALLS'"

# ---------------------------------------------------------------------------
echo "  refuses without .env"
# ---------------------------------------------------------------------------
check "exits non-zero" "[[ $rc -ne 0 ]]"
check "says .env is missing" "grep -q \"$dir/.env is missing\" <<<\"\$out\""
for name in SITE_HOST FACTORY_BIND_ADDRESS TEMPORAL_ADDRESS DEPLOY_DIR DOCKER_GID GITHUB_WEBHOOK_SECRET \
  FACTORY_TRIGGER_TOKEN GITHUB_APP_CLIENT_ID GITHUB_APP_PRIVATE_KEY_HOST_PATH CLAUDE_CODE_OAUTH_TOKEN; do
  check "names $name" "grep -q '  $name   (required)' <<<\"\$out\""
done
check "does not go on to step 6" "! grep -q '^==> 6\.' <<<\"\$out\""

dir="$(new_deploy_dir partial checkout)"
full_env "$dir" | grep -v '^CLAUDE_CODE_OAUTH_TOKEN=' | sed 's/^TEMPORAL_ADDRESS=.*/TEMPORAL_ADDRESS=/' > "$dir/.env"
out="$(run_install "$dir")"
rc=$?
check "an incomplete .env is refused too" "[[ $rc -ne 0 ]]"
check "naming exactly what is missing (an empty value counts as missing)" \
  "grep -q 'has no value for: TEMPORAL_ADDRESS CLAUDE_CODE_OAUTH_TOKEN' <<<\"\$out\""
check "without printing a secret" "! grep -q '$SECRET' <<<\"\$out\""
check "and without going on" "! grep -q '^==> 6\.' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  refuses the wrong platform"
# ---------------------------------------------------------------------------
dir="$TMP/ready"
out="$(FAKE_ARCH=aarch64 run_install "$dir")"
rc=$?
check "aarch64 is refused" "[[ $rc -ne 0 ]] && grep -q 'this host is aarch64' <<<\"\$out\""
out="$(OS_RELEASE="$TMP/os-release-debian" run_install "$dir")"
rc=$?
check "Debian is refused" "[[ $rc -ne 0 ]] && grep -q \"this host is 'debian', not Ubuntu\" <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  DRY_RUN=1 beats --apply"
# ---------------------------------------------------------------------------
out="$(DRY_RUN=1 run_install "$dir" --apply)"
rc=$?
check "exits 0 as a dry run" "[[ $rc -eq 0 ]] && grep -q 'forcing a dry run despite --apply' <<<\"\$out\""
check "and runs nothing" "[[ -z \"\$(mutations)\" ]] && [[ ! -e '$ROOT' ]]"

out="$(run_install "$dir" --bogus)"
rc=$?
check "an unknown argument is refused" "[[ $rc -eq 2 ]]"

echo
printf '  %d checks, %d failures\n' "$checks" "$failures"
[[ "$failures" -eq 0 ]]
