#!/usr/bin/env bash
set -euo pipefail

# Restrict the two ports the site host (the Pi) and the factory host (the NUC) open
# to each other, and fence the factory's page-reader off the private network.
#
# WHY DOCKER-USER, AND NOT UFW
# ----------------------------
# A port published by Docker is DNAT'd in the nat table and then FORWARDed to the
# container. It never passes the INPUT chain, which is the only chain ufw (and most
# host firewalls) filter, so `ufw deny 7233` does nothing to a published port.
# Docker's own answer is the DOCKER-USER chain: FORWARD jumps to it before any of
# Docker's rules, and Docker never edits it.
#
# WHAT IS ENFORCED
# ----------------
#   --role site     tcp 7233 (Temporal) accepted only from --peer (the NUC).
#   --role factory  tcp 8090 (software-factory) accepted only from --peer (the Pi).
#                   page-reader's network, 172.30.250.0/24, may reach <peer>:7233
#                   (Temporal) and the public internet, and nothing else private:
#                   10/8, 172.16/12, 192.168/16, 169.254/16 and 100.64/10 are dropped.
#
# The port rules match `--ctstate DNAT` with the ORIGINAL destination port
# (`--ctorigdstport`), i.e. only connections that arrived at a published port.
# Matching the bare port instead would catch container-to-container traffic on the
# same compose network (bridged packets traverse FORWARD too, because Docker enables
# br_netfilter): on the Pi that is every Temporal client in the stack, which would
# lose Temporal the moment the rules went in.
#
# The first rule returns RELATED/ESTABLISHED traffic untouched, so replies (the
# Temporal container answering the NUC; a web server answering page-reader) are
# never judged as new connections.
#
# IDEMPOTENCE
# -----------
# Everything lives in its own chain, FACTORY-HOST, flushed and refilled on every run,
# and jumped to from DOCKER-USER exactly once: every existing jump is deleted before
# one is inserted. Re-running replaces the rules; it never duplicates them.
#
# WHAT IS INSTALLED (--apply)
# ---------------------------
#   $RULES_PATH                         the rules, role and peer baked in
#   $UNIT_PATH    a oneshot, After=docker.service, PartOf=docker.service (so a Docker
#                 restart re-applies it), WantedBy=multi-user.target (so a boot does)
#
# The unit has no ExecStop on purpose: stopping it leaves the rules in place, which
# fails closed.
#
# KNOWN LIMITS
#   - IPv4 only. The two published ports bind to an IPv4 LAN address
#     (TEMPORAL_BIND_ADDRESS / FACTORY_BIND_ADDRESS), so there is no IPv6 listener to
#     protect.
#   - page-reader can still reach the factory HOST's own services on its network
#     gateway: that traffic is INPUT, not FORWARD. Its in-container egress proxy,
#     which refuses non-public addresses, is the control there.
#   - page-reader's DNS goes to the daemon's upstream servers from inside its own
#     network namespace, so those must be public resolvers: a LAN resolver
#     (192.168.x.1) is dropped by the rules above. install-factory-host.sh pins
#     1.1.1.1/8.8.8.8 with enable-docker-dns.sh for exactly this reason.
#
# Usage:
#   enable-factory-host-firewall.sh --role site|factory --peer <ipv4>            # dry run
#   enable-factory-host-firewall.sh --role site|factory --peer <ipv4> --apply    # install
#   enable-factory-host-firewall.sh --role site|factory --peer <ipv4> --verify   # check

CHAIN="FACTORY-HOST"
UNIT_NAME="factory-host-firewall.service"
RULES_PATH=${RULES_PATH:-/usr/local/sbin/factory-host-firewall-rules.sh}
UNIT_PATH=${UNIT_PATH:-/etc/systemd/system/$UNIT_NAME}
PAGE_READER_SUBNET="172.30.250.0/24"
PRIVATE_RANGES=("10.0.0.0/8" "172.16.0.0/12" "192.168.0.0/16" "169.254.0.0/16" "100.64.0.0/10")
TEMPORAL_PORT=7233
FACTORY_PORT=8090

# `${SUDO-sudo}`, NOT `${SUDO:-sudo}`: the tests set SUDO= and the colon form would
# turn that back into `sudo`. Same convention as enable-docker-dns.sh.
SUDO=${SUDO-sudo}

usage() {
  cat <<EOF
Usage: $0 --role site|factory --peer <ipv4> [--apply|--verify]

  (default)  Print the rules and the systemd unit that --apply would install.
  --apply    Install $RULES_PATH and $UNIT_NAME, enable and start it, then verify.
  --verify   Check the rules are in place; exit 1 if not.
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

# A dotted quad, each octet 0-255 with no leading zero. Bounded before the regex
# runs, so a long argument is refused on length rather than matched.
valid_ipv4() {
  local ip="$1" octet
  (( ${#ip} >= 7 && ${#ip} <= 15 )) || return 1
  [[ "$ip" =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]] || return 1
  local IFS=.
  # shellcheck disable=SC2086
  set -- $ip
  for octet in "$@"; do
    [[ "$octet" == 0 || "$octet" != 0* ]] || return 1
    (( octet <= 255 )) || return 1
  done
  [[ "$ip" != "0.0.0.0" ]]
}

# The rule specs for a role, one per line, without the leading `-A FACTORY-HOST`.
# The single source for both the installed rules script and the verify step.
rule_specs() {
  local role="$1" peer="$2" range
  echo "-m conntrack --ctstate RELATED,ESTABLISHED -j RETURN"
  case "$role" in
    site)
      echo "-s $peer/32 -p tcp -m conntrack --ctstate DNAT --ctdir ORIGINAL --ctorigdstport $TEMPORAL_PORT -j RETURN"
      echo "-p tcp -m conntrack --ctstate DNAT --ctdir ORIGINAL --ctorigdstport $TEMPORAL_PORT -j DROP"
      ;;
    factory)
      echo "-s $peer/32 -p tcp -m conntrack --ctstate DNAT --ctdir ORIGINAL --ctorigdstport $FACTORY_PORT -j RETURN"
      echo "-p tcp -m conntrack --ctstate DNAT --ctdir ORIGINAL --ctorigdstport $FACTORY_PORT -j DROP"
      # Its own network (any future sidecar on it), then Temporal on the Pi, then
      # nothing else private. Anything public falls through to Docker's rules.
      echo "-s $PAGE_READER_SUBNET -d $PAGE_READER_SUBNET -j RETURN"
      echo "-s $PAGE_READER_SUBNET -d $peer/32 -p tcp --dport $TEMPORAL_PORT -j RETURN"
      for range in "${PRIVATE_RANGES[@]}"; do
        echo "-s $PAGE_READER_SUBNET -d $range -j DROP"
      done
      ;;
  esac
}

render_rules_script() {
  local role="$1" peer="$2" spec
  cat <<EOF
#!/bin/sh
# Generated by scripts/enable-factory-host-firewall.sh --role $role --peer $peer.
# Do not edit: re-run that script with --apply. See docs/runbooks/factory-host.md.
set -eu

# Docker creates DOCKER-USER when it starts; create it here if this ever runs first,
# and Docker will adopt it.
iptables -w -N DOCKER-USER 2>/dev/null || true
iptables -w -N $CHAIN 2>/dev/null || true
iptables -w -F $CHAIN
EOF
  while IFS= read -r spec; do
    echo "iptables -w -A $CHAIN $spec"
  done < <(rule_specs "$role" "$peer")
  cat <<EOF

# Exactly one jump, first in DOCKER-USER: delete every existing one, then insert.
while iptables -w -D DOCKER-USER -j $CHAIN 2>/dev/null; do :; done
iptables -w -I DOCKER-USER 1 -j $CHAIN
EOF
}

render_unit() {
  local role="$1" peer="$2"
  cat <<EOF
[Unit]
Description=Factory/site host firewall (DOCKER-USER, role $role, peer $peer)
Documentation=https://github.com/simonjamesrowe/simonrowe-dev-monorepo/blob/main/docs/runbooks/factory-host.md
After=docker.service
Wants=docker.service
PartOf=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=$RULES_PATH

[Install]
WantedBy=multi-user.target
EOF
}

# Exit 0 only when every rule is present, the chain holds nothing else, DOCKER-USER
# jumps to it exactly once, and the unit is enabled for the next boot.
verify() {
  local role="$1" peer="$2" spec missing=0 expected=0 actual jumps
  while IFS= read -r spec; do
    expected=$((expected + 1))
    # shellcheck disable=SC2086
    if ! $SUDO iptables -w -C "$CHAIN" $spec >/dev/null 2>&1; then
      echo "  MISSING: -A $CHAIN $spec"
      missing=$((missing + 1))
    fi
  done < <(rule_specs "$role" "$peer")

  actual="$($SUDO iptables -w -S "$CHAIN" 2>/dev/null | grep -c "^-A $CHAIN " || true)"
  jumps="$($SUDO iptables -w -S DOCKER-USER 2>/dev/null | grep -c -- "-j $CHAIN\$" || true)"

  local ok=1
  if (( missing > 0 )); then
    echo "  $missing of $expected rules are missing from $CHAIN"
    ok=0
  fi
  if [[ "$actual" != "$expected" ]]; then
    echo "  $CHAIN holds ${actual:-0} rules, expected $expected"
    ok=0
  fi
  if [[ "$jumps" != 1 ]]; then
    echo "  DOCKER-USER jumps to $CHAIN ${jumps:-0} times, expected exactly 1"
    ok=0
  fi
  if ! $SUDO systemctl is-enabled --quiet "$UNIT_NAME" 2>/dev/null; then
    echo "  $UNIT_NAME is not enabled, so the rules will not come back after a reboot"
    ok=0
  fi

  if (( ok )); then
    echo "VERIFIED: $CHAIN in place for role $role, peer $peer ($expected rules, one jump, unit enabled)"
    return 0
  fi
  echo "FAILED: the factory host firewall is NOT in place"
  return 1
}

apply() {
  local role="$1" peer="$2"
  command -v iptables >/dev/null 2>&1 || die "iptables not found; Docker's packages normally bring it"
  command -v systemctl >/dev/null 2>&1 || die "systemctl not found; this needs a systemd host"

  echo "Writing $RULES_PATH"
  $SUDO mkdir -p "$(dirname "$RULES_PATH")"
  render_rules_script "$role" "$peer" | $SUDO tee "$RULES_PATH" >/dev/null
  $SUDO chmod 0755 "$RULES_PATH"

  echo "Writing $UNIT_PATH"
  $SUDO mkdir -p "$(dirname "$UNIT_PATH")"
  render_unit "$role" "$peer" | $SUDO tee "$UNIT_PATH" >/dev/null

  $SUDO systemctl daemon-reload
  $SUDO systemctl enable "$UNIT_NAME"
  # restart, not start: a oneshot with RemainAfterExit stays "active", and start on
  # an active unit runs nothing, so a changed peer would never be applied.
  $SUDO systemctl restart "$UNIT_NAME"

  echo
  verify "$role" "$peer"
}

dry_run() {
  local role="$1" peer="$2"
  echo "DRY RUN - nothing is changed. Re-run with --apply to install."
  echo
  echo "=== $RULES_PATH"
  render_rules_script "$role" "$peer"
  echo
  echo "=== $UNIT_PATH"
  render_unit "$role" "$peer"
  echo
  echo "=== then"
  echo "would run: systemctl daemon-reload"
  echo "would run: systemctl enable $UNIT_NAME"
  echo "would run: systemctl restart $UNIT_NAME"
  echo "would verify: every rule above in $CHAIN, one jump from DOCKER-USER, unit enabled"
}

main() {
  local role="" peer="" mode="dry-run"
  while (( $# > 0 )); do
    case "$1" in
      --role) [[ $# -ge 2 ]] || die "--role needs a value"; role="$2"; shift 2 ;;
      --peer) [[ $# -ge 2 ]] || die "--peer needs a value"; peer="$2"; shift 2 ;;
      --apply) mode="apply"; shift ;;
      --verify) mode="verify"; shift ;;
      -h|--help) usage; exit 0 ;;
      *) usage >&2; die "unknown argument: $1" ;;
    esac
  done
  case "$role" in
    site|factory) ;;
    *) usage >&2; die "--role must be 'site' or 'factory'" ;;
  esac
  valid_ipv4 "$peer" || die "--peer must be an IPv4 address (got '$peer')"

  case "$mode" in
    dry-run) dry_run "$role" "$peer" ;;
    apply) apply "$role" "$peer" ;;
    verify) verify "$role" "$peer" ;;
  esac
}

main "$@"
