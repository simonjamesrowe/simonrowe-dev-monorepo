package com.simonrowe.factory.staticanalysis.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.linear.domain.AbsenceSweep;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.FilingMode;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.domain.LinkedIssue;
import com.simonrowe.factory.linear.domain.SweepReport;
import com.simonrowe.factory.linear.domain.SweptIssue;
import com.simonrowe.factory.linear.workflow.LinearActivities;
import com.simonrowe.factory.staticanalysis.config.StaticAnalysisTaskQueues;
import com.simonrowe.factory.staticanalysis.domain.AnalysisHealth;
import com.simonrowe.factory.staticanalysis.domain.FindingGroup;
import com.simonrowe.factory.staticanalysis.domain.FindingKind;
import com.simonrowe.factory.staticanalysis.domain.QualityGateCondition;
import com.simonrowe.factory.staticanalysis.domain.SonarIssue;
import com.simonrowe.factory.staticanalysis.domain.SonarProject;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisRequest;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisResult;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisStatus;
import com.simonrowe.factory.staticanalysis.domain.Trigger;
import com.simonrowe.factory.staticanalysis.persistence.StaticAnalysisRunRecord;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StaticAnalysisWorkflowTest {

  private static final SonarProject PROJECT =
      new SonarProject("https://sonarcloud.io", "simonjamesrowe", "proj", "main");

  private static final AnalysisHealth FRESH =
      new AnalysisHealth(AnalysisHealth.Status.FRESH, "The analysis is of main's head.",
          "5c893af3", Instant.parse("2026-10-20T14:18:11Z"), "5c893af3");

  private static final FindingGroup RULE_GROUP =
      new FindingGroup(FindingKind.RULE, "frontend", "typescript:S6845",
          "Non-interactive DOM elements should not have the `tabindex` attribute",
          List.of(new SonarIssue("AaFQ", "typescript:S6845", "MAJOR", "CODE_SMELL",
              "frontend/src/ReadAlong.tsx", 252, "`tabIndex` should only be declared")),
          null);

  private static final FindingGroup GATE_GROUP =
      FindingGroup.qualityGate(new QualityGateCondition("new_coverage", "LT", "80", "71.2"));

  private StaticAnalysisActivities activities;
  private LinearActivities linear;

  @BeforeEach
  void setUp() {
    activities = mock(StaticAnalysisActivities.class);
    linear = mock(LinearActivities.class);
    when(linear.fileIssue(any())).thenReturn(
        new FiledIssue(FilingDecision.FILED_NEW, "id", "SIM-90", "https://linear/SIM-90", "fp"));
    // Stubbed in every test, not only the sweep ones: an unstubbed mock returns null, the
    // workflow NPEs, and Temporal retries the workflow task for ever - a hang, not a failure.
    when(linear.sweepResolved(any())).thenReturn(SweepReport.none());
  }

  @Test
  void filesEachGroupWithItsStructuredKeyAndRefreshMode() {
    when(activities.observe()).thenReturn(observation(List.of(GATE_GROUP, RULE_GROUP), 0,
        List.of(), false));

    StaticAnalysisResult result = run(request(false, true));

    assertThat(result.status()).isEqualTo(StaticAnalysisStatus.COMPLETED);
    ArgumentCaptor<IssueFiling> filings = ArgumentCaptor.forClass(IssueFiling.class);
    verify(linear, times(2)).fileIssue(filings.capture());
    assertThat(filings.getAllValues()).extracting(IssueFiling::keyParts).containsExactly(
        List.of("quality-gate", "new_coverage"),
        List.of("rule", "frontend", "typescript:S6845"));
    assertThat(filings.getAllValues()).allSatisfy(filing -> {
      assertThat(filing.producer()).isEqualTo("staticanalysis");
      assertThat(filing.mode()).isEqualTo(FilingMode.REFRESH);
    });
    assertThat(filings.getAllValues().get(1).body())
        .contains("frontend/src/ReadAlong.tsx:252", "NOSONAR", "5c893af3");
    assertThat(result.issueUrls()).containsExactly("https://linear/SIM-90",
        "https://linear/SIM-90");
  }

  @Test
  @DisplayName("groups the cap dropped are named to the sweep, so their tickets stay open")
  void namesDroppedGroupsToTheSweep() {
    List<List<String>> dropped = List.of(List.of("rule", "backend", "java:S1192"));
    when(activities.observe()).thenReturn(observation(List.of(RULE_GROUP), 1, dropped, false));
    when(linear.sweepResolved(any())).thenReturn(new SweepReport(1,
        List.of(new SweptIssue("fp", List.of("rule", "backend", "java:S1"), "SIM-12",
            "https://linear/SIM-12")), 0, 0, 0, false));

    StaticAnalysisResult result = run(request(false, true));

    ArgumentCaptor<AbsenceSweep> sweep = ArgumentCaptor.forClass(AbsenceSweep.class);
    verify(linear).sweepResolved(sweep.capture());
    assertThat(sweep.getValue().producer()).isEqualTo("staticanalysis");
    assertThat(sweep.getValue().presentKeyParts()).isEqualTo(dropped);
    assertThat(sweep.getValue().dryRun()).isFalse();
    assertThat(result.resolvedUrls()).containsExactly("https://linear/SIM-12");
    assertThat(result.detail()).contains("held back by the per-run cap", "closed 1", "SIM-12");
  }

  @Test
  @DisplayName("a stale analysis files one ticket saying so, and never sweeps")
  void staleAnalysisFilesItsOwnTicketAndNeverSweeps() {
    AnalysisHealth stale = new AnalysisHealth(AnalysisHealth.Status.STALE,
        "main's head f9b7d532 has gone 46h without an analysis", "5c893af3",
        Instant.parse("2026-10-20T14:18:11Z"), "f9b7d532");
    when(activities.observe()).thenReturn(AnalysisObservation.unusable(stale, PROJECT));

    StaticAnalysisResult result = run(request(false, true));

    assertThat(result.status()).isEqualTo(StaticAnalysisStatus.SOURCE_UNHEALTHY);
    ArgumentCaptor<IssueFiling> filing = ArgumentCaptor.forClass(IssueFiling.class);
    verify(linear).fileIssue(filing.capture());
    // The status, never the evidence: the evidence names commits that change every night.
    assertThat(filing.getValue().keyParts()).containsExactly("source-health", "STALE");
    assertThat(filing.getValue().title()).contains("stopped analysing main");
    assertThat(filing.getValue().body()).contains("f9b7d532");
    verify(linear, never()).sweepResolved(any());
  }

  @Test
  @DisplayName("a dry run files nothing, and its sweep preview treats every group as present")
  void dryRunFilesNothingAndPreviewsTheSweepHonestly() {
    when(activities.observe()).thenReturn(observation(List.of(GATE_GROUP, RULE_GROUP), 0,
        List.of(), false));

    StaticAnalysisResult result = run(request(true, true));

    verify(linear, never()).fileIssue(any());
    ArgumentCaptor<AbsenceSweep> sweep = ArgumentCaptor.forClass(AbsenceSweep.class);
    verify(linear).sweepResolved(sweep.capture());
    assertThat(sweep.getValue().dryRun()).isTrue();
    // Nothing advanced these fingerprints' lastSeenAt, so without naming them the preview would
    // report every open ticket as closable.
    assertThat(sweep.getValue().presentKeyParts())
        .containsExactly(GATE_GROUP.keyParts(), RULE_GROUP.keyParts());
    assertThat(result.detail()).startsWith("Dry run: would have filed 2");
  }

  @Test
  void truncatedReadNeverSweeps() {
    when(activities.observe()).thenReturn(observation(List.of(RULE_GROUP), 0, List.of(), true));

    StaticAnalysisResult result = run(request(false, true));

    verify(linear, never()).sweepResolved(any());
    assertThat(result.detail()).contains("truncated");
  }

  @Test
  @DisplayName("a clean analysis still sweeps: it is the run that closes the last ticket")
  void cleanAnalysisSweeps() {
    when(activities.observe()).thenReturn(observation(List.of(), 0, List.of(), false));

    StaticAnalysisResult result = run(request(false, true));

    assertThat(result.status()).isEqualTo(StaticAnalysisStatus.NO_FINDINGS);
    verify(linear, never()).fileIssue(any());
    verify(linear).sweepResolved(any());
  }

  @Test
  void withTheSinkOffNothingIsScheduledOnTheLinearQueue() {
    when(activities.observe()).thenReturn(observation(List.of(RULE_GROUP), 0, List.of(), false));

    run(request(false, false));

    verify(linear, never()).fileIssue(any());
    verify(linear, never()).sweepResolved(any());
  }

  @Test
  void recordsEveryRun() {
    when(activities.observe()).thenReturn(observation(List.of(RULE_GROUP), 2,
        List.of(List.of("a"), List.of("b")), false));

    run(request(false, true));

    ArgumentCaptor<StaticAnalysisRunRecord> record =
        ArgumentCaptor.forClass(StaticAnalysisRunRecord.class);
    verify(activities).recordRun(record.capture());
    assertThat(record.getValue().status()).isEqualTo(StaticAnalysisStatus.COMPLETED);
    assertThat(record.getValue().groupsFound()).isEqualTo(3);
    assertThat(record.getValue().groupsDropped()).isEqualTo(2);
    assertThat(record.getValue().analysedRevision()).isEqualTo("5c893af3");
    assertThat(record.getValue().trigger()).isEqualTo(Trigger.MANUAL);
  }

  private static AnalysisObservation observation(
      final List<FindingGroup> groups,
      final int dropped,
      final List<List<String>> droppedKeyParts,
      final boolean truncated) {
    return new AnalysisObservation(FRESH, PROJECT, groups, dropped, droppedKeyParts, 4, 1,
        truncated);
  }

  private static StaticAnalysisRequest request(final boolean dryRun, final boolean linear) {
    return new StaticAnalysisRequest(Trigger.MANUAL, dryRun, linear, true,
        Duration.ofHours(12));
  }

  private StaticAnalysisResult run(final StaticAnalysisRequest request) {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker worker = environment.newWorker(StaticAnalysisTaskQueues.STATIC_ANALYSIS);
      worker.registerWorkflowImplementationTypes(StaticAnalysisWorkflowImpl.class);
      worker.registerActivitiesImplementations(new ActivitiesAdapter(activities));
      Worker linearWorker = environment.newWorker(LinearTaskQueues.LINEAR);
      linearWorker.registerActivitiesImplementations(new LinearActivitiesAdapter(linear));
      environment.start();
      StaticAnalysisWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
          StaticAnalysisWorkflow.class,
          WorkflowOptions.newBuilder()
              .setTaskQueue(StaticAnalysisTaskQueues.STATIC_ANALYSIS)
              .setWorkflowId("test-static-analysis-" + UUID.randomUUID())
              .build());
      return workflow.run(request);
    }
  }

  /** Mockito mocks are not activity implementations; Temporal needs the annotated interface. */
  private record ActivitiesAdapter(StaticAnalysisActivities delegate)
      implements StaticAnalysisActivities {

    @Override
    public AnalysisObservation observe() {
      return delegate.observe();
    }

    @Override
    public void recordRun(final StaticAnalysisRunRecord record) {
      delegate.recordRun(record);
    }
  }

  private record LinearActivitiesAdapter(LinearActivities delegate) implements LinearActivities {

    @Override
    public FiledIssue fileIssue(final IssueFiling filing) {
      return delegate.fileIssue(filing);
    }

    @Override
    public SweepReport sweepResolved(final AbsenceSweep sweep) {
      return delegate.sweepResolved(sweep);
    }

    @Override
    public void attachUrl(final String issueId, final String url, final String title) {
      delegate.attachUrl(issueId, url, title);
    }

    @Override
    public List<LinkedIssue> issuesLinkedTo(final String url) {
      return delegate.issuesLinkedTo(url);
    }

    @Override
    public void commentOn(final String issueId, final String body) {
      delegate.commentOn(issueId, body);
    }

    @Override
    public boolean complete(final String issueId, final String body) {
      return delegate.complete(issueId, body);
    }
  }
}
