#!/usr/bin/env bash
set -euo pipefail

reviewer_image=${REVIEWER_IMAGE:-ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-reviewer:latest}
install_directory=${REVIEWER_INSTALL_DIR:-/opt/temporal-reviewer}
state_directory=${REVIEWER_STATE_DIR:-/var/lib/temporal-reviewer}
service_user=${REVIEWER_SERVICE_USER:-temporal-reviewer}
service_group=${REVIEWER_SERVICE_GROUP:-temporal-reviewer}
unit_source=${REVIEWER_UNIT_SOURCE:-config/systemd/temporal-reviewer-worker.service}
deploy_env=${REVIEWER_DEPLOY_ENV:-$(pwd)/.env}

if [[ ${EUID} -ne 0 ]]; then
  echo "Run this installer as root on the Raspberry Pi." >&2
  exit 1
fi

if ! command -v java >/dev/null || ! java -version 2>&1 | grep -q '"21'; then
  echo "Java 21 must be installed on the Raspberry Pi host." >&2
  exit 1
fi

if ! getent group "$service_group" >/dev/null 2>&1; then
  groupadd --system "$service_group"
fi

if ! id "$service_user" >/dev/null 2>&1; then
  useradd \
    --system \
    --gid "$service_group" \
    --home-dir "$state_directory" \
    --create-home \
    --shell /usr/sbin/nologin \
    "$service_user"
fi

install -d -o "$service_user" -g "$service_group" -m 0750 "$install_directory"
install -d -o "$service_user" -g "$service_group" -m 0750 "$state_directory/workspaces"

# Always try to refresh, so a moving tag such as :latest is not served stale. Fall back
# to a local-only image, which is how a build from an unpublished branch is installed.
if ! docker pull "$reviewer_image"; then
  if ! docker image inspect "$reviewer_image" >/dev/null 2>&1; then
    echo "Cannot pull $reviewer_image and it is not present locally." >&2
    exit 1
  fi
  echo "Pull failed; using the local image $reviewer_image."
fi
container_id=$(docker create "$reviewer_image")
temporary_jar=$(mktemp "$install_directory/reviewer.jar.XXXXXX")
cleanup() {
  docker rm "$container_id" >/dev/null 2>&1 || true
  rm -f "$temporary_jar"
}
trap cleanup EXIT

docker cp "$container_id:/app/reviewer.jar" "$temporary_jar"
chown "$service_user:$service_group" "$temporary_jar"
chmod 0550 "$temporary_jar"
mv "$temporary_jar" "$install_directory/reviewer.jar"

# The worker reads the same .env as the rest of the production stack, so there is one
# place to edit secrets. The unit keeps referencing a stable path; this symlink is what
# points that path at the deploy directory, so the unit stays host-agnostic.
if [[ ! -e "$install_directory/reviewer.env" ]]; then
  if [[ ! -f "$deploy_env" ]]; then
    echo "Deploy .env not found at $deploy_env; set REVIEWER_DEPLOY_ENV." >&2
    exit 1
  fi
  ln -s "$deploy_env" "$install_directory/reviewer.env"
  echo "Linked $install_directory/reviewer.env -> $deploy_env"
fi

# That .env is now the only copy of the Claude and GitHub App credentials.
if [[ -n "$(find "$(readlink -f "$install_directory/reviewer.env")" -maxdepth 0 -perm /0077 2>/dev/null)" ]]; then
  echo "WARNING: $deploy_env is readable beyond its owner; run: chmod 0600 $deploy_env" >&2
fi

install -o root -g root -m 0644 \
  "$unit_source" \
  /etc/systemd/system/temporal-reviewer-worker.service
systemctl daemon-reload

# Records which image the installed jar came from, so a deploy can tell whether the
# host worker has drifted from REVIEWER_IMAGE and reinstall only when it has.
printf '%s\n' "$reviewer_image" >"$install_directory/installed-image"
chmod 0644 "$install_directory/installed-image"

echo "Reviewer worker installed from $reviewer_image."
echo "After populating $install_directory/reviewer.env, run:"
echo "  systemctl enable --now temporal-reviewer-worker"
