#!/usr/bin/env bash
#
# Coverage for scripts/enable-docker-dns.sh. The script edits the one file whose
# corruption stops every container on the Pi coming back after a daemon restart,
# so the properties worth pinning are the ones that keep daemon.json loadable:
# existing keys survive, the result is valid JSON, a malformed file or a non-IP
# server is refused WITHOUT touching the file, and revert removes only `dns`.
#
# Every run points DAEMON_JSON at a throwaway path, sets SUDO= so nothing needs a
# password, and puts a PATH with no `docker` first so the per-container report is
# skipped rather than reading this machine's containers.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCRIPT="$PROJECT_DIR/scripts/enable-docker-dns.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# A PATH holding only what the script needs, so a `docker` on the test machine is
# never consulted.
BIN="$TMP/bin"
mkdir -p "$BIN"
for tool in bash python3 sed cp mkdir tee date dirname cat grep; do
  src="$(command -v "$tool" 2>/dev/null || true)"
  [[ -n "$src" ]] && ln -s "$src" "$BIN/$tool"
done

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

run() {
  local json="$1"
  shift
  env -i PATH="$BIN" HOME="$TMP" DAEMON_JSON="$json" SUDO= "$@"
}

# Reads a key from a JSON file as compact JSON, or MISSING.
json_get() {
  python3 - "$1" "$2" <<'PY'
import json, sys
with open(sys.argv[1]) as fh:
    cfg = json.load(fh)
print(json.dumps(cfg[sys.argv[2]], separators=(",", ":")) if sys.argv[2] in cfg else "MISSING")
PY
}

# ---------------------------------------------------------------------------
echo "  apply to a daemon.json that does not exist yet"
# ---------------------------------------------------------------------------
fresh="$TMP/fresh/daemon.json"
run "$fresh" bash "$SCRIPT" --apply >/dev/null 2>&1
check "creates the file with the default public resolvers" \
  "[[ \$(json_get '$fresh' dns) == '[\"1.1.1.1\",\"8.8.8.8\"]' ]]"

# ---------------------------------------------------------------------------
echo "  apply merges into the production shape (log rotation already present)"
# ---------------------------------------------------------------------------
prod="$TMP/prod.json"
cat >"$prod" <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "20m",
    "max-file": "5"
  }
}
JSON
run "$prod" bash "$SCRIPT" --apply >/dev/null 2>&1
check "adds dns" "[[ \$(json_get '$prod' dns) == '[\"1.1.1.1\",\"8.8.8.8\"]' ]]"
check "keeps log-driver" "[[ \$(json_get '$prod' log-driver) == '\"json-file\"' ]]"
check "keeps log-opts, max-file still a string" \
  "[[ \$(json_get '$prod' log-opts) == '{\"max-size\":\"20m\",\"max-file\":\"5\"}' ]]"
check "leaves a timestamped backup of the previous file" \
  "compgen -G '$TMP/prod.json.bak.*' >/dev/null"

run "$prod" bash "$SCRIPT" --apply >/dev/null 2>&1
check "re-applying is idempotent" "[[ \$(json_get '$prod' dns) == '[\"1.1.1.1\",\"8.8.8.8\"]' ]]"

custom="$TMP/custom.json"
echo '{}' >"$custom"
run "$custom" DOCKER_DNS="9.9.9.9 2620:fe::fe" bash "$SCRIPT" --apply >/dev/null 2>&1
check "DOCKER_DNS overrides the servers, IPv6 accepted" \
  "[[ \$(json_get '$custom' dns) == '[\"9.9.9.9\",\"2620:fe::fe\"]' ]]"

# ---------------------------------------------------------------------------
echo "  refusals leave the file byte-for-byte unchanged"
# ---------------------------------------------------------------------------
bad="$TMP/bad.json"
printf '{ "log-driver": "json-file", }\n' >"$bad"
cp "$bad" "$TMP/bad.orig"
run "$bad" bash "$SCRIPT" --apply >/dev/null 2>&1
rc=$?
check "malformed daemon.json exits non-zero" "[[ $rc -ne 0 ]]"
check "malformed daemon.json is not rewritten" "cmp -s '$bad' '$TMP/bad.orig'"

named="$TMP/named.json"
echo '{"log-driver":"json-file"}' >"$named"
cp "$named" "$TMP/named.orig"
run "$named" DOCKER_DNS="1.1.1.1 dns.google" bash "$SCRIPT" --apply >/dev/null 2>&1
rc=$?
check "a hostname instead of an IP exits non-zero (dockerd would refuse to start)" "[[ $rc -ne 0 ]]"
check "a hostname instead of an IP is not written" "cmp -s '$named' '$TMP/named.orig'"

# ---------------------------------------------------------------------------
echo "  verify reports the state through its exit code"
# ---------------------------------------------------------------------------
run "$prod" bash "$SCRIPT" --verify >/dev/null 2>&1
check "verify passes once dns is pinned" "[[ $? -eq 0 ]]"
nodns="$TMP/nodns.json"
echo '{"log-driver":"json-file"}' >"$nodns"
run "$nodns" bash "$SCRIPT" --verify >/dev/null 2>&1
check "verify fails while dns is not pinned" "[[ $? -ne 0 ]]"

# ---------------------------------------------------------------------------
echo "  revert removes only dns"
# ---------------------------------------------------------------------------
run "$prod" bash "$SCRIPT" --revert >/dev/null 2>&1
check "dns is gone" "[[ \$(json_get '$prod' dns) == MISSING ]]"
check "log rotation survives the revert" \
  "[[ \$(json_get '$prod' log-opts) == '{\"max-size\":\"20m\",\"max-file\":\"5\"}' ]]"

echo
printf '  %d checks, %d failures\n' "$checks" "$failures"
[[ "$failures" -eq 0 ]]
