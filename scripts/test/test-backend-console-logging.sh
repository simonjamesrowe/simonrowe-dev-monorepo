#!/usr/bin/env bash
#
# Guards the backend's console logging configuration, whose failure mode is total
# and silent.
#
# During the Spring Boot 4.1 upgrade this file's subject broke in a way nothing
# else caught. logback-spring.xml chose between plain-text and structured (JSON)
# console output with:
#
#     <if condition='property("CONSOLE_LOG_STRUCTURED_FORMAT").equals("")'>
#
# Logback 1.5.38, which Boot 4.1.1 manages (up from ~1.5.18 on Boot 3.5),
# DEPRECATED AND IGNORES the `condition` attribute. Neither <then> nor <else>
# runs, so no appender named CONSOLE is ever created, the <root> reference finds
# nothing, and the application starts perfectly and logs ABSOLUTELY NOTHING. The
# single clue is one line in logback's own status output:
#
#     Appender named [CONSOLE] could not be found. Skipping attachment to Logger[ROOT].
#
# Two things made this worth a dedicated test:
#
#   1. The Gradle test suite cannot catch it. backend/src/test/resources holds its
#      own logback-test.xml, which takes precedence, so 1160 green tests say
#      nothing whatsoever about the file that ships.
#   2. Deleting logback-spring.xml and letting Boot choose does NOT work either.
#      Measured on 4.1.1: with no logback config, `logging.pattern.console` is
#      honoured but `logging.structured.format.console` is NOT, so production
#      silently falls back to plain text and the JSON pipeline into Loki breaks.
#
# A shell test rather than a Java one because the subject is a resource file read
# by Logback before Spring exists — the same reasoning as
# test-frontend-nginx-shipping.sh and test-log-shipping.sh, which this follows.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

LOGBACK_FILE="$PROJECT_DIR/backend/src/main/resources/logback-spring.xml"
COMPOSE_FILE="$PROJECT_DIR/docker-compose.prod.yml"

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

# Comments are stripped before scanning, for the reason test-log-shipping.sh
# gives: the comments here quote the very syntax that must not come back, so a
# scan over raw lines would fail on its own rationale.
#
# Captured once into a variable so every check greps a here-string rather than a
# pipeline — `uncommented file | grep -q` can lose the race to EPIPE under
# `pipefail` on a loaded runner.
BODY=""
if [[ -f "$LOGBACK_FILE" ]]; then
  BODY="$(perl -0777 -pe 's/<!--.*?-->//gs' "$LOGBACK_FILE")"
fi

# ---------------------------------------------------------------------------
echo "  the subjects exist"
# ---------------------------------------------------------------------------
check "backend logback-spring.xml exists" "[[ -f '$LOGBACK_FILE' ]]"
check "docker-compose.prod.yml exists" "[[ -f '$COMPOSE_FILE' ]]"

if [[ ! -f "$LOGBACK_FILE" || ! -f "$COMPOSE_FILE" ]]; then
  echo "  $checks checks, $failures failures"
  exit 1
fi

# ---------------------------------------------------------------------------
echo "  the deprecated conditional cannot come back"
# ---------------------------------------------------------------------------
# This is the whole point of the file. An <if> carrying a `condition` ATTRIBUTE
# is silently ignored by Logback 1.5.20+ and takes all console logging with it.
check "no <if ... condition=\"...\"> attribute" \
  "! grep -qE '<if[^>]+condition[[:space:]]*=' <<<\"\$BODY\""
check "no <if> element at all (the selection is property substitution)" \
  "! grep -qE '<if[[:space:]>]' <<<\"\$BODY\""

# Janino existed ONLY to evaluate that conditional. If it reappears in the build
# file, someone has reintroduced the <if>.
check "backend/build.gradle.kts declares no janino dependency" \
  "! grep -q 'janino' '$PROJECT_DIR/backend/build.gradle.kts'"

# ---------------------------------------------------------------------------
echo "  the console appender is actually defined and referenced"
# ---------------------------------------------------------------------------
# Boot's two fragments both declare an appender named CONSOLE; exactly one must
# be included, by name, through the substituted prefix.
check "includes a console-appender fragment" \
  "grep -qE 'console-appender\.xml' <<<\"\$BODY\""
check "the include is prefix-substituted, not hard-coded to one variant" \
  "grep -qE '<include resource=\"[^\"]*\\\$\{CONSOLE_APPENDER_KIND\}console-appender\.xml\"' <<<\"\$BODY\""
check "root logger references CONSOLE" \
  "grep -qE '<appender-ref[[:space:]]+ref=\"CONSOLE\"' <<<\"\$BODY\""

# ---------------------------------------------------------------------------
echo "  both output modes are reachable"
# ---------------------------------------------------------------------------
# The plain case MUST come from the `:-` substitution default. Logback rejects
# `<property name="..." value=""/>` outright, so it cannot be declared.
check "CONSOLE_APPENDER_KIND falls back to empty via :- (the plain-text case)" \
  "grep -qE 'CONSOLE_APPENDER_KIND\"?[[:space:]]*\$|:-\}\"' <<<\"\$BODY\""
check "the structured case maps ecs to the structured- prefix" \
  "grep -qE 'CONSOLE_APPENDER_PREFIX_ecs\"[[:space:]]+value=\"structured-\"' <<<\"\$BODY\""
check "the lookup is keyed on Boot's CONSOLE_LOG_STRUCTURED_FORMAT" \
  "grep -qE 'CONSOLE_APPENDER_PREFIX_\\\$\{CONSOLE_LOG_STRUCTURED_FORMAT\}' <<<\"\$BODY\""
check "defaults.xml is included (it defines CONSOLE_LOG_STRUCTURED_FORMAT)" \
  "grep -qE 'logback/defaults\.xml' <<<\"\$BODY\""

# ---------------------------------------------------------------------------
echo "  production still asks for structured output"
# ---------------------------------------------------------------------------
# If this is dropped, prod silently reverts to plain text and Alloy ships
# unparseable lines to Loki. The value must be one the prefix map above knows.
check "docker-compose.prod.yml sets LOGGING_STRUCTURED_FORMAT_CONSOLE: ecs" \
  "grep -qE '^[[:space:]]+LOGGING_STRUCTURED_FORMAT_CONSOLE:[[:space:]]*ecs[[:space:]]*$' '$COMPOSE_FILE'"

echo
echo "  $checks checks, $failures failures"
[[ $failures -eq 0 ]]
