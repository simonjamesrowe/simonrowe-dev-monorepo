#!/usr/bin/env bash
set -euo pipefail

# Enable the kernel memory cgroup controller on the production Raspberry Pi.
#
# WHY
# ---
# `docker info` on the Pi reports:
#
#     WARNING: No memory limit support
#     WARNING: No swap limit support
#
# and /sys/fs/cgroup/cgroup.controllers lists only `cpuset cpu io pids` - no
# `memory`. The Raspberry Pi *firmware* prepends `cgroup_disable=memory` to the
# kernel command line (it is not in cmdline.txt or config.txt, which is why
# grepping /boot for it finds nothing while /proc/cmdline shows it).
#
# Two consequences, both bad for a box running ~21 containers:
#
#   1. Every `mem_limit:` in docker-compose.prod.yml is silently ignored. Docker
#      accepts the value and `docker stats` reports `0B / 0B`. Nothing is capped,
#      so one runaway container can drive the whole host into swap and take down
#      every service - there is no blast-radius containment at all.
#
#   2. JVMs size their heap from the *host* total (15.84GiB) instead of their
#      container limit. Dependency-Track's image sets -XX:MaxRAMPercentage=80.0,
#      so it will happily grow a ~12.7GiB heap on a 15.84GiB machine despite
#      declaring `mem_limit: 2g`. The backend has no explicit -Xmx either.
#      Enabling the controller is what makes those declared limits real, and
#      makes the JVMs size themselves correctly, without touching any heap flags.
#
# WHAT THIS DOES
# --------------
# Appends `cgroup_enable=memory cgroup_memory=1` to /boot/firmware/cmdline.txt.
# The kernel parses its command line left to right and the firmware's
# `cgroup_disable=memory` is prepended, so a later explicit enable wins.
#
# THIS REQUIRES A REBOOT. The script deliberately does NOT reboot: on this host a
# reboot means a full-stack cold start, which is exactly the event that broke
# Langfuse and Dependency-Track on 2026-08-14. Schedule it, then verify with
# `--verify` afterwards.
#
# Cost of enabling: the memory controller adds roughly 1-2% RAM overhead for page
# accounting. That is a good trade for actually-enforced limits.

CMDLINE_FILE=${CMDLINE_FILE:-/boot/firmware/cmdline.txt}
PARAMS="cgroup_enable=memory cgroup_memory=1"

usage() {
  cat <<EOF
Usage: $0 [--apply|--verify|--revert]

  --verify   Report whether the memory controller is active (default).
  --apply    Add the kernel parameters. Requires a reboot to take effect.
  --revert   Remove the parameters added by --apply.
EOF
}

controller_active() {
  [[ -r /sys/fs/cgroup/cgroup.controllers ]] &&
    grep -qw memory /sys/fs/cgroup/cgroup.controllers
}

do_verify() {
  echo "kernel cmdline : $(tr ' ' '\n' < /proc/cmdline | grep -E '^cgroup' | tr '\n' ' ')"
  echo "controllers    : $(cat /sys/fs/cgroup/cgroup.controllers 2>/dev/null || echo unknown)"
  if controller_active; then
    echo "RESULT: memory cgroup is ACTIVE - mem_limit values are enforced."
    echo
    echo "Confirm Docker agrees (both warnings should now be gone):"
    echo "  docker info 2>&1 | grep -i 'WARNING.*memory\\|WARNING.*swap' || echo 'no memory warnings'"
    echo "Confirm accounting works (must not be 0B / 0B):"
    echo "  docker stats --no-stream --format '{{.Name}} {{.MemUsage}}'"
    return 0
  fi
  echo "RESULT: memory cgroup is NOT active - every mem_limit in the compose file is ignored."
  if grep -q "cgroup_enable=memory" "$CMDLINE_FILE" 2>/dev/null; then
    echo "NOTE: $CMDLINE_FILE already requests it, so a REBOOT is still pending."
  else
    echo "NOTE: run '$0 --apply', then reboot at a planned time."
  fi
  return 1
}

do_apply() {
  if controller_active; then
    echo "Memory cgroup is already active; nothing to do."
    return 0
  fi

  if [[ ! -f "$CMDLINE_FILE" ]]; then
    echo "ERROR: $CMDLINE_FILE not found. Is this the Raspberry Pi host?" >&2
    exit 1
  fi

  if grep -q "cgroup_enable=memory" "$CMDLINE_FILE"; then
    echo "$CMDLINE_FILE already contains the parameters - a reboot is still pending."
    return 0
  fi

  local backup
  backup="${CMDLINE_FILE}.bak.$(date +%Y%m%d%H%M%S)"
  echo "Backing up $CMDLINE_FILE -> $backup"
  sudo cp -a "$CMDLINE_FILE" "$backup"

  # cmdline.txt MUST stay a single line - the bootloader ignores anything after
  # the first newline, so appending a second line would silently drop the
  # parameters (and could drop the rest of the boot args). Rewrite the first
  # line in place with the params appended, preserving nothing else.
  local current
  current="$(head -n 1 "$CMDLINE_FILE" | tr -d '\n')"
  printf '%s %s\n' "$current" "$PARAMS" | sudo tee "$CMDLINE_FILE" >/dev/null

  echo
  echo "Updated $CMDLINE_FILE:"
  cat "$CMDLINE_FILE"
  echo
  echo "Sanity check - the file must be exactly one line: $(wc -l < "$CMDLINE_FILE") newline(s)"
  echo
  echo "NEXT: reboot at a planned time, then run '$0 --verify'."
  echo "After the reboot, confirm the stack came back cleanly - a cold start is the"
  echo "riskiest moment for this host:"
  echo "  docker compose -f docker-compose.prod.yml ps"
  echo "  ./scripts/monitor-prod.sh   # or wait for the once-a-minute cron tick"
  echo
  echo "To undo before rebooting: $0 --revert"
}

do_revert() {
  if ! grep -q "cgroup_enable=memory" "$CMDLINE_FILE" 2>/dev/null; then
    echo "Parameters not present in $CMDLINE_FILE; nothing to revert."
    return 0
  fi
  local current
  current="$(head -n 1 "$CMDLINE_FILE" | tr -d '\n')"
  current="${current// cgroup_enable=memory/}"
  current="${current// cgroup_memory=1/}"
  printf '%s\n' "$current" | sudo tee "$CMDLINE_FILE" >/dev/null
  echo "Reverted. Current contents:"
  cat "$CMDLINE_FILE"
}

case "${1:---verify}" in
  --verify) do_verify ;;
  --apply)  do_apply ;;
  --revert) do_revert ;;
  -h|--help) usage ;;
  *) usage; exit 1 ;;
esac
