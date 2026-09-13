package com.simonrowe.factory.logwatch.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.logwatch.config.LogWatchProperties;
import com.simonrowe.factory.logwatch.domain.LogLine;
import com.simonrowe.factory.logwatch.domain.LogSignature;
import com.simonrowe.factory.logwatch.domain.LogWatchStatus;
import com.simonrowe.factory.logwatch.domain.Severity;
import com.simonrowe.factory.logwatch.domain.SourceHealth;
import com.simonrowe.factory.logwatch.domain.Trigger;
import com.simonrowe.factory.logwatch.loki.AlloyHealthClient;
import com.simonrowe.factory.logwatch.loki.LokiClient;
import com.simonrowe.factory.logwatch.loki.LokiException;
import com.simonrowe.factory.logwatch.persistence.LogWatchRunRecord;
import com.simonrowe.factory.logwatch.persistence.LogWatchRunRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The read-group-filter-cap step, which is where the module's thresholds are actually applied. */
class LogWatchActivitiesImplTest {

  private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-09-02T00:00:00Z");

  private LokiClient loki;
  private AlloyHealthClient alloy;
  private LogWatchRunRepository repository;

  @BeforeEach
  void setUp() {
    loki = mock(LokiClient.class);
    alloy = mock(AlloyHealthClient.class);
    repository = mock(LogWatchRunRepository.class);
    when(alloy.writeHealth()).thenReturn(new AlloyHealthClient.WriteHealth(true, Optional.empty()));
  }

  private LogWatchActivitiesImpl activities(final int minimumOccurrences, final int maxPerRun,
      final int lineBudget) {
    return activities(minimumOccurrences, maxPerRun, lineBudget, List.of());
  }

  private LogWatchActivitiesImpl activities(final int minimumOccurrences, final int maxPerRun,
      final int lineBudget, final List<LogWatchProperties.Ignore> ignore) {
    return new LogWatchActivitiesImpl(
        loki,
        alloy,
        repository,
        new LogWatchProperties(
            true, minimumOccurrences, maxPerRun, null, lineBudget, 3, null, null, null,
            null, ignore));
  }

  @Test
  @DisplayName("a signature below the occurrence floor is discarded")
  void appliesTheMinimumOccurrenceFilter() {
    when(loki.linesIn(any(), any(), anyInt()))
        .thenReturn(
            List.of(
                line("backend", "level=error msg=\"twice\" id=1"),
                line("backend", "level=error msg=\"twice\" id=2"),
                line("backend", "level=error msg=\"once\"")));
    when(loki.distinctContainers(any(), any())).thenReturn(5);

    ScanObservation observation = activities(2, 5, 5000).observe(FROM, TO);

    assertThat(observation.signatures()).hasSize(1);
    assertThat(observation.signatures().getFirst().occurrences()).isEqualTo(2);
  }

  @Test
  @DisplayName("the cap keeps the most severe and reports how many it dropped")
  void appliesTheCapAndReportsTheLoss() {
    List<LogLine> lines = new ArrayList<>();
    // Three distinct WARN problems, each occurring twice, and one ERROR problem occurring twice.
    for (int problem = 0; problem < 3; problem++) {
      lines.add(line("backend", "WARN thing " + (char) ('a' + problem) + " failed"));
      lines.add(line("backend", "WARN thing " + (char) ('a' + problem) + " failed"));
    }
    lines.add(line("backend", "ERROR the important one"));
    lines.add(line("backend", "ERROR the important one"));
    when(loki.linesIn(any(), any(), anyInt())).thenReturn(lines);
    when(loki.distinctContainers(any(), any())).thenReturn(5);

    ScanObservation observation = activities(2, 2, 5000).observe(FROM, TO);

    assertThat(observation.signatures()).hasSize(2);
    assertThat(observation.signaturesDropped()).isEqualTo(2);
    // The ERROR must survive the cap regardless of how many WARNs outnumber it.
    assertThat(observation.signatures().getFirst().severity()).isEqualTo(Severity.ERROR);
  }

  @Test
  @DisplayName("hitting the line budget is reported as truncation, never as a complete read")
  void reportsTruncation() {
    when(loki.linesIn(any(), any(), anyInt()))
        .thenReturn(List.of(line("backend", "level=error msg=\"a\"")));
    when(loki.distinctContainers(any(), any())).thenReturn(5);

    // Budget of exactly 1, met exactly. Indistinguishable from being exceeded, so it counts as
    // truncated: over-reporting a complete read as partial is harmless, the reverse is not.
    assertThat(activities(1, 5, 1).observe(FROM, TO).truncated()).isTrue();
    assertThat(activities(1, 5, 5000).observe(FROM, TO).truncated()).isFalse();
  }

  @Test
  @DisplayName("a Loki failure becomes a source-health verdict, not a crashed activity")
  void lokiFailureIsRecordedRatherThanThrown() {
    when(loki.linesIn(any(), any(), anyInt())).thenThrow(new LokiException("connection refused"));

    ScanObservation observation = activities(2, 5, 5000).observe(FROM, TO);

    // The run must still record that it could not see, rather than dying and leaving nothing to
    // read on the console.
    assertThat(observation.sourceHealth().status()).isEqualTo(SourceHealth.Status.UNREACHABLE);
    assertThat(observation.signatures()).isEmpty();
    assertThat(observation.linesRead()).isZero();
  }

  @Test
  @DisplayName("Alloy's unhealthy verdict makes an otherwise-normal read unusable")
  void alloyUnhealthyMakesTheScanUnusable() {
    when(alloy.writeHealth())
        .thenReturn(
            new AlloyHealthClient.WriteHealth(true, Optional.of("429 limit: 0 bytes/sec")));
    when(loki.linesIn(any(), any(), anyInt())).thenReturn(List.of());
    when(loki.distinctContainers(any(), any())).thenReturn(0);

    ScanObservation observation = activities(2, 5, 5000).observe(FROM, TO);

    assertThat(observation.sourceHealth().usable()).isFalse();
    assertThat(observation.sourceHealth().evidence()).contains("0 bytes/sec");
  }

  @Test
  void recordRunPersists() {
    LogWatchRunRecord record =
        new LogWatchRunRecord(
            "run-1", "logwatch", FROM, TO, LogWatchStatus.COMPLETED, Trigger.SCHEDULE,
            FROM, TO, 10, false, 5, 1, 0, SourceHealth.Status.ALIVE, "healthy", List.of(),
            "ok", List.of());

    activities(2, 5, 5000).recordRun(record);

    verify(repository).save(record);
  }

  @Test
  @DisplayName("signatures come back in filing order, most severe first")
  void ordersSignaturesForFiling() {
    when(loki.linesIn(any(), any(), anyInt()))
        .thenReturn(
            List.of(
                line("backend", "WARN low priority"),
                line("backend", "WARN low priority"),
                line("backend", "ERROR high priority"),
                line("backend", "ERROR high priority")));
    when(loki.distinctContainers(any(), any())).thenReturn(5);

    List<LogSignature> signatures = activities(2, 5, 5000).observe(FROM, TO).signatures();

    assertThat(signatures).extracting(LogSignature::severity)
        .containsExactly(Severity.ERROR, Severity.WARN);
  }

  // ---------------------------------------------------------------------------
  // Muting third-party noise
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("a group matching an ignore rule is not filed, and the run says which rule did it")
  void mutesThirdPartyNoise() {
    when(loki.linesIn(any(), any(), anyInt()))
        .thenReturn(
            List.of(
                line("temporal", "ERROR msg=\"Operation failed\" error=\"context canceled\" a=1"),
                line("temporal", "ERROR msg=\"Operation failed\" error=\"context canceled\" a=2"),
                line("backend", "ERROR something ours broke id=1"),
                line("backend", "ERROR something ours broke id=2")));
    when(loki.distinctContainers(any(), any())).thenReturn(5);

    ScanObservation observation =
        activities(2, 5, 5000, List.of(temporalCancelNoise())).observe(FROM, TO);

    assertThat(observation.signatures())
        .extracting(LogSignature::container)
        .containsExactly("backend");
    assertThat(observation.mutedSignatures()).isEqualTo(1);
    assertThat(observation.mutedBy()).containsExactly("Temporal cancel churn");
  }

  /**
   * The cap is what the module reports as "I could not fit everything", and the absence sweep
   * refuses to close anything when it is non-zero. A muted group is not that: it is excluded on
   * purpose on every run, so counting it as dropped would make the sweep permanently inert on any
   * stack that mutes anything at all.
   */
  @Test
  @DisplayName("a muted group is not counted as dropped by the per-run cap")
  void mutingIsNotDropping() {
    when(loki.linesIn(any(), any(), anyInt()))
        .thenReturn(
            List.of(
                line("temporal", "ERROR msg=\"poll\" error=\"context canceled\" a=1"),
                line("temporal", "ERROR msg=\"poll\" error=\"context canceled\" a=2")));
    when(loki.distinctContainers(any(), any())).thenReturn(5);

    ScanObservation observation =
        activities(2, 5, 5000, List.of(temporalCancelNoise())).observe(FROM, TO);

    assertThat(observation.signaturesDropped()).isZero();
    assertThat(observation.mutedSignatures()).isEqualTo(1);
  }

  /**
   * The rule is confined to a container for a reason: "context canceled" is a phrase our own code
   * could legitimately log, and a rule written to disown Temporal's churn must not disown ours.
   */
  @Test
  @DisplayName("an ignore rule does not reach outside the container it names")
  void ignoreRuleIsConfinedToItsContainer() {
    when(loki.linesIn(any(), any(), anyInt()))
        .thenReturn(
            List.of(
                line("backend", "ERROR upload failed error=\"context canceled\" id=1"),
                line("backend", "ERROR upload failed error=\"context canceled\" id=2")));
    when(loki.distinctContainers(any(), any())).thenReturn(5);

    ScanObservation observation =
        activities(2, 5, 5000, List.of(temporalCancelNoise())).observe(FROM, TO);

    assertThat(observation.signatures()).hasSize(1);
    assertThat(observation.mutedSignatures()).isZero();
  }

  /**
   * One logger emitting two different faults is the standing objection to grouping by source key,
   * and it is exactly the hole a leader-only mute would open: the noisy message is the frequent
   * one, so it becomes the group's leader, and a real failure rides out of sight underneath it.
   */
  @Test
  @DisplayName("a group is not muted when only some of its messages are noise")
  void keepsGroupsThatAlsoCarryRealFailures() {
    // Temporal's own JSON, so SourceKeyExtractor keys on the `msg` field and all four lines land
    // in ONE group with two variants - which is the shape the assertion is about.
    when(loki.linesIn(any(), any(), anyInt()))
        .thenReturn(
            List.of(
                line("temporal", temporalJson("Operation failed.", "context canceled")),
                line("temporal", temporalJson("Operation failed.", "context canceled")),
                line("temporal", temporalJson("Operation failed.", "disk full")),
                line("temporal", temporalJson("Operation failed.", "disk full"))));
    when(loki.distinctContainers(any(), any())).thenReturn(5);

    ScanObservation observation =
        activities(2, 5, 5000, List.of(temporalCancelNoise())).observe(FROM, TO);

    assertThat(observation.mutedSignatures()).isZero();
    assertThat(observation.signatures()).hasSize(1);
  }

  @Test
  @DisplayName("a rule with no `contains` is inert rather than muting its whole container")
  void ruleWithoutPhraseMutesNothing() {
    when(loki.linesIn(any(), any(), anyInt()))
        .thenReturn(
            List.of(
                line("temporal", "ERROR msg=\"anything at all\" a=1"),
                line("temporal", "ERROR msg=\"anything at all\" a=2")));
    when(loki.distinctContainers(any(), any())).thenReturn(5);

    ScanObservation observation =
        activities(
                2,
                5,
                5000,
                List.of(new LogWatchProperties.Ignore("too broad", "temporal", "  ")))
            .observe(FROM, TO);

    assertThat(observation.signatures()).hasSize(1);
    assertThat(observation.mutedSignatures()).isZero();
  }

  /**
   * Muting runs before the cap. Third-party noise is high-volume by nature, so muting afterwards
   * would let it occupy the five slots it is being muted from and crowd out first-party findings.
   */
  @Test
  @DisplayName("muted groups do not consume slots in the per-run cap")
  void mutingHappensBeforeTheCap() {
    // Three distinct noise groups, each MORE frequent than the one first-party finding, against a
    // cap of two. Muting last, the three would take both slots on occurrence count and the
    // backend problem would be reported as dropped.
    List<LogLine> lines = new ArrayList<>();
    for (String message : List.of("Poll failed.", "Task queue unavailable.", "Shard closed.")) {
      for (int i = 0; i < 3; i++) {
        lines.add(line("temporal", temporalJson(message, "context canceled")));
      }
    }
    lines.add(line("backend", "ERROR ours broke id=1"));
    lines.add(line("backend", "ERROR ours broke id=2"));
    when(loki.linesIn(any(), any(), anyInt())).thenReturn(lines);
    when(loki.distinctContainers(any(), any())).thenReturn(5);

    ScanObservation observation =
        activities(2, 2, 5000, List.of(temporalCancelNoise())).observe(FROM, TO);

    assertThat(observation.signatures())
        .extracting(LogSignature::container)
        .containsExactly("backend");
    assertThat(observation.signaturesDropped()).isZero();
    assertThat(observation.mutedSignatures()).isEqualTo(3);
  }

  private static LogWatchProperties.Ignore temporalCancelNoise() {
    return new LogWatchProperties.Ignore(
        "Temporal cancel churn", "temporal", "context canceled");
  }

  private static String temporalJson(final String message, final String error) {
    return "{\"level\":\"error\",\"ts\":\"2026-09-12T08:58:41.026Z\",\"msg\":\"" + message
        + "\",\"error\":\"" + error + "\"}";
  }

  private static LogLine line(final String container, final String raw) {
    Severity severity = raw.contains("ERROR") || raw.contains("error")
        ? Severity.ERROR : Severity.WARN;
    return new LogLine(container, FROM, severity, raw);
  }
}
