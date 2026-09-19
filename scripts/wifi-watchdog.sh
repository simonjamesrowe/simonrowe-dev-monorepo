#!/usr/bin/env bash
#
# Wi-Fi watchdog for the production Raspberry Pi. Installed by
# scripts/install-wifi-watchdog.sh as a root systemd timer firing every minute,
# logging to /var/log/wifi-watchdog/watchdog.log.
#
# WHY THIS EXISTS
#   On 2026-09-18 the access point was unplugged at ~09:05 UTC. It came back the
#   same day; the Pi did not. The whole site was dark for 23.5 hours and recovery
#   needed a human to power-cycle the machine. Nothing on the box tried to
#   reconnect in the meantime and nothing recorded why, because the journal is
#   volatile and the boot wiped it.
#
#   monitor-prod.sh cannot cover this. It watches containers, and every one of
#   them was running perfectly - the fault was one layer below everything it can
#   see, on the link its own reporting depends on.
#
# THE PROPERTY THAT MATTERS: IT NEVER GIVES UP.
#   The failure this exists for is an AP that is away for hours and then returns.
#   Anything that stops retrying - NetworkManager's own autoconnect-retries
#   default of 4 among them - has already failed by the time the AP is back. So
#   past the last rung the ladder cycles the cheap actions forever, at a slower
#   cadence, rather than escalating or stopping.
#
# ESCALATION
#   Rungs are keyed on CONSECUTIVE failed checks, i.e. roughly minutes of total
#   loss of the link. Cheapest first; each rung's failure mode is covered by the
#   one below it, which is why the driver reload is safe to attempt at all - if
#   the module does not come back, the reboot rung collects it.
#
#     3    re-activate the Wi-Fi profile      (also clears an NM autoconnect block)
#     6    bounce the device
#    10    restart NetworkManager
#    20    reload the Wi-Fi driver
#    30    reboot                              (rate limited, see below)
#    >30   repeat the first three rungs every REPEAT_EVERY checks, forever
#
# REBOOT IS RATE LIMITED AND THAT IS NOT CAUTION FOR ITS OWN SAKE.
#   If the AP is genuinely absent - the actual 2026-09-18 case, a whole working
#   day - rebooting achieves nothing and costs a full stack cold start every
#   time. Measured on 2026-09-19: ~8 minutes from power-on to all six public
#   hostnames serving, during which the backend crash-loops against an
#   Elasticsearch that has not yet bound 9200. One reboot per REBOOT_MIN_INTERVAL
#   buys the one case a reboot actually fixes (a wedged radio) without turning a
#   neighbour's holiday into a reboot loop.
#
# PROBING IS BY IP, NEVER BY NAME.
#   A DNS failure is a different fault with a different fix, and resolving a name
#   here would have the watchdog bouncing the radio because a nameserver was
#   slow. The default gateway is probed first because it is the thing being
#   recovered - association with the AP - and the two public addresses are the
#   tiebreak for a gateway that simply does not answer ICMP.
set -uo pipefail

CHECK_HOSTS=${CHECK_HOSTS:-"1.1.1.1 8.8.8.8"}
PING_TIMEOUT=${PING_TIMEOUT:-3}
PING_COUNT=${PING_COUNT:-2}

WIFI_DEVICE=${WIFI_DEVICE:-wlan0}
# Resolved from the device when unset, so this script carries no SSID and works
# unchanged if the network is ever renamed.
WIFI_PROFILE=${WIFI_PROFILE:-}
WIFI_MODULES=${WIFI_MODULES:-"brcmfmac_cyw brcmfmac"}

REACTIVATE_AFTER=${REACTIVATE_AFTER:-3}
BOUNCE_DEVICE_AFTER=${BOUNCE_DEVICE_AFTER:-6}
RESTART_NM_AFTER=${RESTART_NM_AFTER:-10}
RELOAD_DRIVER_AFTER=${RELOAD_DRIVER_AFTER:-20}
REBOOT_AFTER=${REBOOT_AFTER:-30}
REPEAT_EVERY=${REPEAT_EVERY:-5}
REBOOT_MIN_INTERVAL=${REBOOT_MIN_INTERVAL:-21600}

STATE_DIR=${STATE_DIR:-/var/lib/wifi-watchdog}
# DRY_RUN=1 reports what it would do and touches nothing. Use it to validate a
# change: every rung here either bounces the radio, restarts NetworkManager or
# reboots the host, so running this script "to see what it says" takes the site
# off the internet.
DRY_RUN=${DRY_RUN:-0}

FAILURES_FILE="$STATE_DIR/consecutive-failures"
LAST_REBOOT_FILE="$STATE_DIR/last-reboot"

log() {
  printf '%s [wifi-watchdog] %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"
}

# Every state-mutating action goes through here, so DRY_RUN cannot be forgotten
# at a call site and the log reads the same in both modes.
act() {
  local description="$1"
  shift
  if [[ "$DRY_RUN" == "1" ]]; then
    log "DRY RUN: would $description ($*)"
    return 0
  fi
  log "$description ($*)"
  "$@"
}

read_counter() {
  local file="$1" value
  value="$(cat "$file" 2>/dev/null || true)"
  [[ "$value" =~ ^[0-9]+$ ]] || value=0
  printf '%s' "$value"
}

write_state() {
  local file="$1" value="$2"
  [[ "$DRY_RUN" == "1" ]] && return 0
  printf '%s\n' "$value" >"$file" 2>/dev/null || true
}

default_gateway() {
  ip route 2>/dev/null | awk '/^default/ { print $3; exit }'
}

wifi_profile() {
  if [[ -n "$WIFI_PROFILE" ]]; then
    printf '%s' "$WIFI_PROFILE"
    return 0
  fi
  # -t -f keeps this parseable when the profile name contains spaces, which the
  # netplan-generated names ("netplan-wlan0-<ssid>") can.
  nmcli -t -f DEVICE,CONNECTION device status 2>/dev/null \
    | awk -F: -v dev="$WIFI_DEVICE" '$1 == dev { print $2; exit }'
}

# Any successful probe means the link is fine. The gateway is tried first and
# alone in the common case, so a healthy minute costs one ping.
link_is_up() {
  local host gateway
  gateway="$(default_gateway)"
  for host in $gateway $CHECK_HOSTS; do
    [[ -z "$host" ]] && continue
    if ping -c "$PING_COUNT" -W "$PING_TIMEOUT" -n "$host" >/dev/null 2>&1; then
      return 0
    fi
  done
  return 1
}

reactivate_profile() {
  local profile
  profile="$(wifi_profile)"
  if [[ -z "$profile" ]]; then
    log "no connection profile found for $WIFI_DEVICE; skipping re-activation"
    return 1
  fi
  act "re-activate Wi-Fi profile" nmcli connection up "$profile"
}

bounce_device() {
  act "disconnect $WIFI_DEVICE" nmcli device disconnect "$WIFI_DEVICE"
  act "connect $WIFI_DEVICE" nmcli device connect "$WIFI_DEVICE"
}

restart_network_manager() {
  act "restart NetworkManager" systemctl restart NetworkManager
}

# NetworkManager holds the interface, so the module cannot be removed underneath
# it. Stopping NM first is required, not tidiness - and it is why this rung is
# the one most likely to leave the host offline if the module does not come
# back. The reboot rung below is the recovery for exactly that.
reload_driver() {
  act "stop NetworkManager for driver reload" systemctl stop NetworkManager
  # shellcheck disable=SC2086
  act "unload Wi-Fi driver" modprobe -r $WIFI_MODULES
  # shellcheck disable=SC2086
  act "load Wi-Fi driver" modprobe $WIFI_MODULES
  act "start NetworkManager" systemctl start NetworkManager
}

reboot_host() {
  local now last elapsed
  now="$(date +%s)"
  last="$(read_counter "$LAST_REBOOT_FILE")"
  elapsed=$(( now - last ))
  if [[ "$last" -ne 0 && "$elapsed" -lt "$REBOOT_MIN_INTERVAL" ]]; then
    log "skipping reboot: last one was ${elapsed}s ago, minimum interval is ${REBOOT_MIN_INTERVAL}s"
    return 0
  fi
  write_state "$LAST_REBOOT_FILE" "$now"
  act "reboot the host" systemctl reboot
}

mkdir -p "$STATE_DIR" 2>/dev/null || true

if link_is_up; then
  previous="$(read_counter "$FAILURES_FILE")"
  if [[ "$previous" -gt 0 ]]; then
    log "link recovered after $previous consecutive failed checks"
  fi
  write_state "$FAILURES_FILE" 0
  exit 0
fi

failures=$(( $(read_counter "$FAILURES_FILE") + 1 ))
write_state "$FAILURES_FILE" "$failures"
log "no connectivity (consecutive failed checks: $failures)"

# Past the last rung the ladder restarts rather than stopping. `beyond` is the
# position within each repeat cycle, so the cheap actions keep being tried for as
# long as the outage lasts - which is what picks the link back up within a minute
# of an AP that has been away for a day coming back.
if [[ "$failures" -gt "$REBOOT_AFTER" ]]; then
  beyond=$(( (failures - REBOOT_AFTER) % REPEAT_EVERY ))
  case "$beyond" in
    1) reactivate_profile ;;
    2) bounce_device ;;
    3) restart_network_manager ;;
    *) log "waiting (repeat cycle position $beyond of $REPEAT_EVERY)" ;;
  esac
  exit 0
fi

case "$failures" in
  "$REACTIVATE_AFTER") reactivate_profile ;;
  "$BOUNCE_DEVICE_AFTER") bounce_device ;;
  "$RESTART_NM_AFTER") restart_network_manager ;;
  "$RELOAD_DRIVER_AFTER") reload_driver ;;
  "$REBOOT_AFTER") reboot_host ;;
  *) log "waiting for the next rung" ;;
esac
