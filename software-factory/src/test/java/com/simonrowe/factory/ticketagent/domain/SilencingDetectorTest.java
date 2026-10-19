package com.simonrowe.factory.ticketagent.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SilencingDetectorTest {

  private static String diff(final String file, final String... added) {
    StringBuilder diff = new StringBuilder()
        .append("diff --git a/").append(file).append(" b/").append(file).append('\n')
        .append("--- a/").append(file).append('\n')
        .append("+++ b/").append(file).append('\n')
        .append("@@ -1,3 +1,4 @@\n")
        .append(" unchanged context\n");
    for (String line : added) {
      diff.append('+').append(line).append('\n');
    }
    return diff.toString();
  }

  @Test
  void genuineFixSilencesNothing() {
    assertThat(SilencingDetector.reasons(diff(
        "backend/src/main/java/com/simonrowe/A.java",
        "    if (value == null) {",
        "      return List.of();",
        "    }")))
        .isEmpty();
  }

  @Test
  void logWatchMuteRuleIsSilencing() {
    assertThat(SilencingDetector.reasons(diff(
        "software-factory/src/main/resources/application.yml",
        "      - reason: Temporal teardown noise",
        "        container: temporal",
        "        contains: context canceled")))
        .containsExactly(
            "`software-factory/src/main/resources/application.yml` adds a log-watch mute rule");
  }

  /** The bracketed `$` key form is exactly how a Spring logger name with `$` must be written. */
  @Test
  void quietingLoggerIsSilencingInEitherKeyForm() {
    assertThat(SilencingDetector.reasons(diff(
        "backend/src/main/resources/application.yml",
        "    org.apache.fontbox.ttf: ERROR")))
        .containsExactly("`backend/src/main/resources/application.yml` quiets a logger");
    assertThat(SilencingDetector.reasons(diff(
        "backend/src/main/resources/application.yml",
        "    \"[org.springframework.Outer$Inner]\": \"off\"")))
        .hasSize(1);
  }

  @Test
  void raisingLoggerToInfoOrDebugIsNotSilencing() {
    assertThat(SilencingDetector.reasons(diff(
        "backend/src/main/resources/application.yml",
        "    com.simonrowe.chat: DEBUG",
        "    enabled: true")))
        .isEmpty();
  }

  @Test
  void disablingTestIsSilencing() {
    assertThat(SilencingDetector.reasons(diff(
        "backend/src/test/java/com/simonrowe/ATest.java", "  @Disabled(\"flaky\")")))
        .containsExactly("`backend/src/test/java/com/simonrowe/ATest.java` adds `@Disabled`");
    assertThat(SilencingDetector.reasons(diff(
        "frontend/tests/a.test.tsx", "  it.skip('renders', () => {")))
        .hasSize(1);
  }

  @Test
  void suppressingWarningIsSilencing() {
    assertThat(SilencingDetector.reasons(diff(
        "backend/src/main/java/A.java", "  @SuppressWarnings(\"unchecked\")")))
        .hasSize(1);
    assertThat(SilencingDetector.reasons(diff(
        "frontend/src/a.ts", "  // eslint-disable-next-line no-console")))
        .hasSize(1);
  }

  @Test
  void removedAndContextLinesAreIgnored() {
    String diff = """
        +++ b/backend/src/main/resources/application.yml
        @@ -1 +1 @@
        -    org.apache.fontbox.ttf: ERROR
             org.apache.pdfbox: ERROR
        """;
    assertThat(SilencingDetector.reasons(diff)).isEmpty();
  }

  @Test
  void oneReasonPerFileAndKind() {
    assertThat(SilencingDetector.reasons(diff(
        "backend/src/main/resources/application.yml", "    a.b: ERROR", "    c.d: OFF")))
        .hasSize(1);
  }

  /** Unbounded input: a huge diff must be read in one linear pass. */
  @Test
  void hugeDiffIsReadQuickly() {
    String line = "+" + "x".repeat(200_000) + "\n";
    String huge = "+++ b/a.yml\n" + line.repeat(5);
    long start = System.nanoTime();
    assertThat(SilencingDetector.reasons(huge)).isEmpty();
    assertThat(System.nanoTime() - start).isLessThan(2_000_000_000L);
  }
}
