#!/usr/bin/env bash
#
# Exercises scripts/wifi-watchdog.sh's escalation ladder against stubbed
# nmcli/systemctl/modprobe/ping binaries on PATH.
#
# WHY THIS EXISTS
#   Every rung of this ladder either bounces the radio, restarts NetworkManager
#   or reboots the host, and the machine it runs on is reachable only over the
#   link it is bouncing. There is no way to try it in production and no way to
#   partially try it: the first honest test of rung 5 takes the site offline for
#   the eight minutes a cold start costs. So the ladder is verified here, with
#   the real script and fake tools.
#
#   The property worth protecting is the LAST one asserted below - that past the
#   final rung the watchdog keeps trying forever. An off-by-one in that branch
#   would leave a watchdog that looks completely correct in every test of the
#   first thirty minutes and then does nothing at all for the case it was
#   written for: an access point that is away for a working day and comes back.
#
# A shell test rather than a Java one because the subject is a host script owned
# by neither Gradle module, as with its neighbours here.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
WATCHDOG="$PROJECT_DIR/scripts/wifi-watchdog.sh"

failures=0
checks=0

pass() { checks=$((checks + 1)); echo "    ok: $1"; }
fail() { checks=$((checks + 1)); failures=$((failures + 1)); echo "    FAIL: $1"; }

check() {
  local description="$1" condition="$2"
  if eval "$condition"; then pass "$description"; else fail "$description"; fi
}

echo "  Subject: $WATCHDOG"

if [[ ! -x "$WATCHDOG" ]]; then
  fail "watchdog script exists and is executable"
  echo "  checks: $checks  failures: $failures"
  exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

BIN_DIR="$WORK_DIR/bin"
STATE_DIR="$WORK_DIR/state"
CALLS="$WORK_DIR/calls"
LINK_STATE="$WORK_DIR/link"
mkdir -p "$BIN_DIR" "$STATE_DIR"

# ping is the only stub with behaviour: it reads the link state the test sets.
cat >"$BIN_DIR/ping" <<EOF
#!/usr/bin/env bash
echo "ping \$*" >> "$CALLS"
[[ "\$(cat "$LINK_STATE")" == "up" ]]
EOF

for tool in nmcli systemctl modprobe; do
  cat >"$BIN_DIR/$tool" <<EOF
#!/usr/bin/env bash
echo "$tool \$*" >> "$CALLS"
exit 0
EOF
done

# nmcli is also interrogated for the profile name, so it needs one real answer.
cat >"$BIN_DIR/nmcli" <<EOF
#!/usr/bin/env bash
echo "nmcli \$*" >> "$CALLS"
if [[ "\$*" == *"device status"* ]]; then
  echo "wlan0:netplan-wlan0-simon-eero"
fi
exit 0
EOF

cat >"$BIN_DIR/ip" <<EOF
#!/usr/bin/env bash
echo "ip \$*" >> "$CALLS"
echo "default via 192.168.4.1 dev wlan0 proto dhcp src 192.168.4.66 metric 600"
EOF

chmod +x "$BIN_DIR"/*

export PATH="$BIN_DIR:$PATH"

# Runs the watchdog once with a given consecutive-failure count already on disk,
# and prints whatever it asked the stubbed tools to do.
run_at_failure_count() {
  local count="$1"
  : >"$CALLS"
  printf '%s\n' "$count" >"$STATE_DIR/consecutive-failures"
  DRY_RUN=0 STATE_DIR="$STATE_DIR" "$WATCHDOG" >"$WORK_DIR/out" 2>&1
  grep -vE '^(ping|ip) ' "$CALLS" 2>/dev/null || true
}

echo
echo "  A healthy link takes no action and resets the counter"
echo "up" >"$LINK_STATE"
printf '%s\n' "7" >"$STATE_DIR/consecutive-failures"
: >"$CALLS"
DRY_RUN=0 STATE_DIR="$STATE_DIR" "$WATCHDOG" >"$WORK_DIR/out" 2>&1
check "no remediation runs while the link is up" \
  '[[ -z "$(grep -vE "^(ping|ip) " "$CALLS" 2>/dev/null)" ]]'
check "the failure counter is reset to 0" \
  '[[ "$(cat "$STATE_DIR/consecutive-failures")" == "0" ]]'
check "recovery is logged with the outage length" \
  'grep -q "link recovered after 7" "$WORK_DIR/out"'

echo
echo "  The link being down walks the ladder in order"
echo "down" >"$LINK_STATE"

check "nothing happens on the first failed check" \
  '[[ -z "$(run_at_failure_count 0)" ]]'
check "nothing happens on the second" \
  '[[ -z "$(run_at_failure_count 1)" ]]'
check "rung 1 (3 checks) re-activates the profile" \
  'run_at_failure_count 2 | grep -q "nmcli connection up netplan-wlan0-simon-eero"'
check "rung 2 (6 checks) bounces the device" \
  'run_at_failure_count 5 | grep -q "nmcli device disconnect wlan0"'
check "rung 2 reconnects it as well as disconnecting it" \
  'run_at_failure_count 5 | grep -q "nmcli device connect wlan0"'
check "rung 3 (10 checks) restarts NetworkManager" \
  'run_at_failure_count 9 | grep -q "systemctl restart NetworkManager"'
check "rung 4 (20 checks) reloads the driver" \
  'run_at_failure_count 19 | grep -q "modprobe -r brcmfmac_cyw brcmfmac"'
check "rung 4 stops NetworkManager first, or the module is still in use" \
  'run_at_failure_count 19 | head -1 | grep -q "systemctl stop NetworkManager"'
check "rung 4 brings NetworkManager back" \
  'run_at_failure_count 19 | grep -q "systemctl start NetworkManager"'
check "rung 5 (30 checks) reboots" \
  'run_at_failure_count 29 | grep -q "systemctl reboot"'

echo
echo "  A reboot is rate limited"
rm -f "$STATE_DIR/last-reboot"
run_at_failure_count 29 >/dev/null
check "the reboot is recorded" '[[ -s "$STATE_DIR/last-reboot" ]]'
check "a second reboot inside the interval is refused" \
  '! run_at_failure_count 29 | grep -q "systemctl reboot"'
printf '%s\n' "1" >"$STATE_DIR/last-reboot"
check "a reboot is allowed again once the interval has passed" \
  'run_at_failure_count 29 | grep -q "systemctl reboot"'

echo
echo "  Past the last rung it never gives up"
# This is the whole point of the script. The 2026-09-18 outage was ~23.5 hours,
# i.e. roughly 1,410 checks - far past every rung. A ladder that stopped
# escalating and also stopped retrying would have recovered nothing.
rm -f "$STATE_DIR/last-reboot"
recovery_attempts=0
for count in $(seq 30 74); do
  if run_at_failure_count "$count" | grep -qE "nmcli (connection up|device (dis)?connect)|systemctl restart NetworkManager"; then
    recovery_attempts=$((recovery_attempts + 1))
  fi
done
check "recovery is still attempted repeatedly 45 checks past the last rung" \
  '[[ "$recovery_attempts" -ge 20 ]]'

# An outage a day long must not be a reboot loop: the AP being genuinely absent
# is the case that produces these counts, and a reboot then costs a full stack
# cold start for nothing.
reboots=0
for count in $(seq 30 74); do
  if run_at_failure_count "$count" | grep -q "systemctl reboot"; then
    reboots=$((reboots + 1))
  fi
done
check "it does not turn into a reboot loop" '[[ "$reboots" -le 1 ]]'

echo
echo "  DRY_RUN touches nothing"
: >"$CALLS"
printf '%s\n' "29" >"$STATE_DIR/consecutive-failures"
DRY_RUN=1 STATE_DIR="$STATE_DIR" "$WATCHDOG" >"$WORK_DIR/out" 2>&1
check "no tool is invoked under DRY_RUN" \
  '[[ -z "$(grep -vE "^(ping|ip) " "$CALLS" 2>/dev/null)" ]]'
check "DRY_RUN says what it would have done" 'grep -q "DRY RUN: would" "$WORK_DIR/out"'
check "DRY_RUN does not advance the counter on disk" \
  '[[ "$(cat "$STATE_DIR/consecutive-failures")" == "29" ]]'

echo
echo "  The installer does not run a root unit out of the checkout"
# A root systemd unit whose ExecStart points into a directory the login user can
# write is a privilege escalation, and the deploy rewrites that directory while
# the watchdog is the thing keeping the host reachable. The installer copies to
# /usr/local/sbin; this pins that it keeps doing so.
INSTALLER="$PROJECT_DIR/scripts/install-wifi-watchdog.sh"
check "the installer exists and is executable" '[[ -x "$INSTALLER" ]]'
check "it installs the watchdog under /usr/local/sbin" \
  'grep -qE "^WATCHDOG_SCRIPT=\"?/usr/local/sbin/wifi-watchdog\.sh" "$INSTALLER"'
check "it copies the script there as root-owned" \
  'grep -q "install -o root -g root -m 0755" "$INSTALLER"'
check "the unit does not ExecStart out of the project directory" \
  '! grep -q "ExecStart=.*\$PROJECT_DIR" "$INSTALLER"'

echo
echo "  The journal drop-in outranks the vendor one"
# Raspberry Pi OS ships /usr/lib/systemd/journald.conf.d/40-rpi-volatile-storage.conf
# with Storage=volatile. Drop-ins merge by filename across /etc and /usr/lib and
# apply in lexical order, so anything below 40- is read, reported by
# `systemd-analyze cat-config`, and completely ineffective. The first cut of the
# installer used 10- and silently did nothing on the real host.
journal_dropin="$(grep -oE '/etc/systemd/journald\.conf\.d/[0-9]+-[a-z-]+\.conf' "$INSTALLER" | grep -v '10-persistent' | head -1)"
check "the installer writes a journald drop-in" '[[ -n "$journal_dropin" ]]'
check "its name sorts after 40-rpi-volatile-storage.conf" \
  '[[ "$(printf "%s\n" "$(basename "$journal_dropin")" "40-rpi-volatile-storage.conf" | sort | tail -1)" == "$(basename "$journal_dropin")" ]]'
check "it sets Storage=persistent" 'grep -q "^Storage=persistent" "$INSTALLER"'
check "the journal is size-capped, on a host already at ~83% disk" \
  'grep -qE "^SystemMaxUse=[0-9]+M" "$INSTALLER"'
check "the installer verifies persistence rather than assuming it" \
  'grep -q "File path: /run/log/journal" "$INSTALLER"'

echo
echo "  checks: $checks  failures: $failures"
[[ "$failures" -eq 0 ]]
