#!/usr/bin/env bash
set -euo pipefail

# Pin the upstream DNS servers Docker's embedded resolver forwards to, on the
# production Raspberry Pi.
#
# WHY
# ---
# Every container here sits on the compose project's user-defined network, so its
# /etc/resolv.conf says `nameserver 127.0.0.11` - Docker's embedded resolver. That
# resolver answers compose service names itself and forwards everything else to
# a list of upstream servers, and with no `dns` in daemon.json that list is copied
# from the HOST's /etc/resolv.conf AT THE MOMENT THE CONTAINER STARTS. It is never
# re-read. A container started while the host had no nameservers keeps an empty
# upstream list for the rest of its life: service names still resolve, so every
# healthcheck stays green, and every external name returns SERVFAIL.
#
# That is exactly what happened on 2026-09-24. The Wi-Fi dropped at ~19:48Z, the
# wifi-watchdog's reboot rung fired at 20:18Z, and Docker started the stack at
# 20:20Z with the link still down - no DHCP lease, so no nameservers. The link
# came back at 06:45Z the next morning; monitor-prod.sh restarted pinggy, backend
# and temporal-ui to bring the site back, and those three picked up DNS. The
# other twenty containers could not resolve a single external name for a further
# ~23 hours, all reporting `healthy`:
#
#   - Dependency-Track could not fetch Auth0's discovery document, so
#     /api/v1/oidc/available returned `false` and the "Login with Auth0" button
#     silently disappeared (how it was noticed).
#   - alloy could not resolve Grafana Cloud, so Loki received NOTHING.
#   - software-factory could reach neither Loki nor Linear, so the log watch that
#     exists to notice outages could neither see this one nor report it.
#
# `docker inspect`'s resolv.conf shows it: a healthy container carries
#   # ExtServers: [host(194.168.4.100) host(194.168.8.100)]
# and every affected one had no ExtServers line at all.
#
# Pinning `dns` in daemon.json makes the upstream list a fixed property of the
# daemon rather than a snapshot of whatever the host had during boot, so a
# container started while offline resolves normally the moment the link returns.
# That is a constraint that removes the failure, rather than a check that has to
# notice it - monitor-prod.sh's DNS layer is the backstop, not the fix.
#
# WHY NOT `dns:` IN THE COMPOSE FILE
# ----------------------------------
# The same reason rotation lives in daemon.json (see enable-docker-log-rotation.sh):
# `dns:` changes every service's `docker compose config --hash`, sync-config
# compares those against the nine-service FACTORY_DEPLOY_RECREATABLE allowlist,
# and a change touching all 23 services declines as `held-back` and freezes the
# deploy directory, self-perpetuatingly. daemon.json changes no service hash.
#
# WHICH SERVERS
# -------------
# Public resolvers by default, not the ISP's (194.168.4.100/194.168.8.100 today).
# Those are handed out by DHCP and belong to whichever network the Pi is on; a
# pinned copy goes stale silently if the router or ISP changes, and a stale pin
# would reproduce this outage permanently. 1.1.1.1 and 8.8.8.8 are what
# wifi-watchdog.sh already probes by IP. Override with DOCKER_DNS.
#
# WHAT THIS DOES
# --------------
# Merges "dns": [...] into /etc/docker/daemon.json, preserving everything else
# (notably the log rotation settings). It does NOT restart the Docker daemon:
# that restarts every container, a full-stack cold start (~8 minutes to all
# hostnames serving on this Pi). Schedule it. `dns` is not a live-reloadable
# daemon option, and a container only adopts it on its next start, so the
# restart is also what cures any container already stuck without upstreams.

DAEMON_JSON=${DAEMON_JSON:-/etc/docker/daemon.json}
DOCKER_DNS=${DOCKER_DNS:-"1.1.1.1 8.8.8.8"}

# `${SUDO-sudo}`, NOT `${SUDO:-sudo}`: the colon form treats an explicitly empty
# value as unset, so the tests' `SUDO=` would still expand to `sudo` and hang on
# a password prompt. Same convention as enable-docker-log-rotation.sh.
SUDO=${SUDO-sudo}

usage() {
  cat <<EOF
Usage: $0 [--verify|--apply|--revert]

  --verify   Report whether upstream DNS is pinned, and which running containers
             have no upstream servers at all (default).
  --apply    Merge "dns": [$DOCKER_DNS] into $DAEMON_JSON. Takes effect after a
             Docker daemon restart.
  --revert   Remove the dns setting.
EOF
}

# The Pi has no jq - it exists only inside the deployer image - so daemon.json is
# read and written with python3, which Raspberry Pi OS ships.
require_python() {
  if ! command -v python3 >/dev/null 2>&1; then
    echo "ERROR: python3 not found; cannot read or write $DAEMON_JSON safely." >&2
    exit 1
  fi
}

# True when daemon.json exists and carries a non-empty dns list.
dns_configured() {
  [[ -f "$DAEMON_JSON" ]] && python3 - "$DAEMON_JSON" <<'PY'
import json, sys
try:
    with open(sys.argv[1]) as fh:
        cfg = json.load(fh)
except Exception:
    sys.exit(1)
dns = cfg.get("dns")
sys.exit(0 if isinstance(dns, list) and dns else 1)
PY
}

# The daemon setting is only half the story: a container keeps the upstream list
# it was started with, so the honest check is per container. Docker records that
# list as an `# ExtServers:` comment in the resolv.conf it generates; a container
# whose file has no such line has nowhere to forward external names to.
report_containers() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "  (docker not on PATH - skipping per-container check)"
    return
  fi
  local missing=0 total=0 name path servers
  while read -r name; do
    [[ -z "$name" ]] && continue
    path="$(docker inspect -f '{{.ResolvConfPath}}' "$name" 2>/dev/null || true)"
    # Host-network containers have no generated file and use the host's resolver.
    [[ -z "$path" ]] && continue
    total=$((total + 1))
    servers="$($SUDO grep -h '^# ExtServers:' "$path" 2>/dev/null || true)"
    if [[ -z "$servers" ]]; then
      missing=$((missing + 1))
      echo "  NO UPSTREAM: $name"
    fi
  done < <(docker ps --format '{{.Names}}')
  echo "  $missing of $total running containers have no upstream DNS servers."
  if [[ "$missing" -gt 0 ]]; then
    echo "  Those cannot resolve any external name until they are restarted."
  fi
}

do_verify() {
  echo "daemon config  : $DAEMON_JSON"
  if [[ -f "$DAEMON_JSON" ]]; then
    echo "contents       :"
    sed 's/^/  /' "$DAEMON_JSON"
  else
    echo "contents       : (file does not exist)"
  fi
  echo
  report_containers
  echo
  if dns_configured; then
    echo "RESULT: upstream DNS IS pinned for containers started after the last daemon restart."
    return 0
  fi
  echo "RESULT: upstream DNS is NOT pinned - a container started while the host has no"
  echo "        nameservers (e.g. during boot with the Wi-Fi down) cannot resolve external"
  echo "        names until it is restarted."
  echo "NOTE: run '$0 --apply', then restart Docker in a maintenance window."
  return 1
}

do_apply() {
  require_python

  if [[ -f "$DAEMON_JSON" ]]; then
    local backup
    backup="${DAEMON_JSON}.bak.$(date +%Y%m%d%H%M%S)"
    echo "Backing up $DAEMON_JSON -> $backup"
    $SUDO cp -a "$DAEMON_JSON" "$backup"
  fi

  # Merge rather than overwrite: daemon.json already carries the log rotation
  # settings, and a daemon that loses or cannot parse its config on restart
  # brings back none of the 23 containers.
  local rendered
  # shellcheck disable=SC2086
  rendered="$(python3 - "$DAEMON_JSON" $DOCKER_DNS <<'PY'
import ipaddress, json, os, sys
path, servers = sys.argv[1], sys.argv[2:]
if not servers:
    print("  DOCKER_DNS is empty", file=sys.stderr)
    sys.exit(1)
for s in servers:
    # dockerd refuses to start on an entry that is not an IP address, which on
    # this host means no container comes back. Refuse here instead.
    try:
        ipaddress.ip_address(s)
    except ValueError:
        print("  not an IP address: %r" % s, file=sys.stderr)
        sys.exit(1)
cfg = {}
if os.path.exists(path):
    with open(path) as fh:
        text = fh.read().strip()
    if text:
        try:
            cfg = json.loads(text)
        except ValueError as exc:
            print("  parse error: %s" % exc, file=sys.stderr)
            sys.exit(1)
cfg["dns"] = servers
print(json.dumps(cfg, indent=2))
PY
)" || {
    echo "ERROR: refusing to write $DAEMON_JSON (see above). It is unchanged." >&2
    exit 1
  }

  $SUDO mkdir -p "$(dirname "$DAEMON_JSON")"
  printf '%s\n' "$rendered" | $SUDO tee "$DAEMON_JSON" >/dev/null

  echo
  echo "Wrote $DAEMON_JSON:"
  sed 's/^/  /' "$DAEMON_JSON"
  echo
  echo "NEXT - in a planned maintenance window, because this restarts every container:"
  echo "  sudo systemctl restart docker"
  echo
  echo "Then confirm every container picked up the pinned servers, and that the stack"
  echo "is actually serving - a green 'ps' is not proof:"
  echo "  $0 --verify"
  echo "  for h in www.simonrowe.dev api.simonrowe.dev console.simonrowe.dev \\"
  echo "           langfuse.simonrowe.dev temporal.simonrowe.dev dependency-track.simonrowe.dev; do"
  echo "    printf '%s %s\\n' \"\$h\" \"\$(curl -s -o /dev/null -w '%{http_code}' https://\$h)\"; done"
  echo
  echo "To undo: $0 --revert"
}

do_revert() {
  require_python

  if ! dns_configured; then
    echo "$DAEMON_JSON does not pin dns; nothing to revert."
    return 0
  fi

  local rendered
  rendered="$(python3 - "$DAEMON_JSON" <<'PY'
import json, sys
with open(sys.argv[1]) as fh:
    cfg = json.load(fh)
cfg.pop("dns", None)
print(json.dumps(cfg, indent=2))
PY
)"
  printf '%s\n' "$rendered" | $SUDO tee "$DAEMON_JSON" >/dev/null
  echo "Reverted. Current contents:"
  sed 's/^/  /' "$DAEMON_JSON"
  echo
  echo "Restart Docker in a maintenance window for this to take effect."
}

case "${1:---verify}" in
  --verify) do_verify ;;
  --apply)  do_apply ;;
  --revert) do_revert ;;
  -h|--help) usage ;;
  *) usage; exit 1 ;;
esac
