package com.simonrowe.factory.logwatch.signature;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.logwatch.domain.Severity;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Fixtures are real lines from the production stack, captured via `docker logs` on the Pi.
 *
 * <p>They come from `docker logs` rather than Loki because Loki held nothing for the whole of the
 * period this module was written in — the containers log fine, it is only shipping that broke.
 */
class SeverityDetectorTest {

  @Test
  @DisplayName("logfmt: alloy's own failure, the line that diagnosed the outage")
  void detectsLogfmtError() {
    String line =
        "ts=2026-08-31T10:34:52.95190745Z level=error msg=\"final error sending batch, no "
            + "retries left, dropping data\" component_path=/ "
            + "component_id=loki.write.grafana_cloud";

    assertThat(SeverityDetector.detect(line)).contains(Severity.ERROR);
  }

  @Test
  void detectsLogfmtWarn() {
    assertThat(SeverityDetector.detect("ts=2026-08-31T10:34:52Z level=warn msg=\"retrying\""))
        .contains(Severity.WARN);
  }

  @Test
  @DisplayName("Spring Boot console layout, as the backend and software-factory emit it")
  void detectsSpringBootLevels() {
    String error =
        "2026-08-31T17:23:34.236Z ERROR 1 --- [software-factory] [main] c.s.f.Application"
            + " : something broke";
    String warn =
        "2026-08-31T17:23:34.236Z  WARN 1 --- [software-factory] [main] c.s.f.Application"
            + " : something is odd";

    assertThat(SeverityDetector.detect(error)).contains(Severity.ERROR);
    assertThat(SeverityDetector.detect(warn)).contains(Severity.WARN);
  }

  @Test
  void detectsMongoStructuredLevels() {
    assertThat(SeverityDetector.detect("{\"t\":{},\"s\":\"E\", \"c\":\"NETWORK\"}"))
        .contains(Severity.ERROR);
    assertThat(SeverityDetector.detect("{\"t\":{},\"s\":\"W\", \"c\":\"NETWORK\"}"))
        .contains(Severity.WARN);
  }

  @Test
  void detectsBracketedAndAngleLevels() {
    assertThat(SeverityDetector.detect("[2026-08-31 17:00:00,123] [ERROR] kafka broke"))
        .contains(Severity.ERROR);
    assertThat(SeverityDetector.detect("[2026-08-31 17:00:00,123] [WARN ] kafka is unhappy"))
        .contains(Severity.WARN);
    assertThat(SeverityDetector.detect("2026.08.31 17:00:00 <Error> ClickHouse said no"))
        .contains(Severity.ERROR);
    assertThat(SeverityDetector.detect("2026.08.31 17:00:00 <Warning> ClickHouse is unsure"))
        .contains(Severity.WARN);
  }

  /**
   * The whole reason this class is a list of anchored matchers rather than one permissive regex.
   *
   * <p>These are real nginx access lines. A bare case-insensitive search for "error" or "warn"
   * anywhere in the line classifies every one of them, and nginx is the second-chattiest container
   * in the stack — so getting this wrong does not produce a few false findings, it produces
   * thousands.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "172.18.0.14 - - [31/Aug/2026:11:18:38 +0000] \"GET /ws/chat HTTP/1.1\" 101 43 \"-\""
            + " \"Mozilla/5.0\" xff=\"-\"",
        "172.18.0.14 - - [31/Aug/2026:11:18:38 +0000] \"GET /error HTTP/1.1\" 200 43 \"-\""
            + " \"Mozilla/5.0\" xff=\"-\"",
        "172.18.0.14 - - [31/Aug/2026:11:18:38 +0000] \"GET /api/warnings HTTP/1.1\" 200 43"
            + " \"-\" \"Mozilla/5.0\" xff=\"-\"",
        "172.18.0.14 - - [31/Aug/2026:11:18:38 +0000] \"GET /blogs/handling-errors HTTP/1.1\""
            + " 200 9 \"-\" \"Mozilla/5.0\" xff=\"-\""
      })
  void doesNotClassifyNginxAccessLines(final String accessLine) {
    assertThat(SeverityDetector.detect(accessLine)).isEmpty();
  }

  @Test
  @DisplayName("an unmatched line is excluded, never defaulted to WARN")
  void excludesRatherThanDefaulting() {
    assertThat(SeverityDetector.detect("MCP feature registered: dashboardWidgets")).isEmpty();
    assertThat(SeverityDetector.detect("")).isEmpty();
    assertThat(SeverityDetector.detect(null)).isEqualTo(Optional.empty());
  }

  @Test
  @DisplayName("ERROR outranks WARN when a line somehow carries both")
  void errorWinsOverWarn() {
    assertThat(SeverityDetector.detect("level=error msg=\"a warn threshold was crossed\""))
        .contains(Severity.ERROR);
  }
}
