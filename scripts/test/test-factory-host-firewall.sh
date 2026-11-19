#!/usr/bin/env bash
#
# Coverage for enable-factory-host-firewall.sh: the DOCKER-USER rules that keep
# Temporal (7233, on the Pi) and software-factory (8090, on the NUC) reachable only
# from each other, and page-reader off the private network.
#
# `iptables` and `systemctl` are stubbed on PATH. The iptables stub keeps real chain
# state in a directory (one file per chain, one rule per line) and supports exactly
# the operations the script uses (-N -F -A -I -D -C -S), so idempotence is tested on
# behaviour rather than on the text of the script. The systemctl stub runs the
# installed rules script on `restart`, as systemd would, unless FAKE_UNIT_BROKEN=1.
# Nothing here touches a real firewall or systemd.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCRIPT="$PROJECT_DIR/scripts/enable-factory-host-firewall.sh"

export DRY_RUN=1

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
STUBS="$TMP/stubs"
mkdir -p "$STUBS"

cat >"$STUBS/iptables" <<'EOF'
#!/usr/bin/env bash
echo "iptables $*" >> "$CALLS"
[[ "${1:-}" == "-w" ]] && shift
op="$1" chain="$2"; shift 2
f="$IPT_STATE/$chain"
spec="$*"
case "$op" in
  -N) [[ -e "$f" ]] && { echo "Chain already exists." >&2; exit 1; }; : > "$f" ;;
  -F) [[ -e "$f" ]] || exit 1; : > "$f" ;;
  -A) [[ -e "$f" ]] || exit 1; echo "$spec" >> "$f" ;;
  -I) [[ -e "$f" ]] || exit 1
      pos="$1"; shift; spec="$*"
      { head -n $((pos - 1)) "$f"; echo "$spec"; tail -n +"$pos" "$f"; } > "$f.tmp"; mv "$f.tmp" "$f" ;;
  -D) [[ -e "$f" ]] || exit 1
      n=$(grep -nxF -- "$spec" "$f" | head -1 | cut -d: -f1)
      [[ -n "$n" ]] || exit 1
      sed -i.bak "${n}d" "$f"; rm -f "$f.bak" ;;
  -C) [[ -e "$f" ]] && grep -qxF -- "$spec" "$f" ;;
  -S) [[ -e "$f" ]] || exit 1
      echo "-N $chain"; while IFS= read -r r; do echo "-A $chain $r"; done < "$f" ;;
  *) echo "stub: unsupported $op" >&2; exit 2 ;;
esac
EOF

cat >"$STUBS/systemctl" <<'EOF'
#!/usr/bin/env bash
echo "systemctl $*" >> "$CALLS"
case "$1" in
  enable) touch "$IPT_STATE/.enabled" ;;
  is-enabled) [[ -e "$IPT_STATE/.enabled" ]] ;;
  restart) [[ "${FAKE_UNIT_BROKEN:-0}" == 1 ]] && exit 0; sh "$RULES_PATH" ;;
esac
EOF
chmod +x "$STUBS"/*

failures=0
checks=0
check() {
  local description="$1" condition="$2"
  checks=$((checks + 1))
  if eval "$condition"; then
    echo "    ok: $description"
  else
    failures=$((failures + 1))
    echo "    FAIL: $description"
  fi
}

# fresh_host <name>: a host whose Docker has already created DOCKER-USER, holding
# Docker's own trailing RETURN.
fresh_host() {
  HOST="$TMP/$1"
  mkdir -p "$HOST/ipt"
  echo "-j RETURN" > "$HOST/ipt/DOCKER-USER"
  CALLS="$HOST/calls"
  : > "$CALLS"
}

# run_fw <script args...>. FAKE_UNIT_BROKEN passes through from the caller.
run_fw() {
  env PATH="$STUBS:$PATH" CALLS="$CALLS" IPT_STATE="$HOST/ipt" SUDO= \
    RULES_PATH="$HOST/rules.sh" UNIT_PATH="$HOST/unit/factory-host-firewall.service" \
    FAKE_UNIT_BROKEN="${FAKE_UNIT_BROKEN:-0}" bash "$SCRIPT" "$@" 2>&1
}

resolved="$(PATH="$STUBS:$PATH" command -v iptables)"
[[ "$resolved" == "$STUBS/iptables" ]] || { echo "    FAIL: iptables is not the stub ($resolved)"; exit 1; }
resolved="$(PATH="$STUBS:$PATH" command -v systemctl)"
[[ "$resolved" == "$STUBS/systemctl" ]] || { echo "    FAIL: systemctl is not the stub ($resolved)"; exit 1; }

PEER=192.168.1.20

# ---------------------------------------------------------------------------
echo "  dry run (the default) changes nothing"
# ---------------------------------------------------------------------------
fresh_host dry
out="$(run_fw --role factory --peer "$PEER")"
rc=$?
check "exits 0" "[[ $rc -eq 0 ]]"
check "says it is a dry run" "grep -q '^DRY RUN' <<<\"\$out\""
check "shows the unit's ordering on docker" "grep -qx 'After=docker.service' <<<\"\$out\" && grep -qx 'WantedBy=multi-user.target' <<<\"\$out\""
check "calls neither iptables nor systemctl" "[[ ! -s '$CALLS' ]]"
check "writes no file" "[[ ! -e '$HOST/rules.sh' && ! -e '$HOST/unit' ]]"

# ---------------------------------------------------------------------------
echo "  site role: 7233 from the peer only"
# ---------------------------------------------------------------------------
fresh_host site
out="$(run_fw --role site --peer "$PEER" --apply)"
rc=$?
check "apply exits 0 and verifies" "[[ $rc -eq 0 ]] && grep -q '^VERIFIED' <<<\"\$out\""
expected_site="-m conntrack --ctstate RELATED,ESTABLISHED -j RETURN
-s $PEER/32 -p tcp -m conntrack --ctstate DNAT --ctdir ORIGINAL --ctorigdstport 7233 -j RETURN
-p tcp -m conntrack --ctstate DNAT --ctdir ORIGINAL --ctorigdstport 7233 -j DROP"
check "FACTORY-HOST holds exactly the three site rules, in order" \
  "[[ \"\$(cat '$HOST/ipt/FACTORY-HOST')\" == \"\$expected_site\" ]]"
check "DOCKER-USER jumps to it first, Docker's RETURN kept after" \
  "[[ \"\$(cat '$HOST/ipt/DOCKER-USER')\" == \$'-j FACTORY-HOST\n-j RETURN' ]]"
check "no factory rule leaked into the site role" "! grep -q '8090\|172.30.250' '$HOST/ipt/FACTORY-HOST'"
check "systemctl received exactly daemon-reload, enable, restart" \
  "[[ \"\$(grep '^systemctl' '$CALLS' | grep -v is-enabled)\" == \$'systemctl daemon-reload\nsystemctl enable factory-host-firewall.service\nsystemctl restart factory-host-firewall.service' ]]"
check "every iptables call waits for the xtables lock" \
  "! grep '^iptables' '$CALLS' | grep -qv '^iptables -w '"
check "the unit is a oneshot after docker, re-run with it, and installed for boot" \
  "grep -qx 'Type=oneshot' '$HOST/unit/factory-host-firewall.service' \
   && grep -qx 'After=docker.service' '$HOST/unit/factory-host-firewall.service' \
   && grep -qx 'PartOf=docker.service' '$HOST/unit/factory-host-firewall.service' \
   && grep -qx 'WantedBy=multi-user.target' '$HOST/unit/factory-host-firewall.service' \
   && grep -qx \"ExecStart=$HOST/rules.sh\" '$HOST/unit/factory-host-firewall.service'"

# ---------------------------------------------------------------------------
echo "  factory role: 8090 from the peer, page-reader fenced"
# ---------------------------------------------------------------------------
fresh_host factory
out="$(run_fw --role factory --peer "$PEER" --apply)"
rc=$?
check "apply exits 0 and verifies" "[[ $rc -eq 0 ]] && grep -q '^VERIFIED' <<<\"\$out\""
expected_factory="-m conntrack --ctstate RELATED,ESTABLISHED -j RETURN
-s $PEER/32 -p tcp -m conntrack --ctstate DNAT --ctdir ORIGINAL --ctorigdstport 8090 -j RETURN
-p tcp -m conntrack --ctstate DNAT --ctdir ORIGINAL --ctorigdstport 8090 -j DROP
-s 172.30.250.0/24 -d 172.30.250.0/24 -j RETURN
-s 172.30.250.0/24 -d $PEER/32 -p tcp --dport 7233 -j RETURN
-s 172.30.250.0/24 -d 10.0.0.0/8 -j DROP
-s 172.30.250.0/24 -d 172.16.0.0/12 -j DROP
-s 172.30.250.0/24 -d 192.168.0.0/16 -j DROP
-s 172.30.250.0/24 -d 169.254.0.0/16 -j DROP
-s 172.30.250.0/24 -d 100.64.0.0/10 -j DROP"
check "FACTORY-HOST holds exactly the ten factory rules, in order" \
  "[[ \"\$(cat '$HOST/ipt/FACTORY-HOST')\" == \"\$expected_factory\" ]]"
check "the Temporal allowance precedes the private-range drops (the Pi is on 192.168/16)" \
  "[[ \$(grep -n 'dport 7233 -j RETURN' '$HOST/ipt/FACTORY-HOST' | cut -d: -f1) -lt \$(grep -n '192.168.0.0/16 -j DROP' '$HOST/ipt/FACTORY-HOST' | cut -d: -f1) ]]"
check "no site rule leaked into the factory role" "! grep -q 'ctorigdstport 7233' '$HOST/ipt/FACTORY-HOST'"

# ---------------------------------------------------------------------------
echo "  idempotence: re-running replaces, never duplicates"
# ---------------------------------------------------------------------------
run_fw --role factory --peer "$PEER" --apply >/dev/null
out="$(run_fw --role factory --peer "$PEER" --apply)"
rc=$?
check "a third apply still verifies" "[[ $rc -eq 0 ]]"
check "still exactly one jump" "[[ \$(grep -cx -- '-j FACTORY-HOST' '$HOST/ipt/DOCKER-USER') -eq 1 ]]"
check "still exactly ten rules" "[[ \$(wc -l < '$HOST/ipt/FACTORY-HOST' | tr -d ' ') -eq 10 ]]"

# A jump someone added by hand, at the end, is folded into the one at the top.
echo "-j FACTORY-HOST" >> "$HOST/ipt/DOCKER-USER"
run_fw --role factory --peer "$PEER" --apply >/dev/null
check "a stray duplicate jump is removed" \
  "[[ \"\$(cat '$HOST/ipt/DOCKER-USER')\" == \$'-j FACTORY-HOST\n-j RETURN' ]]"

run_fw --role factory --peer 192.168.1.30 --apply >/dev/null
check "a new peer replaces the old one" \
  "grep -q '192.168.1.30/32' '$HOST/ipt/FACTORY-HOST' && ! grep -q '192.168.1.20' '$HOST/ipt/FACTORY-HOST'"

# ---------------------------------------------------------------------------
echo "  peer and role validation"
# ---------------------------------------------------------------------------
fresh_host bad
for bad in "" "192.168.1" "192.168.1.256" "192.168.01.2" "0.0.0.0" "1.2.3.4/24" "fe80::1" \
  "192.168.1.2;rm" "$(printf '9%.0s' $(seq 1 200))"; do
  out="$(run_fw --role factory --peer "$bad" --apply)"
  rc=$?
  check "refuses peer '${bad:0:20}'" "[[ $rc -ne 0 ]] && grep -q 'must be an IPv4 address' <<<\"\$out\""
done
out="$(run_fw --role factory --apply)"
rc=$?
check "refuses no peer at all" "[[ $rc -ne 0 ]] && grep -q 'must be an IPv4 address' <<<\"\$out\""
out="$(run_fw --role pi --peer "$PEER" --apply)"
rc=$?
check "refuses an unknown role" "[[ $rc -ne 0 ]] && grep -q \"role must be 'site' or 'factory'\" <<<\"\$out\""
check "and nothing was touched by any refusal" "[[ ! -s '$CALLS' ]]"
out="$(run_fw --role site --peer 10.0.0.255)"
rc=$?
check "accepts 10.0.0.255" "[[ $rc -eq 0 ]] && grep -q -- '-s 10.0.0.255/32' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  verify fails when the rules are not in place"
# ---------------------------------------------------------------------------
fresh_host absent
out="$(run_fw --role site --peer "$PEER" --verify)"
rc=$?
check "--verify on a bare host exits 1" "[[ $rc -eq 1 ]] && grep -q '^FAILED' <<<\"\$out\""
check "and names the missing jump" "grep -q 'jumps to FACTORY-HOST 0 times' <<<\"\$out\""

fresh_host broken
out="$(FAKE_UNIT_BROKEN=1 run_fw --role site --peer "$PEER" --apply)"
rc=$?
check "an apply whose unit did not load the rules exits non-zero" "[[ $rc -ne 0 ]] && grep -q '^FAILED' <<<\"\$out\""
check "while leaving the unit installed and enabled" \
  "[[ -f '$HOST/unit/factory-host-firewall.service' && -e '$HOST/ipt/.enabled' ]]"

fresh_host partial
run_fw --role site --peer "$PEER" --apply >/dev/null
sed -i.bak '/DROP/d' "$HOST/ipt/FACTORY-HOST"; rm -f "$HOST/ipt/FACTORY-HOST.bak"
out="$(run_fw --role site --peer "$PEER" --verify)"
rc=$?
check "a deleted DROP rule fails verify" "[[ $rc -eq 1 ]] && grep -q 'MISSING: .*ctorigdstport 7233 -j DROP' <<<\"\$out\""

fresh_host extra
run_fw --role site --peer "$PEER" --apply >/dev/null
echo "-j ACCEPT" >> "$HOST/ipt/FACTORY-HOST"
out="$(run_fw --role site --peer "$PEER" --verify)"
rc=$?
check "an extra rule someone added fails verify" "[[ $rc -eq 1 ]] && grep -q 'holds 4 rules, expected 3' <<<\"\$out\""

fresh_host disabled
run_fw --role site --peer "$PEER" --apply >/dev/null
rm -f "$HOST/ipt/.enabled"
out="$(run_fw --role site --peer "$PEER" --verify)"
rc=$?
check "rules present but the unit disabled fails verify (lost at the next boot)" \
  "[[ $rc -eq 1 ]] && grep -q 'not enabled' <<<\"\$out\""

echo
printf '  %d checks, %d failures\n' "$checks" "$failures"
[[ "$failures" -eq 0 ]]
