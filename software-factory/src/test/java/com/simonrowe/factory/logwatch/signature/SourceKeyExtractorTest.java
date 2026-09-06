package com.simonrowe.factory.logwatch.signature;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every fixture here is a real production line, read from Grafana Cloud Loki and captured from
 * the fourteen Linear tickets cancelled on 2026-09-06. That provenance matters: {@code
 * SignatureExtractorTest}'s fixtures were captured with {@code docker logs} because Loki held
 * nothing when 042 was written, so the rules it pins were recorded as estimates.
 */
class SourceKeyExtractorTest {

  private static final String ECS_EMBABEL =
      "{\"@timestamp\":\"2026-09-05T08:18:45.175457758Z\",\"log\":{\"level\":\"ERROR\","
          + "\"logger\":\"com.embabel.agent.spi.validation.DefaultAgentValidationManager\"},"
          + "\"process\":{\"pid\":1,\"thread\":{\"name\":\"main\"}},\"service\":{\"name\":"
          + "\"simonrowe-backend\",\"version\":\"0.0.1-SNAPSHOT\",\"node\":{}},\"message\":"
          + "\"- MISSING_GOALS: Agent 'WeeklyDigest' must have at least one goal defined\","
          + "\"ecs\":{\"version\":\"8.11\"}}";

  private static final String ECS_SPRING_APPLICATION =
      "{\"@timestamp\":\"2026-09-01T19:41:13.215299316Z\",\"log\":{\"level\":\"ERROR\","
          + "\"logger\":\"org.springframework.boot.SpringApplication\"},\"process\":{\"pid\":1,"
          + "\"thread\":{\"name\":\"main\"}},\"message\":\"Application run failed\"}";

  private static final String TEMPORAL_JSON =
      "{\"level\":\"error\",\"ts\":\"2026-09-01T02:55:45.312Z\",\"msg\":\"Operation failed with "
          + "internal error.\",\"error\":\"GetTaskQueue operation failed. Failed to check if task "
          + "queue /_sys/temporal-sys-processor-parent-close-policy/3 of type Workflow existed. "
          + "Error: context canceled\",\"error-type\":\"serviceerror.Unavailable\",\"operation\":"
          + "\"GetTaskQueue\"}";

  private static final String LOGFMT_LOKI_WRITE =
      "ts=2026-09-03T05:59:25.342351851Z level=error msg=\"final error sending batch, no retries "
          + "left, dropping data\" component_path=/ component_id=loki.write.grafana_cloud "
          + "component=endpoint host=logs-prod-035.grafana.net status=-1 tenant=\"\"";

  private static final String LOGFMT_LOKI_SOURCE =
      "ts=2026-09-01T06:40:31.229962382Z level=error msg=\"could not fetch logs for container\" "
          + "component_path=/ component_id=loki.source.docker.default component=tailer";

  private static final String SPRING_PLAIN =
      "2026-09-04T00:00:09.047Z  WARN 1 --- [software-factory] [ce=\"default\": 1] "
          + "c.s.factory.linear.linear.LinearGateway  : Team SIM has no label named "
          + "factory:logwatch - filing this issue unlabelled.";

  private static final String JAVA_EXCEPTION =
      "java.lang.IllegalStateException: backup-platform.sh exited with 1: [backup-platform] "
          + "ERROR: python3 is required (JSON handling)";

  private static final String BARE_TEXT =
      "ERROR: Elasticsearch did not exit normally - check the logs at "
          + "/usr/share/elasticsearch/logs/docker-cluster.log";

  @Test
  @DisplayName("ECS JSON yields the log.logger value")
  void ecsJsonYieldsLogger() {
    assertThat(SourceKeyExtractor.sourceKeyOf(ECS_EMBABEL))
        .contains("com.embabel.agent.spi.validation.DefaultAgentValidationManager");
  }

  @Test
  @DisplayName("Temporal JSON yields its msg, which is a literal template, never interpolated")
  void temporalJsonYieldsMessage() {
    assertThat(SourceKeyExtractor.sourceKeyOf(TEMPORAL_JSON))
        .contains("Operation failed with internal error.");
  }

  @Test
  @DisplayName("logfmt yields component_id")
  void logfmtYieldsComponentId() {
    assertThat(SourceKeyExtractor.sourceKeyOf(LOGFMT_LOKI_WRITE))
        .contains("loki.write.grafana_cloud");
  }

  @Test
  @DisplayName("Spring plain text yields the abbreviated logger, not the bracketed MDC context")
  void springPlainTextYieldsLogger() {
    assertThat(SourceKeyExtractor.sourceKeyOf(SPRING_PLAIN))
        .contains("c.s.factory.linear.linear.LinearGateway");
  }

  @Test
  @DisplayName("a bare stack-trace head yields the exception class")
  void javaExceptionYieldsClass() {
    assertThat(SourceKeyExtractor.sourceKeyOf(JAVA_EXCEPTION))
        .contains("java.lang.IllegalStateException");
  }

  @Test
  @DisplayName("an unrecognised format yields empty, so the caller falls back to the signature")
  void unrecognisedYieldsEmpty() {
    assertThat(SourceKeyExtractor.sourceKeyOf(BARE_TEXT)).isEmpty();
    assertThat(SourceKeyExtractor.sourceKeyOf("")).isEmpty();
    assertThat(SourceKeyExtractor.sourceKeyOf(null)).isEmpty();
  }

  @Test
  @DisplayName("SIM-11 and SIM-13 stay separate: one incident, two pieces of emitting code")
  void oneIncidentFromTwoLoggersStaysTwoSources() {
    assertThat(SourceKeyExtractor.sourceKeyOf(ECS_SPRING_APPLICATION))
        .isNotEqualTo(SourceKeyExtractor.sourceKeyOf(ECS_EMBABEL));
  }

  @Test
  @DisplayName("SIM-15 and SIM-16 stay separate: two Alloy components, two problems")
  void twoAlloyComponentsStayTwoSources() {
    assertThat(SourceKeyExtractor.sourceKeyOf(LOGFMT_LOKI_SOURCE))
        .isNotEqualTo(SourceKeyExtractor.sourceKeyOf(LOGFMT_LOKI_WRITE));
  }
}
