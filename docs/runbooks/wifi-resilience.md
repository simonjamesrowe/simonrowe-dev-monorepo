# Runbook: Wi-Fi resilience

The Pi reaches the internet over Wi-Fi, and on 2026-09-18 that took the whole
site down for 23.5 hours. This is what happened, what was changed, and what is
still unknown.

## The incident

The access point was unplugged at about **09:05 UTC on 2026-09-18** — a cleaner,
not a fault. It was plugged back in the same day. The Pi never rejoined the
network, and the site stayed dark until the machine was **power-cycled by hand at
08:30 UTC on 2026-09-19**. Everything came back on its own after that: all six
public hostnames were serving by 08:45.

Two things about the shape of this are worth keeping.

**Nothing on the box tried to fix it.** `scripts/monitor-prod.sh` watches
containers, and every container was running perfectly the whole time. The fault
was one layer below anything it can see — and on the link its own ability to
report depends on. A container watchdog cannot cover a network outage, and it is
not a defect in it that it did not.

**Nothing recorded why.** `Storage=auto` with an empty `/var/log/journal` means
the journal lives in `/run`, so the reboot that recovered the machine destroyed
every log line explaining the failure. We know the AP went away and we know the
Pi did not come back; we do **not** know which of the plausible mechanisms was
responsible. That is why the fixes below are belt-and-braces rather than
targeted, and why making the journal persistent is part of the change.

### A forensic trap on this host

The Pi 5's RTC is not battery-backed, so a power cycle loses the clock and
systemd restores it from the last `systemd-timesyncd` save. After the 09-19
reboot, `journalctl --list-boots` reported a single boot whose first entry was
`Fri 2026-09-18 09:47:39 BST` — 23h45m before the machine had actually started —
while `uptime` correctly said 27 minutes. The early-boot lines carry the stale
clock and everything after the first NTP sync carries the real one, in one
unbroken boot ID.

**After any power cycle here, do not trust journal timestamps from before the
first `systemd-timesyncd` sync**, and do not read a boot that appears to span a
day as evidence the machine stayed up. Cross-check against `uptime -s`, or
against Loki, whose timestamps are assigned on ingest.

## What was changed

Three independent things, installed by `scripts/install-wifi-watchdog.sh`.

### 1. NetworkManager defaults

Written to `/etc/NetworkManager/conf.d/10-wifi-resilience.conf`, applied with
`systemctl reload NetworkManager` (reload, not restart — it re-reads config
without deactivating devices, so it is safe to run over the link it configures).

| Setting | Was | Now | Why |
| --- | --- | --- | --- |
| `wifi.powersave` | on | `2` (disable) | The `brcmfmac` radio ships with power save on. On an always-on server it buys nothing, and it is the best-documented cause of a Pi that does not notice its AP has returned. |
| `connection.autoconnect-retries` | `-1` → global default of **4** | `0` (forever) | After four failed autoconnect attempts NetworkManager blocks the profile and stops trying by itself. An AP away for more than a couple of minutes burns that budget immediately. |
| `unmanaged-devices` | unset | `docker*`, `veth*`, `br-*` | The watchdog restarts NetworkManager at rung 3. Docker's interfaces show as `connected (externally)`, which is a description of current behaviour; this makes it a constraint. |

**`connection.autoconnect-retries` is not a valid key in `NetworkManager.conf`'s
`[connection]` section** on NM 1.52 — it logs `unknown key` and carries on, which
is exactly the silently-ignored-config failure this repo keeps meeting. Neither
`autoconnect-retries-default` nor the fully-qualified form is accepted. It is
therefore set **on each Wi-Fi profile** with `nmcli connection modify` instead.
Verify with:

```bash
sudo NetworkManager --print-config | grep -i warning        # must be empty
nmcli -f connection.autoconnect-retries con show netplan-wlan0-simon-eero
iw dev wlan0 get power_save
```

Note the profiles are **netplan-generated** (`/etc/netplan/90-NM-<uuid>.yaml`,
profile names `netplan-wlan0-<ssid>`). `nmcli connection modify` writes back into
that YAML on this host — confirmed, the file grew and its mtime moved — so the
change survives. A future `netplan apply` from a hand-written file would not
preserve it, which is the reason everything that *can* live in `conf.d` does.

### 2. The watchdog

`scripts/wifi-watchdog.sh`, installed to `/usr/local/sbin/wifi-watchdog.sh` and
run by a **root systemd timer every minute**, logging to
`/var/log/wifi-watchdog/watchdog.log` (logrotate: weekly, 8 weeks).

It is copied to `/usr/local/sbin` rather than run from the deploy checkout, which
is the one place it departs from `monitor-prod.sh`: a root unit executing a file
the login user can write is a privilege escalation, and the deploy rewrites that
directory at exactly the times the network is most likely to be disturbed.

Connectivity is probed by **IP, never by name** — the default gateway first
(that is the association being recovered), then `1.1.1.1` and `8.8.8.8` as the
tiebreak for a gateway that does not answer ICMP. Resolving a name here would
have the watchdog bouncing the radio because a nameserver was slow.

Rungs are keyed on consecutive failed checks, i.e. roughly minutes of total loss:

| After | Action |
| --- | --- |
| 3 | Re-activate the Wi-Fi profile (`nmcli connection up`) — also clears an NM autoconnect block |
| 6 | Bounce the device (`nmcli device disconnect` / `connect`) |
| 10 | `systemctl restart NetworkManager` |
| 20 | Unload and reload the Wi-Fi driver |
| 30 | Reboot, rate-limited to once per 6 hours |
| beyond | Cycle the first three rungs every 5 checks, **forever** |

**The last row is the point of the whole script.** The failure it exists for is
an AP that is away for hours and then returns; anything that stops retrying has
already failed by the time it comes back. NetworkManager's own default of 4
retries is precisely that mistake, one layer down.

**The reboot rung is rate-limited for a reason, not out of caution.** If the AP
is genuinely absent — the actual 2026-09-18 case, a whole working day — a reboot
fixes nothing and costs a full stack cold start each time. Measured on
2026-09-19: ~8 minutes from power-on to all six hostnames serving, during which
the backend crash-loops against an Elasticsearch that has not yet bound 9200 (see
below). One reboot per six hours buys the case a reboot does fix — a wedged radio
— without turning a neighbour's holiday into a reboot loop.

**Rung 4's failure mode is covered by rung 5**, which is the only reason it is
safe to attempt at all: NetworkManager holds the interface, so the module cannot
be removed underneath it and NM must be stopped first; if the module does not
come back, the host is offline until the reboot rung collects it ten minutes
later.

Verify and operate:

```bash
systemctl list-timers wifi-watchdog.timer
tail -50 /var/log/wifi-watchdog/watchdog.log
sudo env DRY_RUN=1 STATE_DIR=$(mktemp -d) /usr/local/sbin/wifi-watchdog.sh
```

**Always use `DRY_RUN=1` and a throwaway `STATE_DIR` when testing a change** —
same rule as `monitor-prod.sh`, and for a sharper reason: every rung here either
bounces the radio, restarts NetworkManager or reboots the host, so running the
script "to see what it says" takes the site off the internet.
`scripts/test/test-wifi-watchdog.sh` covers the ladder with stubbed tools and is
in the `run-tests.sh` suite, so it sits inside the required
`Software Factory Build & Test` check.

### 3. A persistent journal

`Storage=persistent`, `SystemMaxUse=300M`, `MaxRetentionSec=1month` in
**`/etc/systemd/journald.conf.d/95-persistent-journal.conf`**. Capped because the
host runs at ~83% disk and an uncapped journal is the kind of fix that becomes
the next outage.

This is here so the **next** occurrence leaves evidence. Without it the recovery
action — a power cycle — is also the action that destroys the explanation.

**The `95-` prefix is load-bearing; do not renumber it.** Raspberry Pi OS ships
`/usr/lib/systemd/journald.conf.d/40-rpi-volatile-storage.conf` (package
`raspberrypi-sys-mods`) containing `Storage=volatile`. Drop-ins are merged by
filename across `/etc` and `/usr/lib` and applied in lexical order, so a
`10-`-prefixed file in `/etc` **loses to the vendor's `40-` one**. The first cut
of the installer used `10-`, and the result was the failure mode this repo keeps
meeting: the setting is present, it is reported by `systemd-analyze cat-config`,
and it does nothing.

```
$ systemd-analyze cat-config systemd/journald.conf | grep -E '^# /|Storage='
# /etc/systemd/journald.conf.d/10-persistent.conf
Storage=persistent                                   <- read, then overridden
# /usr/lib/systemd/journald.conf.d/40-rpi-volatile-storage.conf
Storage=volatile                                     <- wins
```

That is also the command to diagnose it. The authoritative check is where the
journal actually lives — anything under `/run` is volatile:

```bash
sudo journalctl --header | grep -m1 'File path'
```

`Storage=volatile` is a sensible vendor default for a Pi booting from an SD card.
This one boots from a 117G USB SSD (`/dev/sda2`), so the write-wear argument it
exists for does not apply here.

## What this does not fix

**The Pi is on Wi-Fi.** `eth0` exists and is `unavailable` — no cable. Everything
above makes the Pi recover from an AP that comes back; none of it helps while the
AP is genuinely away, and the site is down for that whole period regardless. **A
wired connection to the router removes this entire class of outage** and is the
only change here that would have kept the site up on 2026-09-18. It is a physical
job, not a software one.

**We still do not know the 2026-09-18 mechanism.** Power save, the retry limit,
and a wedged `brcmfmac` are all consistent with the symptom, and the evidence
that would have distinguished them was in the volatile journal. Each is now
addressed independently. If it recurs, the watchdog log and the persistent
journal will say which — and the watchdog's own escalation record is itself the
diagnosis, since the rung that restores the link names the cause.

## Related

- Disk was at **83%** on 2026-09-19, and Elasticsearch had already logged
  `low disk watermark [85%] exceeded ... replicas will not be assigned`. Not part
  of this incident, but it is close enough to the threshold to matter and the
  persistent journal above now takes a (capped) share of it.
- The cold-start race that makes every reboot cost ~8 minutes —
  `backend` crash-looping on `Connect to http://elasticsearch:9200 ... Connection
  refused` while wiring `vectorStore` → `embeddingService` — is described in
  `docs/runbooks/prod-monitoring.md`. It self-heals; it is the reason a reboot is
  expensive rather than free.
