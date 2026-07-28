#!/usr/bin/env bash
set -euo pipefail

reviewer_image=${REVIEWER_IMAGE:-ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-reviewer:latest}
install_directory=${REVIEWER_INSTALL_DIR:-/opt/temporal-reviewer}
state_directory=${REVIEWER_STATE_DIR:-/var/lib/temporal-reviewer}
service_user=${REVIEWER_SERVICE_USER:-temporal-reviewer}
service_group=${REVIEWER_SERVICE_GROUP:-temporal-reviewer}
unit_source=${REVIEWER_UNIT_SOURCE:-config/systemd/temporal-reviewer-worker.service}

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

docker pull "$reviewer_image"
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

if [[ ! -f "$install_directory/reviewer.env" ]]; then
  install -o root -g "$service_group" -m 0640 /dev/null "$install_directory/reviewer.env"
  echo "Created $install_directory/reviewer.env; populate it before starting the worker."
fi

install -o root -g root -m 0644 \
  "$unit_source" \
  /etc/systemd/system/temporal-reviewer-worker.service
systemctl daemon-reload

echo "Reviewer worker installed from $reviewer_image."
echo "After populating $install_directory/reviewer.env, run:"
echo "  systemctl enable --now temporal-reviewer-worker"
