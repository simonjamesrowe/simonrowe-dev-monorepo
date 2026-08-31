#!/usr/bin/env bash
set -euo pipefail

# Bound every container's json-file log on the production Raspberry Pi.
#
# WHY
# ---
# `docker inspect -f '{{.HostConfig.LogConfig}}' <any container>` on the Pi
# reports `json-file map[]` - the default driver with an EMPTY options map.
# There is no `logging:` block in docker-compose.prod.yml and no
# /etc/docker/daemon.json, so nothing anywhere sets max-size or max-file and
# every container log grows without limit for as long as that container lives.
# Measured 2026-08-31: mongodb's was 250 MB after 67 hours.
#
# Two costs, and the second is the one that actually bit:
#
#   1. Disk. 22 containers x unbounded, on a host that also stores Mongo,
#      Elasticsearch, ClickHouse and Trivy's 1.27GiB database.
#
#   2. Grafana Cloud ingest. Alloy's read cursors live in its --storage.path,
#      and alloy is in FACTORY_DEPLOY_RECREATABLE, so before the alloy-data
#      volume was added every deploy re-tailed each container FROM THE START.
#      Unbounded files are what made that re-read expensive rather than
#      merely wasteful: it re-shipped the whole history of the stack, every
#      deploy. August 2026 spent the entire 50 GB free-tier logs allowance on a
#      workload that generates 0.58 GB/month, after which the tenant's ingestion
#      limit became 0 bytes/sec and Loki held nothing for three weeks.
#
# The alloy-data volume is the primary fix for (2). Rotation is the backstop:
# it caps what any single misbehaving container can cost, whether the cost is
# disk or ingest. Both are wanted.
#
# WHY NOT A `logging:` BLOCK IN THE COMPOSE FILE
# ----------------------------------------------
# Because that is the tempting version and it wedges production. Adding
# `logging:` to a service changes its `docker compose config --hash`, and
# sync-config compares those hashes against FACTORY_DEPLOY_RECREATABLE - an
# ALLOWLIST of nine services. A compose change touching anything outside it
# makes sync-config decline as `held-back`, which freezes the deploy directory,
# and the decline is self-perpetuating because the comparison is
# host-checkout vs. target rather than previous-target vs. target. That is the
# wedge that stranded #130 through #136. Rotation has to apply to all 22
# containers, so the compose file is exactly the wrong place to put it.
#
# daemon.json is host configuration, changes no service hash, and applies to
# every container regardless of which compose file created it.
#
# WHAT THIS DOES
# --------------
# Writes /etc/docker/daemon.json with the json-file driver capped at
# MAX_SIZE x MAX_FILE per container. It deliberately does NOT restart the Docker
# daemon: `systemctl restart docker` on this host restarts all 22 containers,
# which is a full-stack cold start - the exact event that broke Langfuse and
# Dependency-Track on 2026-08-14. Schedule it.
#
# It also does not backfill. Log options are fixed at container CREATION, so
# running containers keep their current unbounded config until they are next
# recreated. Nothing breaks in the meantime; the cap simply arrives per
# container as the stack turns over.

DAEMON_JSON=${DAEMON_JSON:-/etc/docker/daemon.json}

# 20m x 5 = 100 MB per container, ~2.2 GB across 22 containers against 53 GB
# free. Five files rather than three so a burst does not evict the context you
# need to diagnose the burst.
MAX_SIZE=${MAX_SIZE:-20m}
MAX_FILE=${MAX_FILE:-5}

# Every write below goes through $SUDO so the tests can point DAEMON_JSON at a
# throwaway path and set SUDO= to write it directly. Defaulting to `sudo` keeps
# the real invocation on the host unchanged.
#
# `${SUDO-sudo}`, NOT `${SUDO:-sudo}`: the colon form treats an explicitly empty
# value as unset, so `SUDO= ` would still expand to `sudo` and every test would
# hang on a password prompt.
SUDO=${SUDO-sudo}

usage() {
  cat <<EOF
Usage: $0 [--verify|--apply|--revert]

  --verify   Report whether rotation is configured and which containers are
             still running unbounded (default).
  --apply    Write $DAEMON_JSON. Requires a Docker daemon restart to take
             effect, and container recreation to apply per container.
  --revert   Remove the rotation settings this script added.
EOF
}

# The Pi has no jq - it exists only inside the deployer image - so every read of
# daemon.json here goes through python3, which Raspberry Pi OS does ship.
require_python() {
  if ! command -v python3 >/dev/null 2>&1; then
    echo "ERROR: python3 not found; cannot read or write $DAEMON_JSON safely." >&2
    exit 1
  fi
}

# True when daemon.json exists and already carries both log-opts.
rotation_configured() {
  [[ -f "$DAEMON_JSON" ]] && python3 - "$DAEMON_JSON" <<'PY'
import json, sys
try:
    with open(sys.argv[1]) as fh:
        cfg = json.load(fh)
except Exception:
    sys.exit(1)
opts = cfg.get("log-opts") or {}
sys.exit(0 if opts.get("max-size") and opts.get("max-file") else 1)
PY
}

# Containers created before the change keep their own config, so the honest
# check is per container rather than "is the daemon configured".
report_unbounded() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "  (docker not on PATH - skipping per-container check)"
    return
  fi
  local unbounded=0 total=0 name opts
  while read -r name; do
    [[ -z "$name" ]] && continue
    total=$((total + 1))
    opts="$(docker inspect -f '{{.HostConfig.LogConfig.Config}}' "$name" 2>/dev/null || echo 'map[]')"
    if [[ "$opts" == "map[]" ]]; then
      unbounded=$((unbounded + 1))
      echo "  unbounded: $name"
    fi
  done < <(docker ps --format '{{.Names}}')
  echo "  $unbounded of $total running containers have no log cap."
  if [[ "$unbounded" -gt 0 ]]; then
    echo "  Those keep growing until they are next recreated - log options are"
    echo "  fixed at container creation and cannot be changed in place."
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
  report_unbounded
  echo
  if rotation_configured; then
    echo "RESULT: rotation IS configured for newly created containers."
    return 0
  fi
  echo "RESULT: no rotation configured - container logs grow without limit."
  echo "NOTE: run '$0 --apply', then restart Docker in a maintenance window."
  return 1
}

do_apply() {
  require_python

  if rotation_configured; then
    echo "$DAEMON_JSON already sets max-size and max-file; nothing to do."
    echo "If the daemon has not been restarted since, the settings are not live yet."
    return 0
  fi

  if [[ -f "$DAEMON_JSON" ]]; then
    local backup
    backup="${DAEMON_JSON}.bak.$(date +%Y%m%d%H%M%S)"
    echo "Backing up $DAEMON_JSON -> $backup"
    $SUDO cp -a "$DAEMON_JSON" "$backup"
  fi

  # Merge rather than overwrite. A daemon.json this script did not write may
  # carry registry mirrors, storage-driver or DNS settings, and clobbering those
  # would break the daemon on its next restart - at which point 22 containers do
  # not come back and the host is offline until someone finds the backup.
  local rendered
  rendered="$(python3 - "$DAEMON_JSON" "$MAX_SIZE" "$MAX_FILE" <<'PY'
import json, os, sys
path, max_size, max_file = sys.argv[1], sys.argv[2], sys.argv[3]
cfg = {}
if os.path.exists(path):
    with open(path) as fh:
        text = fh.read().strip()
    if text:
        # A malformed daemon.json means the daemon is already running on its
        # last-loaded config and a restart would fail. Refuse rather than
        # "repair" it by writing our own from scratch.
        #
        # Caught so the operator gets the parse position on one line. An
        # uncaught traceback would name a line number inside this heredoc,
        # which is not a file anyone can open.
        try:
            cfg = json.loads(text)
        except ValueError as exc:
            print("  parse error: %s" % exc, file=sys.stderr)
            sys.exit(1)
cfg.setdefault("log-driver", "json-file")
opts = cfg.setdefault("log-opts", {})
opts["max-size"] = max_size
# A STRING, deliberately. Docker rejects a JSON number here
# ("cannot unmarshal number into Go struct field") and then refuses to start.
opts["max-file"] = str(max_file)
print(json.dumps(cfg, indent=2))
PY
)" || {
    echo "ERROR: $DAEMON_JSON exists but is not valid JSON. Fix it by hand first -" >&2
    echo "       a daemon that cannot parse it will not start, and neither will the stack." >&2
    exit 1
  }

  $SUDO mkdir -p "$(dirname "$DAEMON_JSON")"
  printf '%s\n' "$rendered" | $SUDO tee "$DAEMON_JSON" >/dev/null

  echo
  echo "Wrote $DAEMON_JSON:"
  sed 's/^/  /' "$DAEMON_JSON"
  echo
  echo "NEXT - in a planned maintenance window, because this restarts all 22 containers:"
  echo "  sudo systemctl restart docker"
  echo
  echo "Then confirm the stack came back. A cold start is the riskiest moment for"
  echo "this host, and a green 'ps' is not proof a service is serving:"
  echo "  docker compose -f docker-compose.prod.yml ps"
  echo "  for h in simonrowe.dev api.simonrowe.dev console.simonrowe.dev \\"
  echo "           langfuse.simonrowe.dev temporal.simonrowe.dev dependency-track.simonrowe.dev; do"
  echo "    printf '%s %s\\n' \"\$h\" \"\$(curl -s -o /dev/null -w '%{http_code}' https://\$h)\"; done"
  echo
  echo "The cap applies per container at CREATION, so existing containers stay"
  echo "uncapped until recreated. Check progress any time with: $0 --verify"
  echo
  echo "To undo: $0 --revert"
}

do_revert() {
  require_python

  if [[ ! -f "$DAEMON_JSON" ]]; then
    echo "$DAEMON_JSON does not exist; nothing to revert."
    return 0
  fi
  if ! rotation_configured; then
    echo "$DAEMON_JSON does not set rotation; nothing to revert."
    return 0
  fi

  local rendered
  rendered="$(python3 - "$DAEMON_JSON" <<'PY'
import json, sys
with open(sys.argv[1]) as fh:
    cfg = json.load(fh)
opts = cfg.get("log-opts") or {}
opts.pop("max-size", None)
opts.pop("max-file", None)
# Leave an empty log-opts out entirely, and drop log-driver only if it is the
# default we added - another operator may have set it deliberately.
if not opts:
    cfg.pop("log-opts", None)
if cfg.get("log-driver") == "json-file" and "log-opts" not in cfg:
    cfg.pop("log-driver", None)
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
