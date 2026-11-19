#!/usr/bin/env bash
#
# Coverage for backup-factory-mongo.sh: the factory host's nightly mongodump.
#
# `docker` is stubbed on PATH: `ps` answers the label query, and `exec ... mongodump`
# writes a real gzip stream (or fails, or writes nothing) on stdout. The DRY_RUN=0
# cases run only after proving `docker` resolves to the stub, so nothing here can
# reach a real daemon. Each case uses its own throwaway BACKUP_DIR.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCRIPT="$PROJECT_DIR/scripts/backup-factory-mongo.sh"

export DRY_RUN=1

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
STUBS="$TMP/stubs"
mkdir -p "$STUBS"

# The resolved name is deliberately NOT <project>-<service>-1, so the assertions
# cannot pass on a formatted-name fallback.
cat >"$STUBS/docker" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$CALLS"
case "$1" in
  ps)
    [[ " $* " == *" label=com.docker.compose.project=simonrowe-factory "* \
      && " $* " == *" label=com.docker.compose.service=mongodb "* \
      && "${FAKE_NO_CONTAINER:-0}" != 1 ]] && echo "factory-mongo-by-label"
    ;;
  exec)
    case "${FAKE_DUMP:-ok}" in
      ok) printf 'fake mongodump archive' | gzip -c ;;
      fail) echo "Failed: connection refused" >&2; exit 1 ;;
      missing) exit 127 ;;
      empty) ;;
      garbage) printf 'not gzip at all' ;;
    esac
    ;;
esac
exit 0
EOF
chmod +x "$STUBS/docker"

resolved="$(PATH="$STUBS:$PATH" command -v docker)"
[[ "$resolved" == "$STUBS/docker" ]] || { echo "    FAIL: docker is not the stub ($resolved)"; exit 1; }

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

CALLS="$TMP/calls"
run_backup() {
  local dir="$1" dry="$2"
  shift 2
  : > "$CALLS"
  env PATH="$STUBS:$PATH" CALLS="$CALLS" BACKUP_DIR="$dir" DRY_RUN="$dry" "$@" bash "$SCRIPT" 2>&1
}

archives() { find "$1" -maxdepth 1 -name 'software_factory-*.archive.gz' | sort; }

# seed <dir> <n>: n archives dated on consecutive days of January 2026.
seed() {
  local dir="$1" n="$2" i
  mkdir -p "$dir"
  for ((i = 1; i <= n; i++)); do
    printf 'old %d' "$i" | gzip -c > "$dir/software_factory-202601$(printf '%02d' "$i")T023000Z.archive.gz"
  done
}

# ---------------------------------------------------------------------------
echo "  a good dump"
# ---------------------------------------------------------------------------
dir="$TMP/good"
out="$(run_backup "$dir" 0)"
rc=$?
check "exits 0" "[[ $rc -eq 0 ]]"
check "resolves mongodb by compose labels, not by a formatted name" \
  "grep -qxF 'ps --format {{.Names}} --filter label=com.docker.compose.project=simonrowe-factory --filter label=com.docker.compose.service=mongodb' '$CALLS'"
check "dumps software_factory as a gzipped archive from that container" \
  "grep -qxF 'exec factory-mongo-by-label mongodump --archive --gzip --db software_factory --quiet' '$CALLS'"
check "writes one dated archive" "[[ \$(archives '$dir' | wc -l | tr -d ' ') -eq 1 ]]"
check "the archive is the dump" "[[ \"\$(gzip -dc \"\$(archives '$dir')\")\" == 'fake mongodump archive' ]]"
check "leaves no partial file behind" "[[ -z \"\$(find '$dir' -name '*.partial')\" ]]"

# ---------------------------------------------------------------------------
echo "  retention keeps the newest 7"
# ---------------------------------------------------------------------------
dir="$TMP/retention"
seed "$dir" 9
echo "not an archive" > "$dir/notes.txt"
echo "x" | gzip -c > "$dir/software_factory-manual.archive.gz"
out="$(run_backup "$dir" 0)"
rc=$?
check "exits 0" "[[ $rc -eq 0 ]]"
check "exactly 7 dated archives remain" "[[ \$(archives '$dir' | grep -c 'T[0-9]*Z') -eq 7 ]]"
check "the three oldest are gone" \
  "[[ ! -e '$dir/software_factory-20260101T023000Z.archive.gz' && ! -e '$dir/software_factory-20260102T023000Z.archive.gz' && ! -e '$dir/software_factory-20260103T023000Z.archive.gz' ]]"
check "the newest seeded one and today's are kept" \
  "[[ -e '$dir/software_factory-20260109T023000Z.archive.gz' ]] && archives '$dir' | grep -q \"\$(date -u +%Y%m%d)T\""
check "files that are not this script's archives are never touched" \
  "[[ -e '$dir/notes.txt' && -e '$dir/software_factory-manual.archive.gz' ]]"

dir="$TMP/retention-3"
seed "$dir" 5
run_backup "$dir" 0 RETENTION=3 >/dev/null
check "RETENTION=3 keeps 3" "[[ \$(archives '$dir' | wc -l | tr -d ' ') -eq 3 ]]"

out="$(run_backup "$TMP/r0" 0 RETENTION=0)"
rc=$?
check "RETENTION=0 is refused rather than deleting everything" "[[ $rc -ne 0 ]] && grep -q 'positive integer' <<<\"\$out\""

# ---------------------------------------------------------------------------
echo "  failures exit non-zero and keep the old archives"
# ---------------------------------------------------------------------------
for mode in fail missing empty garbage; do
  dir="$TMP/fail-$mode"
  seed "$dir" 7
  out="$(run_backup "$dir" 0 FAKE_DUMP=$mode)"
  rc=$?
  check "$mode: exits non-zero" "[[ $rc -ne 0 ]]"
  check "$mode: deletes none of the 7 good archives" "[[ \$(archives '$dir' | wc -l | tr -d ' ') -eq 7 ]]"
  check "$mode: leaves no partial file" "[[ -z \"\$(find '$dir' -name '*.partial')\" ]]"
done
out="$(run_backup "$TMP/msg-missing" 0 FAKE_DUMP=missing)"
check "a missing mongodump (127) is named as such" "grep -q 'mongodump is not runnable in factory-mongo-by-label (exit 127)' <<<\"\$out\""
out="$(run_backup "$TMP/msg-empty" 0 FAKE_DUMP=empty)"
check "an empty archive is named as such" "grep -q 'empty archive' <<<\"\$out\""

dir="$TMP/nocontainer"
seed "$dir" 7
out="$(run_backup "$dir" 0 FAKE_NO_CONTAINER=1)"
rc=$?
check "no mongodb container: exits non-zero" "[[ $rc -ne 0 ]] && grep -q \"no running container for compose service 'mongodb' in project 'simonrowe-factory'\" <<<\"\$out\""
check "and never execs into a guessed name" "! grep -q '^exec' '$CALLS'"

# ---------------------------------------------------------------------------
echo "  DRY_RUN changes nothing"
# ---------------------------------------------------------------------------
dir="$TMP/dry"
seed "$dir" 8
out="$(run_backup "$dir" 1)"
rc=$?
check "exits 0" "[[ $rc -eq 0 ]]"
check "never runs mongodump" "! grep -q '^exec' '$CALLS'"
check "says what it would dump" "grep -q 'would run: docker exec factory-mongo-by-label mongodump --archive --gzip --db software_factory' <<<\"\$out\""
check "names the two archives it would delete" \
  "grep -q 'would delete .*20260101T' <<<\"\$out\" && grep -q 'would delete .*20260102T' <<<\"\$out\" && [[ \$(grep -c 'would delete' <<<\"\$out\") -eq 2 ]]"
check "deletes nothing" "[[ \$(archives '$dir' | wc -l | tr -d ' ') -eq 8 ]]"

echo
printf '  %d checks, %d failures\n' "$checks" "$failures"
[[ "$failures" -eq 0 ]]
