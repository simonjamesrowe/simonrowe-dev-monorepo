#!/usr/bin/env bash
#
# Coverage of the `sync-config` phase, which is the only part of the deploy that
# mutates the host.
#
# EVERY TEST RUNS AGAINST A SCRATCH ORIGIN AND A SCRATCH CLONE in a temp
# directory. Never the real deploy directory, and never this repository's own
# checkout: the phase moves HEAD, and a test that pointed at a working checkout
# would move a developer's HEAD out from under them.
#
# The fixture is a tiny repository containing just a docker-compose.prod.yml and a
# copy of the script, which is all the phase reads.
set -uo pipefail

# THE ONE TEST THAT OPTS OUT OF DRY_RUN, and the reason is the point of the file:
# what is under test here is the real git behaviour - the ancestor assertion, the
# --ff-only, the reset --hard. With DRY_RUN set, run_cmd echoes those commands
# instead of running them, HEAD never moves, and every assertion below passes or
# fails for reasons unrelated to the fences.
#
# Safe because nothing here touches anything real: each test builds its own origin
# and clone under mktemp, and runs the COPY of the script inside that clone. The
# only docker commands sync-config issues are read-only (`config --hash`,
# `config -q`), and STATE_DIR is inside the throwaway clone too.
unset DRY_RUN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
REAL_SCRIPT="$PROJECT_DIR/scripts/restart-prod.sh"

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

if ! docker version >/dev/null 2>&1; then
  # `sync-config` runs `docker compose config --hash` to decide which services a
  # change affects, so without Docker there is nothing meaningful to assert.
  echo "    SKIP: docker is not available"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ---------------------------------------------------------------------------
# Fixture
# ---------------------------------------------------------------------------

compose_with() {
  # A minimal but real compose file. `frontend` is on the recreate allowlist and
  # `mongodb` is deliberately not, so a change to each exercises both branches.
  cat <<EOF
services:
  frontend:
    image: nginx:alpine
    environment:
      MARKER: "$1"
  mongodb:
    image: mongo:8
    environment:
      MARKER: "$2"
EOF
}

# Builds a fresh origin + clone. Returns the clone path on stdout.
new_fixture() {
  local name="$1"
  local origin="$TMP/$name-origin" clone="$TMP/$name"

  rm -rf "$origin" "$clone"
  mkdir -p "$origin"
  git -C "$origin" init -q --initial-branch=main
  git -C "$origin" config user.email test@example.com
  git -C "$origin" config user.name Test
  mkdir -p "$origin/scripts"
  cp "$REAL_SCRIPT" "$origin/scripts/restart-prod.sh"
  compose_with base base >"$origin/docker-compose.prod.yml"
  # Untracked and ignored, exactly like the real host's .env.
  printf '.env\n' >"$origin/.gitignore"
  git -C "$origin" add -A
  git -C "$origin" commit -q -m "base"

  git clone -q "$origin" "$clone"
  git -C "$clone" config user.email test@example.com
  git -C "$clone" config user.name Test
  echo "$clone"
}

# Adds a commit to the origin changing one service's marker, and prints its sha.
commit_change() {
  local origin="$1" frontend_marker="$2" mongodb_marker="$3" message="$4"
  compose_with "$frontend_marker" "$mongodb_marker" >"$origin/docker-compose.prod.yml"
  git -C "$origin" add -A
  git -C "$origin" commit -q -m "$message"
  git -C "$origin" rev-parse HEAD
}

# Runs sync-config in a clone, against the clone's own origin as REPO_URL.
sync() {
  local clone="$1" target="$2"
  # DRY_RUN is NOT set: the whole point of these tests is the real git behaviour,
  # and it is safe because everything is a throwaway clone. The docker commands
  # sync-config runs are read-only (`config --hash`, `config -q`).
  STATE_DIR="$clone/state" REPO_URL="$clone-origin" \
    bash "$clone/scripts/restart-prod.sh" sync-config "$target" 2>"$TMP/stderr" \
    >"$TMP/stdout"
  echo $?
}

head_of() {
  git -C "$1" rev-parse HEAD
}

value_of() {
  grep "^$1=" "$TMP/stdout" | head -1 | cut -d= -f2-
}

# ---------------------------------------------------------------------------
echo "  fast-forwards to the deployed commit"
# ---------------------------------------------------------------------------
clone="$(new_fixture ff)"
target="$(commit_change "$TMP/ff-origin" changed base "change frontend")"
before="$(head_of "$clone")"
rc="$(sync "$clone" "$target")"

check "exits 0" "[[ '$rc' == '0' ]]"
check "reports decision=applied" "[[ \"\$(value_of decision)\" == 'applied' ]]"
check "reports the previous commit as the rollback target" \
  "[[ \"\$(value_of previous-sha)\" == '$before' ]]"
check "moves HEAD to the target" "[[ \"\$(head_of '$clone')\" == '$target' ]]"
check "names the affected service" "[[ \"\$(value_of affected)\" == *frontend* ]]"

# ---------------------------------------------------------------------------
echo "  fast-forwards to the TARGET, not the tip of main"
# ---------------------------------------------------------------------------
# The distinction that matters: `git pull` would take whatever main points at now,
# which may be a newer commit whose images do not exist yet. Config and images
# have to come from the same commit or the deploy is a mix of two.
clone="$(new_fixture tip)"
target="$(commit_change "$TMP/tip-origin" one base "first")"
tip="$(commit_change "$TMP/tip-origin" two base "second, newer")"
rc="$(sync "$clone" "$target")"

check "exits 0" "[[ '$rc' == '0' ]]"
check "HEAD is the target commit" "[[ \"\$(head_of '$clone')\" == '$target' ]]"
check "HEAD is NOT the newer tip" "[[ \"\$(head_of '$clone')\" != '$tip' ]]"

# ---------------------------------------------------------------------------
echo "  already current"
# ---------------------------------------------------------------------------
clone="$(new_fixture current)"
target="$(head_of "$clone")"
rc="$(sync "$clone" "$target")"

check "exits 0" "[[ '$rc' == '0' ]]"
check "reports already-current" "[[ \"\$(value_of decision)\" == 'already-current' ]]"
check "HEAD is unchanged" "[[ \"\$(head_of '$clone')\" == '$target' ]]"

# ---------------------------------------------------------------------------
echo "  refuses a dirty tree"
# ---------------------------------------------------------------------------
clone="$(new_fixture dirty)"
target="$(commit_change "$TMP/dirty-origin" changed base "change frontend")"
before="$(head_of "$clone")"
echo "# a human is working on the box" >>"$clone/docker-compose.prod.yml"
rc="$(sync "$clone" "$target")"

check "exits 2 (declined, not failed)" "[[ '$rc' == '2' ]]"
check "reports dirty-tree" "[[ \"\$(value_of decision)\" == 'dirty-tree' ]]"
check "leaves HEAD untouched" "[[ \"\$(head_of '$clone')\" == '$before' ]]"
check "still reported the previous commit before declining" \
  "[[ \"\$(value_of previous-sha)\" == '$before' ]]"

# ---------------------------------------------------------------------------
echo "  an untracked file does NOT block"
# ---------------------------------------------------------------------------
# The real host has a hand-edited, gitignored .env. It must never block a deploy,
# and must never be overwritten.
clone="$(new_fixture untracked)"
target="$(commit_change "$TMP/untracked-origin" changed base "change frontend")"
printf 'SECRET=keepme\n' >"$clone/.env"
printf 'scratch\n' >"$clone/some-untracked-file"
rc="$(sync "$clone" "$target")"

check "exits 0" "[[ '$rc' == '0' ]]"
check "applies the sync" "[[ \"\$(value_of decision)\" == 'applied' ]]"
check "the untracked .env survives untouched" \
  "[[ \"\$(cat '$clone/.env')\" == 'SECRET=keepme' ]]"
check "the other untracked file survives" "[[ -f '$clone/some-untracked-file' ]]"

# ---------------------------------------------------------------------------
echo "  refuses a commit that is not on origin/main"
# ---------------------------------------------------------------------------
# THE fence that bounds this whole capability: the working tree can only ever move
# to a commit genuinely on origin/main.
clone="$(new_fixture ancestor)"
before="$(head_of "$clone")"
# A commit that exists locally in the clone but was never pushed.
compose_with rogue base >"$clone/docker-compose.prod.yml"
git -C "$clone" add -A
git -C "$clone" commit -q -m "not on main"
rogue="$(head_of "$clone")"
git -C "$clone" reset -q --hard "$before"
rc="$(sync "$clone" "$rogue")"

check "exits 2" "[[ '$rc' == '2' ]]"
check "reports not-an-ancestor" "[[ \"\$(value_of decision)\" == 'not-an-ancestor' ]]"
check "leaves HEAD untouched" "[[ \"\$(head_of '$clone')\" == '$before' ]]"

# ---------------------------------------------------------------------------
echo "  holds back a change affecting a non-allowlisted service"
# ---------------------------------------------------------------------------
clone="$(new_fixture heldback)"
target="$(commit_change "$TMP/heldback-origin" base changed "change mongodb")"
before="$(head_of "$clone")"
rc="$(sync "$clone" "$target")"

check "exits 2" "[[ '$rc' == '2' ]]"
check "reports held-back" "[[ \"\$(value_of decision)\" == 'held-back' ]]"
check "names mongodb as held back" "[[ \"\$(value_of held-back)\" == *mongodb* ]]"
check "supplies the manual command" \
  "[[ \"\$(value_of manual-command)\" == *'up -d'*mongodb* ]]"
# The most important assertion here. Fast-forwarding and THEN declining to
# recreate would leave the deploy directory ahead of what is running, and
# monitor-prod.sh's next bare `up -d` would apply the held-back change within the
# minute - precisely the surprise this is meant to prevent.
check "leaves HEAD UNTOUCHED rather than moving it and refusing to recreate" \
  "[[ \"\$(head_of '$clone')\" == '$before' ]]"

# ---------------------------------------------------------------------------
echo "  holds back when a mixed change touches one non-allowlisted service"
# ---------------------------------------------------------------------------
clone="$(new_fixture mixed)"
target="$(commit_change "$TMP/mixed-origin" changed changed "change both")"
before="$(head_of "$clone")"
rc="$(sync "$clone" "$target")"

check "exits 2" "[[ '$rc' == '2' ]]"
check "one non-allowlisted service is enough to hold the whole change" \
  "[[ \"\$(value_of decision)\" == 'held-back' ]]"
check "leaves HEAD untouched" "[[ \"\$(head_of '$clone')\" == '$before' ]]"

# ---------------------------------------------------------------------------
echo "  widening the allowlist lets a held-back change through"
# ---------------------------------------------------------------------------
# Widening it is a one-line config change once a service has earned it.
clone="$(new_fixture widened)"
target="$(commit_change "$TMP/widened-origin" base changed "change mongodb")"
STATE_DIR="$clone/state" REPO_URL="$clone-origin" \
  RECREATABLE="frontend mongodb" \
  bash "$clone/scripts/restart-prod.sh" sync-config "$target" \
  >"$TMP/stdout" 2>"$TMP/stderr"
rc=$?

check "exits 0" "[[ '$rc' == '0' ]]"
check "applies the sync" "[[ \"\$(value_of decision)\" == 'applied' ]]"
check "HEAD moved" "[[ \"\$(head_of '$clone')\" == '$target' ]]"

# ---------------------------------------------------------------------------
echo "  declines when the new compose file needs an undefined variable"
# ---------------------------------------------------------------------------
# Declining here is what stops the box being left in a state where every later
# `docker compose` command fails.
clone="$(new_fixture missingvar)"
cat >"$TMP/missingvar-origin/docker-compose.prod.yml" <<'EOF'
services:
  frontend:
    image: nginx:alpine
    environment:
      MARKER: ${A_VARIABLE_THIS_HOST_DOES_NOT_DEFINE:?set it in .env}
  mongodb:
    image: mongo:8
EOF
git -C "$TMP/missingvar-origin" add -A
git -C "$TMP/missingvar-origin" commit -q -m "needs a new variable"
target="$(git -C "$TMP/missingvar-origin" rev-parse HEAD)"
before="$(head_of "$clone")"
rc="$(sync "$clone" "$target")"

check "exits 2" "[[ '$rc' == '2' ]]"
check "reports missing-variable" "[[ \"\$(value_of decision)\" == 'missing-variable' ]]"
check "names the variable" \
  "[[ \"\$(value_of missing-variable)\" == 'A_VARIABLE_THIS_HOST_DOES_NOT_DEFINE' ]]"
check "leaves HEAD untouched" "[[ \"\$(head_of '$clone')\" == '$before' ]]"

# ---------------------------------------------------------------------------
echo "  rollback-config restores the recorded commit"
# ---------------------------------------------------------------------------
clone="$(new_fixture rollback)"
target="$(commit_change "$TMP/rollback-origin" changed base "change frontend")"
before="$(head_of "$clone")"
sync "$clone" "$target" >/dev/null
check "the deploy moved HEAD first" "[[ \"\$(head_of '$clone')\" == '$target' ]]"

STATE_DIR="$clone/state" bash "$clone/scripts/restart-prod.sh" rollback-config "$before" \
  >/dev/null 2>&1
rc=$?
check "rollback-config exits 0" "[[ '$rc' == '0' ]]"
check "HEAD is back at the recorded commit" "[[ \"\$(head_of '$clone')\" == '$before' ]]"
check "the compose file is the previous version" \
  "! grep -q 'MARKER: \"changed\"' '$clone/docker-compose.prod.yml'"

# ---------------------------------------------------------------------------
echo "  usage"
# ---------------------------------------------------------------------------
clone="$(new_fixture usage)"
STATE_DIR="$clone/state" bash "$clone/scripts/restart-prod.sh" sync-config >/dev/null 2>&1
rc=$?
check "sync-config with no target exits 64" "[[ '$rc' == '64' ]]"

STATE_DIR="$clone/state" bash "$clone/scripts/restart-prod.sh" rollback-config >/dev/null 2>&1
rc=$?
check "rollback-config with no target exits 64" "[[ '$rc' == '64' ]]"

# ---------------------------------------------------------------------------
echo
printf '  %d checks, %d failures\n' "$checks" "$failures"
[[ "$failures" -eq 0 ]]
