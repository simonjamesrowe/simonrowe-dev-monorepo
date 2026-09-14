package com.simonrowe.factory.logwatch.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.logwatch.domain.LogLine;
import com.simonrowe.factory.logwatch.domain.LogSignature;
import com.simonrowe.factory.logwatch.domain.Severity;
import com.simonrowe.factory.logwatch.signature.SignatureExtractor;
import java.time.Instant;
import java.util.ArrayList;
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

  private static final String TEMPORAL_COMMITTED_TRANSACTION =
      "{\"level\":\"error\",\"ts\":\"2026-09-13T20:04:46.645Z\",\"msg\":\"Operation failed "
          + "with internal error.\",\"error\":\"UpdateTaskQueue operation failed. Failed to "
          + "commit transaction. Error: sql: transaction has already been committed or rolled "
          + "back\",\"error-type\":\"serviceerror.Unavailable\",\"operation\":"
          + "\"UpdateTaskQueue\"}";

  private static final String TEMPORAL_LOST_CONNECTION =
      "{\"level\":\"error\",\"ts\":\"2026-09-13T20:04:46.730Z\",\"msg\":\"Operation failed "
          + "with internal error.\",\"error\":\"database connection lost: driver: bad "
          + "connection\",\"error-type\":\"serviceerror.Unavailable\",\"operation\":"
          + "\"UpdateTaskQueue\"}";

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

  /**
   * The reason muting is decided across the rule list rather than one rule at a time.
   *
   * <p>These three lines are SIM-28, verbatim from Loki. They share a {@code msg}, so they are
   * one group keyed on one logger, and no single phrase covers all three: Temporal reports the
   * same deploy-time teardown as a cancelled context, as a finished SQL transaction and as a
   * lost connection. Under one-rule-at-a-time matching the group was audible with three of its
   * five messages already disowned, and was re-filed every night for a week.
   */
  @Test
  @DisplayName("a group whose messages take several rules between them is muted")
  void mutesTheGroupThatNoSingleRuleCovers() {
    assertMuted(
        "simonrowe-dev-monorepo-temporal-1",
        Severity.ERROR,
        TEMPORAL_INTERNAL_ERROR,
        TEMPORAL_COMMITTED_TRANSACTION,
        TEMPORAL_LOST_CONNECTION);

    assertThat(
            properties.mutedBy(
                group(
                    "simonrowe-dev-monorepo-temporal-1",
                    Severity.ERROR,
                    TEMPORAL_INTERNAL_ERROR,
                    TEMPORAL_COMMITTED_TRANSACTION,
                    TEMPORAL_LOST_CONNECTION)))
        .as("each rule that disowned part of the group has to be named in the run detail")
        .hasSize(3);
  }

  /**
   * And the safety property that makes the above acceptable. One message no rule claims and the
   * whole group is filed — so a real fault appearing under a muted logger un-mutes it on the
   * next scan rather than riding out of sight beside the noise it resembles.
   */
  @Test
  @DisplayName("one unclaimed message in a group makes the whole group audible again")
  void doesNotMuteTheGroupWithOneUnclaimedMessage() {
    assertAudible(
        "simonrowe-dev-monorepo-temporal-1",
        Severity.ERROR,
        TEMPORAL_INTERNAL_ERROR,
        TEMPORAL_COMMITTED_TRANSACTION,
        TEMPORAL_LOST_CONNECTION,
        "{\"level\":\"error\",\"ts\":\"2026-09-13T20:04:47.001Z\",\"msg\":\"Operation "
            + "failed with internal error.\",\"error\":\"UpdateTaskQueue operation failed. "
            + "Error: no space left on device\",\"error-type\":"
            + "\"serviceerror.Unavailable\",\"operation\":\"UpdateTaskQueue\"}");
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

  /**
   * The second safety constraint, and the one no realistic fixture can reach through
   * {@code SignatureExtractor}: a group with more distinct messages than {@code MAX_VARIANTS}
   * lists only the first five, so a rule that matches all five still knows nothing about the
   * rest. Same distinction, and the same reason, as the per-run cap's veto over the absence
   * sweep — the cap limits what is listed, not what was seen.
   */
  @Test
  @DisplayName(
      "a group whose variants were capped is never muted, however well the visible ones match")
  void neverMutesGroupsItCannotFullySee() {
    LogWatchProperties.Ignore rule =
        new LogWatchProperties.Ignore("noise", "temporal", "context canceled");
    List<LogSignature.Variant> visible =
        List.of(new LogSignature.Variant("a context canceled", 3, "a context canceled"));

    assertThat(rule.mutes(signatureWith(visible, 1))).isTrue();
    assertThat(rule.mutes(signatureWith(visible, 9))).isFalse();

    // And through the call a scan actually makes, where a second rule covering the same phrase
    // must not be able to make up for what neither of them can see.
    assertThat(properties.mutedBy(signatureWith(visible, 1))).isNotEmpty();
    assertThat(properties.mutedBy(signatureWith(visible, 9))).isEmpty();
  }

  /**
   * A {@code LogSignature} replayed from a Temporal history serialized before this feature
   * carries no variants at all. Falling back to the leader is deliberate: refusing to mute would
   * re-file the very noise an operator has already disowned.
   */
  @Test
  @DisplayName("a variant-less signature falls back to its leader rather than refusing to mute")
  void mutesReplayedSignatureWithNoVariants() {
    LogWatchProperties.Ignore rule =
        new LogWatchProperties.Ignore("noise", "temporal", "context canceled");

    assertThat(rule.mutes(signatureWith(List.of(), 0))).isTrue();
    assertThat(rule.mutes(null)).isFalse();

    assertThat(properties.mutedBy(signatureWith(List.of(), 0))).isNotEmpty();
    assertThat(properties.mutedBy(null)).isEmpty();
  }

  private LogSignature signatureWith(
      final List<LogSignature.Variant> variants, final int distinctVariants) {
    return new LogSignature(
        "a context canceled",
        Severity.ERROR,
        "simonrowe-dev-monorepo-temporal-1",
        6,
        WHEN,
        WHEN,
        "a context canceled",
        "logger:Operation failed.",
        variants,
        distinctVariants);
  }

  private void assertMuted(final String container, final Severity severity, final String... raw) {
    LogSignature signature = group(container, severity, raw);
    assertThat(properties.mutedBy(signature))
        .as("expected the shipped rules to mute: %s", String.join(" | ", raw))
        .isNotEmpty();
  }

  private void assertAudible(
      final String container, final Severity severity, final String... raw) {
    LogSignature signature = group(container, severity, raw);
    assertThat(properties.mutedBy(signature))
        .as("expected no rule to mute: %s", String.join(" | ", raw))
        .isEmpty();
  }

  /**
   * Builds the thing a rule is actually applied to. Grouping is what turns lines into one
   * problem with several distinct messages, and several distinct messages is the whole subject
   * of the union test below — so the fixtures go through {@code SignatureExtractor} rather than
   * being constructed by hand.
   */
  private LogSignature group(
      final String container, final Severity severity, final String... raw) {
    List<LogLine> lines = new ArrayList<>();
    for (String line : raw) {
      // Twice each, because a single occurrence never reaches the filter in production either.
      lines.add(new LogLine(container, WHEN, severity, line));
      lines.add(new LogLine(container, WHEN, severity, line));
    }
    List<LogSignature> grouped = SignatureExtractor.group(lines);
    assertThat(grouped)
        .as("these lines must land in ONE group, or the test is not asking the question it looks"
            + " like it is asking")
        .hasSize(1);
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
