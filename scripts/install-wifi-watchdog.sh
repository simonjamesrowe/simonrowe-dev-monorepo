#!/usr/bin/env bash
#
# Installs the Wi-Fi watchdog and the NetworkManager hardening that goes with it.
# Run once on the production host; re-running is safe and idempotent.
#
# Three separate things, each of which stands on its own:
#
#   1. NetworkManager defaults   - stop the radio sleeping, stop NM giving up.
#   2. The watchdog timer        - recover the link without a human.
#   3. A persistent journal      - so the NEXT occurrence leaves evidence.
#
# (3) is here rather than in its own script because of how the 2026-09-18 outage
# ended: the Pi was power-cycled to recover it, and with Storage=auto and an
# empty /var/log/journal the journal lived in /run and the reboot destroyed every
# log line explaining why the Wi-Fi never came back. The fix and the ability to
# confirm the fix worked are the same change.
#
# A systemd timer rather than cron - unlike monitor-prod.sh, which runs as the
# login user and only needs the docker group - because every action this takes
# (nmcli, systemctl, modprobe, reboot) needs root, and a root job with its own
# unit is easier to inspect than a line in root's crontab.
#
# The watchdog is COPIED to /usr/local/sbin rather than run from the checkout,
# which is the one place this departs from monitor-prod.sh. Two reasons, and the
# first is the important one: a root unit executing a file inside a checkout the
# login user can write is a privilege escalation waiting to be noticed. The
# second is availability - the deploy rewrites that directory, and the minutes
# during which the network could be wedged are exactly the minutes a deploy is
# most likely to be in flight.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE_SCRIPT="$PROJECT_DIR/scripts/wifi-watchdog.sh"
WATCHDOG_SCRIPT="/usr/local/sbin/wifi-watchdog.sh"

LOG_DIR="/var/log/wifi-watchdog"
LOG_FILE="$LOG_DIR/watchdog.log"
LOGROTATE_FILE="/etc/logrotate.d/wifi-watchdog"
NM_CONF="/etc/NetworkManager/conf.d/10-wifi-resilience.conf"
SERVICE_UNIT="/etc/systemd/system/wifi-watchdog.service"
TIMER_UNIT="/etc/systemd/system/wifi-watchdog.timer"
JOURNAL_DIR="/var/log/journal"

if [[ ! -r "$SOURCE_SCRIPT" ]]; then
  echo "ERROR: watchdog script is missing: $SOURCE_SCRIPT"
  exit 1
fi

echo "0/4 Installing the watchdog to $WATCHDOG_SCRIPT..."
sudo install -o root -g root -m 0755 "$SOURCE_SCRIPT" "$WATCHDOG_SCRIPT"

echo "1/4 Installing NetworkManager defaults..."
# Two settings and one guard, all of them global defaults rather than edits to
# the connection profile: netplan owns the profiles on this host (they are
# regenerated into /etc/netplan/90-NM-<uuid>.yaml), so a profile edit is the one
# that can be silently reverted.
#
#   connection.autoconnect-retries  NM's default is 4. After four failed
#       autoconnect attempts it blocks the profile and stops trying on its own.
#       An AP away for more than a couple of minutes therefore burns the budget
#       immediately and is never reconnected to. 0 = forever.
#       NOTE: this key is NOT accepted in NetworkManager.conf's [connection]
#       section on 1.52 - it warns "unknown key" and carries on - so it is set
#       on the profile by this script instead, below. The comment stays here
#       because the next person will reach for the config file first.
#
#   wifi.powersave=2 (disable)      The brcmfmac radio ships with power save ON.
#       On an always-on server it buys nothing and it is the best-documented
#       cause of a Pi that does not notice its AP has come back.
#
#   unmanaged-devices               Docker's bridges and veths show as
#       "connected (externally)" to NM. The watchdog restarts NetworkManager at
#       rung 3, so telling NM explicitly to keep its hands off them turns an
#       assumption about NM's behaviour into a written-down constraint.
sudo tee "$NM_CONF" >/dev/null <<'EOF'
# Managed by simonrowe-dev-monorepo: scripts/install-wifi-watchdog.sh
# Do not hand-edit. netplan owns the connection profiles; this owns the defaults.

[connection-wifi-powersave]
match-device=type:wifi
# 2 = disable. The brcmfmac radio otherwise sleeps and can miss the AP returning.
wifi.powersave=2

[keyfile]
# Docker's interfaces are NM's business only to the extent of leaving them alone.
unmanaged-devices=interface-name:docker*;interface-name:veth*;interface-name:br-*
EOF

sudo systemctl reload NetworkManager

# Set on the profile, because the equivalent global default is not a supported
# key (see above). Applied to every Wi-Fi profile NM knows about so a future
# network change does not quietly reinstate the limit of 4.
while IFS=: read -r name type; do
  [[ "$type" == "802-11-wireless" ]] || continue
  echo "    setting autoconnect-retries=0 on '$name'"
  sudo nmcli connection modify "$name" connection.autoconnect-retries 0
done < <(nmcli -t -f NAME,TYPE connection show)

echo "2/4 Creating log directory and rotation..."
sudo mkdir -p "$LOG_DIR"
sudo touch "$LOG_FILE"
sudo tee "$LOGROTATE_FILE" >/dev/null <<EOF
$LOG_FILE {
    weekly
    rotate 8
    compress
    missingok
    notifempty
    copytruncate
}
EOF

echo "3/4 Installing the systemd timer..."
sudo tee "$SERVICE_UNIT" >/dev/null <<EOF
[Unit]
Description=Recover the Wi-Fi link if it stops carrying traffic
Documentation=https://github.com/simonjamesrowe/simonrowe-dev-monorepo/blob/main/docs/runbooks/wifi-resilience.md

[Service]
Type=oneshot
ExecStart=/bin/sh -c '$WATCHDOG_SCRIPT >> $LOG_FILE 2>&1'
EOF

# OnBootSec is deliberately generous: the boot this most often follows is a cold
# start of 21 containers, and a watchdog that fires while the machine is still
# associating would spend two rungs on a link that was coming up anyway.
sudo tee "$TIMER_UNIT" >/dev/null <<'EOF'
[Unit]
Description=Run the Wi-Fi watchdog every minute

[Timer]
OnBootSec=3min
OnUnitActiveSec=1min
AccuracySec=10s
Unit=wifi-watchdog.service

[Install]
WantedBy=timers.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now wifi-watchdog.timer

echo "4/4 Making the journal persistent..."
# Capped, because this host runs at ~83% disk and an uncapped journal is the
# kind of fix that becomes the next outage.
if [[ ! -d "$JOURNAL_DIR" ]]; then
  sudo mkdir -p "$JOURNAL_DIR"
fi
sudo mkdir -p /etc/systemd/journald.conf.d
# THE FILENAME IS LOAD-BEARING. Raspberry Pi OS ships
# /usr/lib/systemd/journald.conf.d/40-rpi-volatile-storage.conf (package
# raspberrypi-sys-mods) containing Storage=volatile. Drop-ins are merged by
# filename across /etc and /usr/lib and applied in lexical order, so a
# 10-prefixed file in /etc loses to the vendor's 40- one - it is read, it is
# reported by `systemd-analyze cat-config`, and it has no effect. That was the
# first attempt here and it silently did nothing.
#
# `Storage=volatile` is a sensible default for a Pi booting from an SD card. This
# one boots from a 117G USB SSD, so the wear argument it exists for does not
# apply, and losing the journal on every reboot cost us the entire explanation
# for the 2026-09-18 outage.
sudo rm -f /etc/systemd/journald.conf.d/10-persistent.conf
sudo tee /etc/systemd/journald.conf.d/95-persistent-journal.conf >/dev/null <<'EOF'
# Managed by simonrowe-dev-monorepo: scripts/install-wifi-watchdog.sh
#
# Overrides /usr/lib/systemd/journald.conf.d/40-rpi-volatile-storage.conf, which
# sets Storage=volatile. The 95- prefix is what makes this win; do not renumber.
[Journal]
Storage=persistent
SystemMaxUse=300M
MaxRetentionSec=1month
EOF
sudo systemctl restart systemd-journald
sudo journalctl --flush >/dev/null 2>&1 || true

# Assert rather than assume: the failure mode above is silent, and a journal that
# is not persistent is only discovered by the reboot that needed it.
if sudo journalctl --header 2>/dev/null | grep -q 'File path: /run/log/journal'; then
  echo "    WARNING: the journal is still volatile. Check for a drop-in sorting"
  echo "             after 95-persistent-journal.conf:"
  echo "             systemd-analyze cat-config systemd/journald.conf | grep -E '^# /|Storage='"
else
  echo "    journal is persistent under $JOURNAL_DIR"
fi

echo
echo "Installed. Verifying with a dry run:"
sudo env DRY_RUN=1 STATE_DIR="$(mktemp -d)" "$WATCHDOG_SCRIPT"
echo
sudo systemctl list-timers wifi-watchdog.timer --no-pager
echo
echo "Wi-Fi watchdog installed. Log: $LOG_FILE"
