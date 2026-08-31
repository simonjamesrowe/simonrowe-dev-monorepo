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
    return new LogWatchActivitiesImpl(
        loki,
        alloy,
        repository,
        new LogWatchProperties(
            true, minimumOccurrences, maxPerRun, null, lineBudget, 3, null, null));
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
            FROM, TO, 10, false, 5, 1, 0, SourceHealth.Status.ALIVE, "healthy", List.of(), "ok");

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

  private static LogLine line(final String container, final String raw) {
    Severity severity = raw.contains("ERROR") || raw.contains("error")
        ? Severity.ERROR : Severity.WARN;
    return new LogLine(container, FROM, severity, raw);
  }
}
