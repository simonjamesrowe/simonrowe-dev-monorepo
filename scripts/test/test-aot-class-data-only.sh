#!/usr/bin/env bash
#
# Guards that both JVM images use their Leyden AOT cache for class data only.
#
# With an AOT cache in use, JDK 25 switches on AOTAdapterCaching and AOTStubCaching by itself, so
# the cache also carries adapters compiled on the GitHub runner that built the image. On the Pi 5
# those fail to link and the JVM dies with SIGILL in ~AdapterBlob within a minute of starting.
# Every deploy of #211 crash-looped and rolled back (SIM-79), and an Apple Silicon machine does not
# reproduce it, so no local run would ever notice the flags going missing. Hence a test on the two
# files that carry them.
#
# The software-factory assertion is scoped to the line that starts the JVM WITH the cache. A grep
# over the whole of start.sh would also pass on the flags appearing only in its comment.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

BACKEND_BUILD="$PROJECT_DIR/backend/build.gradle.kts"
FACTORY_START="$PROJECT_DIR/software-factory/docker/start.sh"
FLAGS="-XX:+UnlockDiagnosticVMOptions -XX:-AOTAdapterCaching -XX:-AOTStubCaching"

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

# ---------------------------------------------------------------------------
echo "  the subjects exist"
# ---------------------------------------------------------------------------
check "backend/build.gradle.kts exists" "[[ -f '$BACKEND_BUILD' ]]"
check "software-factory/docker/start.sh exists" "[[ -f '$FACTORY_START' ]]"

# ---------------------------------------------------------------------------
echo "  backend: the flags are baked into the image's launch environment"
# ---------------------------------------------------------------------------
# BPE_APPEND only joins with the delimiter it is given; without one the flags run into the
# buildpack's own options and the JVM refuses the result.
check "the AOT cache is still enabled (this guard is about how, not whether)" \
  "grep -qF 'environment.put(\"BP_JVM_AOTCACHE_ENABLED\", \"true\")' '$BACKEND_BUILD'"
check "JAVA_TOOL_OPTIONS is appended with a space delimiter" \
  "grep -qF 'environment.put(\"BPE_DELIM_JAVA_TOOL_OPTIONS\", \" \")' '$BACKEND_BUILD'"
check "the appended options are exactly the class-data-only flags" \
  "grep -A2 -F '\"BPE_APPEND_JAVA_TOOL_OPTIONS\"' '$BACKEND_BUILD' | grep -qF '\"$FLAGS\"'"

# ---------------------------------------------------------------------------
echo "  software-factory: the JVM that maps the cache gets the flags"
# ---------------------------------------------------------------------------
cache_line="$(grep -E '^exec java .*-XX:AOTCache=' "$FACTORY_START")"
check "exactly one exec line maps the cache" \
  "[[ \$(grep -cE '^exec java .*-XX:AOTCache=' '$FACTORY_START') -eq 1 ]]"
check "that line passes \$AOT_CLASS_DATA_ONLY" \
  "[[ '$cache_line' == *'\$AOT_CLASS_DATA_ONLY'* ]]"
check "AOT_CLASS_DATA_ONLY holds exactly the class-data-only flags" \
  "grep -qxF 'AOT_CLASS_DATA_ONLY=\"$FLAGS\"' '$FACTORY_START'"

# ---------------------------------------------------------------------------
echo
if [[ "$failures" -gt 0 ]]; then
  echo "  $failures of $checks checks failed"
  exit 1
fi
echo "  all $checks checks passed"
