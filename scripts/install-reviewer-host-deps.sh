#!/usr/bin/env bash
# Installs the host prerequisites that install-reviewer-worker.sh expects:
# Java 21 at /usr/bin/java and Claude Code at /usr/local/bin/claude.
#
# Both must be system-wide. The temporal-reviewer service account runs with
# ProtectHome=true, so a per-user install under ~/.local/bin is invisible to it,
# and the systemd unit hardcodes ExecStart=/usr/bin/java.
#
# The official installer (https://claude.ai/install.sh) cannot be used here: it
# refuses to run under sudo and installs into the invoking user's ~/.local/bin.
# This script performs the same download-and-verify steps against the same
# release endpoint, then installs the binary to a system path.
set -euo pipefail

claude_command=${CLAUDE_COMMAND:-/usr/local/bin/claude}
claude_version=${CLAUDE_VERSION:-}
service_user=${REVIEWER_SERVICE_USER:-temporal-reviewer}
download_base_url=https://downloads.claude.ai/claude-code-releases

if [[ ${EUID} -ne 0 ]]; then
  echo "Run this installer as root on the Raspberry Pi." >&2
  exit 1
fi

# The JRE, not the JDK: the worker only ever runs `java -jar reviewer.jar`, so a
# compiler on a host reachable from the internet is avoidable attack surface.
# Building this repository on the Pi needs openjdk-21-jdk-headless as well;
# CI builds the reviewer image, so that is not normally required.
echo "==> Installing Java 21 (JRE)"
if [[ -x /usr/bin/java ]] && /usr/bin/java -version 2>&1 | grep -q '"21'; then
  echo "Java 21 already present at /usr/bin/java."
else
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq openjdk-21-jre-headless
fi

if [[ ! -x /usr/bin/java ]]; then
  echo "Java did not install to /usr/bin/java, which the systemd unit hardcodes." >&2
  exit 1
fi
if ! /usr/bin/java -version 2>&1 | grep -q '"21'; then
  echo "/usr/bin/java is not Java 21:" >&2
  /usr/bin/java -version >&2
  exit 1
fi

echo "==> Installing Claude Code to ${claude_command}"
case "$(uname -m)" in
  x86_64 | amd64) architecture=x64 ;;
  arm64 | aarch64) architecture=arm64 ;;
  *)
    echo "Unsupported architecture: $(uname -m)" >&2
    exit 1
    ;;
esac

if ldd /bin/ls 2>&1 | grep -q musl; then
  platform="linux-${architecture}-musl"
else
  platform="linux-${architecture}"
fi

if [[ -z ${claude_version} ]]; then
  claude_version=$(curl -fsSL "${download_base_url}/latest")
fi
if [[ ! ${claude_version} =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]]; then
  echo "Refusing to install unexpected version string: ${claude_version}" >&2
  exit 1
fi

manifest=$(curl -fsSL "${download_base_url}/${claude_version}/manifest.json")
if command -v jq >/dev/null 2>&1; then
  checksum=$(printf '%s' "${manifest}" | jq -r ".platforms[\"${platform}\"].checksum // empty")
else
  checksum=$(printf '%s' "${manifest}" |
    python3 -c 'import json,sys; print(json.load(sys.stdin)["platforms"].get(sys.argv[1],{}).get("checksum",""))' \
      "${platform}")
fi
if [[ ! ${checksum} =~ ^[a-f0-9]{64}$ ]]; then
  echo "No usable checksum for platform ${platform} in the ${claude_version} manifest." >&2
  exit 1
fi

download_directory=$(mktemp -d)
trap 'rm -rf "${download_directory}"' EXIT
downloaded_binary="${download_directory}/claude"

curl -fsSL -o "${downloaded_binary}" \
  "${download_base_url}/${claude_version}/${platform}/claude"

actual_checksum=$(sha256sum "${downloaded_binary}" | cut -d' ' -f1)
if [[ ${actual_checksum} != "${checksum}" ]]; then
  echo "Checksum verification failed for Claude Code ${claude_version} (${platform})." >&2
  echo "  expected ${checksum}" >&2
  echo "  actual   ${actual_checksum}" >&2
  exit 1
fi

# 0755 root-owned: every user can execute it, but the service account cannot
# overwrite it, so the reviewer's Claude version only changes when this script
# is re-run.
install -o root -g root -m 0755 "${downloaded_binary}" "${claude_command}"

echo "==> Verifying"
echo -n "java:   "
/usr/bin/java -version 2>&1 | head -1
echo -n "claude: "
"${claude_command}" --version

if id "${service_user}" >/dev/null 2>&1; then
  # The service account has a nologin shell, so exercise the binaries the way
  # systemd will rather than through a login shell.
  runuser -u "${service_user}" -- /usr/bin/java -version >/dev/null 2>&1 ||
    {
      echo "${service_user} cannot execute /usr/bin/java." >&2
      exit 1
    }
  runuser -u "${service_user}" -- "${claude_command}" --version >/dev/null 2>&1 ||
    {
      echo "${service_user} cannot execute ${claude_command}." >&2
      exit 1
    }
  echo "Service account ${service_user} can execute both."
else
  echo "Service account ${service_user} does not exist yet;"
  echo "install-reviewer-worker.sh creates it. Re-run this script afterwards to"
  echo "confirm it can execute both binaries."
fi

echo
echo "Host prerequisites are ready. Next: scripts/install-reviewer-worker.sh"
