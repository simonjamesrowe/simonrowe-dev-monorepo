package com.simonrowe.factory.logwatch.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.logwatch.domain.LogLine;
import com.simonrowe.factory.logwatch.domain.LogSignature;
import com.simonrowe.factory.logwatch.domain.Severity;
import com.simonrowe.factory.logwatch.signature.SignatureExtractor;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Checks the {@code factory.logwatch.ignore} rules that actually ship against the real production
 * log lines they were written for.
 *
 * <p>Deliberately binds the shipped {@code application.yml} rather than constructing rules by
 * hand. The rules <em>are</em> configuration — a hand-written fixture would prove the matching
 * code works and say nothing about whether the four rules in the file match anything, which is
 * the only question worth asking about them. {@code ContentCaptureKillSwitchTest} in the backend
 * reads its YAML for the same reason.
 *
 * <p>The lines below are verbatim from the tickets each rule exists to stop: SIM-28, SIM-31,
 * SIM-32, SIM-40, SIM-41 and SIM-42, captured from Loki on 2026-09-13. They are long and ugly on
 * purpose. A rule tested against a paraphrase is a rule tested against nothing — the phrases are
 * matched literally, and "canceling" has one L in Temporal's output.
 */
class LogWatchIgnoreRulesTest {

  private static final Instant WHEN = Instant.parse("2026-09-12T08:58:41Z");

  private static final String TEMPORAL_INTERNAL_ERROR =
      "{\"level\":\"error\",\"ts\":\"2026-09-12T08:58:41.026Z\",\"msg\":\"Operation failed with "
          + "internal error.\",\"error\":\"GetTaskQueue operation failed. Failed to check if task "
          + "queue /_sys/default-worker-tq/1 of type Workflow existed. Error: context canceled\","
          + "\"error-type\":\"serviceerror.Unavailable\",\"operation\":\"GetTaskQueue\"}";

  private static final String TEMPORAL_POLL_TIMEOUT =
      "{\"level\":\"error\",\"ts\":\"2026-09-10T06:40:38.554Z\",\"msg\":\"Unable to call "
          + "matching.PollWorkflowTaskQueue.\",\"service\":\"frontend\",\"wf-task-queue-name\":"
          + "\"1@a3e10add0f76:d2955baf\",\"timeout\":\"1m9.995004899s\",\"error\":"
          + "\"context canceled\"}";

  private static final String TEMPORAL_VISIBILITY_CANCEL =
      "{\"level\":\"error\",\"ts\":\"2026-09-07T12:00:07.843Z\",\"msg\":\"Operation failed with "
          + "an error.\",\"error\":\"pq: canceling statement due to user request\"}";

  private static final String ALLOY_DEAD_CONTAINER =
      "ts=2026-09-10T06:16:10.340215638Z level=error msg=\"could not fetch logs for container\" "
          + "component_path=/ component_id=loki.source.docker.default component=tailer "
          + "container=docker/68d510e49c92 container=68d510e49c92 err=\"Error response from "
          + "daemon: can not get logs from container which is dead or marked for removal\"";

  private static final String DTRACK_PYPI_RANGE =
      "2026-09-11 03:12:01,143 WARN [BovModelConverter] Range 'vers:pypi/>=0|<2.2.0rrc0|>=2.2.0|"
          + "<2.3.0rrc0' could not be parsed because one or more versions do not comply with the "
          + "versioning scheme's rules; Falling back to versioning scheme 'generic' instead "
          + "[vulnDataSourceName=osv, vulnSource=OSV, vulnId=PYSEC-2021-150]";

  private final LogWatchProperties properties = shippedProperties();

  @Test
  @DisplayName("the shipped rules mute every line the standing backlog was filed from")
  void mutesTheKnownThirdPartyNoise() {
    assertMuted("temporal", Severity.ERROR, TEMPORAL_INTERNAL_ERROR);
    assertMuted("temporal", Severity.ERROR, TEMPORAL_POLL_TIMEOUT);
    assertMuted("temporal", Severity.ERROR, TEMPORAL_VISIBILITY_CANCEL);
    assertMuted("alloy", Severity.ERROR, ALLOY_DEAD_CONTAINER);
    assertMuted("dependencytrack-apiserver", Severity.WARN, DTRACK_PYPI_RANGE);
  }

  /**
   * The container names in production carry the compose project prefix, and a rule that only
   * matched the bare service name would mute nothing at all in the one place it has to work.
   */
  @Test
  @DisplayName("the rules match the prefixed container names production actually reports")
  void matchesProductionContainerNames() {
    assertMuted("simonrowe-dev-monorepo-temporal-1", Severity.ERROR, TEMPORAL_INTERNAL_ERROR);
    assertMuted("simonrowe-dev-monorepo-alloy-1", Severity.ERROR, ALLOY_DEAD_CONTAINER);
    assertMuted(
        "simonrowe-dev-monorepo-dependencytrack-apiserver-1", Severity.WARN, DTRACK_PYPI_RANGE);
  }

  /**
   * The rule that most obviously over-reaches if written without a container. Alloy's own report
   * that it could not ship a batch is the SIM-29 signal and the one thing this module must never
   * stop hearing — an unheard ingest failure makes every subsequent scan self-consistently clean.
   */
  @Test
  @DisplayName("a real ingest failure from the same containers is still reported")
  void doesNotMuteRealProblemsFromTheSameContainers() {
    assertAudible(
        "simonrowe-dev-monorepo-alloy-1",
        Severity.ERROR,
        "ts=2026-09-11T07:44:05.277299745Z level=error msg=\"final error sending batch, no "
            + "retries left, dropping data\" component_id=loki.write.grafana_cloud status=429 "
            + "error=\"ingestion rate limit exceeded\"");
    assertAudible(
        "simonrowe-dev-monorepo-temporal-1",
        Severity.ERROR,
        "{\"level\":\"error\",\"ts\":\"2026-09-12T08:58:41.026Z\",\"msg\":\"Operation failed with "
            + "internal error.\",\"error\":\"GetTaskQueue operation failed. Error: connection "
            + "refused\"}");
    assertAudible(
        "simonrowe-dev-monorepo-backend-1",
        Severity.ERROR,
        "{\"level\":\"error\",\"msg\":\"upload aborted\",\"error\":\"context canceled\"}");
  }

  @Test
  @DisplayName("every shipped rule names a container, and none is a bare phrase")
  void everyRuleIsScopedAndExplained() {
    assertThat(properties.ignore()).isNotEmpty();
    assertThat(properties.ignore()).allSatisfy(rule -> {
      // A rule with no container mutes a phrase everywhere, including in code this repository
      // owns. There is no rule today that needs that, and a future one should have to argue for
      // it by deleting this assertion.
      assertThat(rule.container()).as("container for rule '%s'", rule.reason()).isNotBlank();
      assertThat(rule.contains()).as("phrase for rule '%s'", rule.reason()).isNotBlank();
      // The reason is the only thing that tells a future reader whether the rule can be deleted.
      assertThat(rule.reason()).as("reason for phrase '%s'", rule.contains()).isNotBlank();
    });
  }

  private void assertMuted(final String container, final Severity severity, final String raw) {
    LogSignature signature = group(container, severity, raw);
    assertThat(properties.mutedBy(signature))
        .as("expected a rule to mute: %s", raw)
        .isNotNull();
  }

  private void assertAudible(final String container, final Severity severity, final String raw) {
    LogSignature signature = group(container, severity, raw);
    assertThat(properties.mutedBy(signature))
        .as("expected no rule to mute: %s", raw)
        .isNull();
  }

  /**
   * Two identical lines, because a single occurrence never reaches the filter in production
   * either — the grouping is what turns a line into the thing a rule is applied to.
   */
  private LogSignature group(final String container, final Severity severity, final String raw) {
    List<LogSignature> grouped =
        SignatureExtractor.group(
            List.of(
                new LogLine(container, WHEN, severity, raw),
                new LogLine(container, WHEN, severity, raw)));
    assertThat(grouped).hasSize(1);
    return grouped.getFirst();
  }

  private static LogWatchProperties shippedProperties() {
    StandardEnvironment environment = new StandardEnvironment();
    try {
      List<PropertySource<?>> loaded =
          new YamlPropertySourceLoader()
              .load("application.yml", new ClassPathResource("application.yml"));
      loaded.forEach(source -> environment.getPropertySources().addLast(source));
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("could not read application.yml", exception);
    }
    return Binder.get(environment)
        .bind("factory.logwatch", LogWatchProperties.class)
        .orElseThrow(() -> new IllegalStateException("factory.logwatch is not configured"));
  }
}
