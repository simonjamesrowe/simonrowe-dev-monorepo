package com.simonrowe.factory.logwatch.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.workflow.LinearActivities;
import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.logwatch.config.LogWatchTaskQueues;
import com.simonrowe.factory.logwatch.domain.LogSignature;
import com.simonrowe.factory.logwatch.domain.LogWatchRequest;
import com.simonrowe.factory.logwatch.domain.LogWatchResult;
import com.simonrowe.factory.logwatch.domain.LogWatchStatus;
import com.simonrowe.factory.logwatch.domain.Severity;
import com.simonrowe.factory.logwatch.domain.SourceHealth;
import com.simonrowe.factory.logwatch.domain.Trigger;
import com.simonrowe.factory.logwatch.persistence.LogWatchRunRecord;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** The scan's decision-making, exercised against Temporal's test environment. */
class LogWatchWorkflowTest {

  private static final Instant FROM = Instant.parse("2026-08-31T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-09-01T00:00:00Z");

  private TestWorkflowEnvironment environment;
  private LogWatchActivities activities;
  private LinearActivities linear;
  private LogWatchWorkflow workflow;

  @BeforeEach
  void setUp() {
    setUpWithWorkflowId("test-logwatch");
  }

  /**
   * Registers plain adapters rather than the Mockito mocks themselves.
   *
   * <p>Temporal rejects a mock directly: {@code POJOActivityImplMetadata} sees the
   * {@code @ActivityMethod} annotations inherited onto the generated proxy's methods and throws
   * "This annotation can be used only on the interface method it implements". The adapters are
   * the same workaround {@code CveFixWorkflowTest} uses.
   */
  private void setUpWithWorkflowId(final String workflowId) {
    environment = TestWorkflowEnvironment.newInstance();
    activities = Mockito.mock(LogWatchActivities.class);
    linear = Mockito.mock(LinearActivities.class);

    Worker worker = environment.newWorker(LogWatchTaskQueues.LOG_WATCH);
    worker.registerWorkflowImplementationTypes(LogWatchWorkflowImpl.class);
    worker.registerActivitiesImplementations(new LogWatchActivitiesAdapter(activities));
    // The sink runs on its own queue, exactly as it does in production.
    Worker linearWorker = environment.newWorker(LinearTaskQueues.LINEAR);
    linearWorker.registerActivitiesImplementations(new LinearActivitiesAdapter(linear));
    environment.start();

    workflow =
        environment
            .getWorkflowClient()
            .newWorkflowStub(
                LogWatchWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(LogWatchTaskQueues.LOG_WATCH)
                    .setWorkflowId(workflowId)
                    .build());

    when(linear.fileIssue(any()))
        .thenReturn(
            new FiledIssue(
                FilingDecision.FILED_NEW, "id", "SIM-1", "https://linear.app/SIM-1", "fp"));
  }

  @AfterEach
  void tearDown() {
    environment.close();
  }

  private record LogWatchActivitiesAdapter(LogWatchActivities delegate)
      implements LogWatchActivities {

    @Override
    public ScanObservation observe(final Instant from, final Instant to) {
      return delegate.observe(from, to);
    }

    @Override
    public void recordRun(final LogWatchRunRecord record) {
      delegate.recordRun(record);
    }
  }

  private record LinearActivitiesAdapter(LinearActivities delegate) implements LinearActivities {

    @Override
    public FiledIssue fileIssue(final IssueFiling filing) {
      return delegate.fileIssue(filing);
    }

    @Override
    public void attachUrl(final String issueId, final String url, final String title) {
      delegate.attachUrl(issueId, url, title);
    }
  }

  /**
   * The central behaviour of the whole module.
   *
   * <p>An unusable source must never be reported as a clean scan, however empty the read was.
   */
  @Test
  @DisplayName("a silent source is SOURCE_UNHEALTHY, never NO_FINDINGS")
  void silentSourceIsNotReportedAsClean() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                new SourceHealth(
                    SourceHealth.Status.SILENT,
                    SourceHealth.Tier.ALLOY_COMPONENT,
                    "Alloy reports its loki.write component unhealthy: 429 limit: 0 bytes/sec"),
                List.of(), 0, false, 0, 0));

    LogWatchResult result = workflow.run(request(false));

    assertThat(result.status()).isEqualTo(LogWatchStatus.SOURCE_UNHEALTHY);
    assertThat(result.status()).isNotEqualTo(LogWatchStatus.NO_FINDINGS);
    assertThat(result.detail()).contains("0 bytes/sec");
  }

  @Test
  @DisplayName("a source-health failure is filed through the same sink as any other finding")
  void silentSourceFilesAnIssue() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                new SourceHealth(
                    SourceHealth.Status.SILENT, SourceHealth.Tier.ALLOY_COMPONENT, "429"),
                List.of(), 0, false, 0, 0));

    workflow.run(request(false));

    ArgumentCaptor<IssueFiling> filing = ArgumentCaptor.forClass(IssueFiling.class);
    verify(linear).fileIssue(filing.capture());
    assertThat(filing.getValue().producer()).isEqualTo("logwatch");
    // Carries the status but NOT the evidence: a 429 whose byte counts differ every run must
    // stay one recurring ticket, while a quota problem and a rejected credential stay separate.
    assertThat(filing.getValue().keyParts()).containsExactly("source-health", "SILENT");
  }

  @Test
  @DisplayName("with the source alive and nothing found, the scan reports a genuine all-clear")
  void aliveSourceWithNoSignaturesIsNoFindings() {
    when(activities.observe(any(), any()))
        .thenReturn(new ScanObservation(alive(), List.of(), 120, false, 9, 0));

    LogWatchResult result = workflow.run(request(false));

    assertThat(result.status()).isEqualTo(LogWatchStatus.NO_FINDINGS);
    verify(linear, never()).fileIssue(any());
  }

  @Test
  @DisplayName("each signature is filed with the signature as its key, never the title")
  void filesOneIssuePerSignatureKeyedOnTheSignature() {
    when(activities.observe(any(), any()))
        .thenReturn(new ScanObservation(alive(), List.of(signature("boom")), 10, false, 5, 0));

    LogWatchResult result = workflow.run(request(false));

    ArgumentCaptor<IssueFiling> filing = ArgumentCaptor.forClass(IssueFiling.class);
    verify(linear).fileIssue(filing.capture());
    assertThat(filing.getValue().keyParts()).containsExactly("backend", "boom");
    assertThat(filing.getValue().title()).isNotEqualTo("boom");
    assertThat(result.status()).isEqualTo(LogWatchStatus.COMPLETED);
    assertThat(result.issueUrls()).containsExactly("https://linear.app/SIM-1");
  }

  @Test
  @DisplayName("a dry run creates and comments on nothing whatsoever")
  void dryRunFilesNothing() {
    when(activities.observe(any(), any()))
        .thenReturn(new ScanObservation(alive(), List.of(signature("boom")), 10, false, 5, 0));

    LogWatchResult result = workflow.run(request(true));

    verify(linear, never()).fileIssue(any());
    assertThat(result.detail()).contains("would have filed");
    assertThat(result.issueUrls()).isEmpty();
  }

  @Test
  @DisplayName("a dry run over a silent source also files nothing")
  void dryRunDoesNotFileSourceHealthEither() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                new SourceHealth(
                    SourceHealth.Status.SILENT, SourceHealth.Tier.ALLOY_COMPONENT, "429"),
                List.of(), 0, false, 0, 0));

    LogWatchResult result = workflow.run(request(true));

    verify(linear, never()).fileIssue(any());
    assertThat(result.status()).isEqualTo(LogWatchStatus.SOURCE_UNHEALTHY);
  }

  @Test
  @DisplayName("with the sink disabled the run completes rather than stalling on a dead queue")
  void filingDisabledStillCompletes() {
    when(activities.observe(any(), any()))
        .thenReturn(new ScanObservation(alive(), List.of(signature("boom")), 10, false, 5, 0));

    LogWatchResult result =
        workflow.run(
            new LogWatchRequest(FROM, TO, Trigger.SCHEDULE, false, false));

    verify(linear, never()).fileIssue(any());
    assertThat(result.status()).isEqualTo(LogWatchStatus.COMPLETED);
  }

  @Test
  @DisplayName("losses to the cap and to the line budget are both reported, never hidden")
  void reportsItsOwnLosses() {
    when(activities.observe(any(), any()))
        .thenReturn(new ScanObservation(alive(), List.of(signature("boom")), 5000, true, 9, 12));

    LogWatchResult result = workflow.run(request(true));

    assertThat(result.detail()).contains("12 more were dropped");
    assertThat(result.detail()).contains("line budget");
    assertThat(result.truncated()).isTrue();
    assertThat(result.signaturesDropped()).isEqualTo(12);
  }

  @Test
  @DisplayName("the run record is keyed on the run id, not the workflow id")
  void recordsTheRunUnderTheRunId() {
    environment.close();
    setUpWithWorkflowId("logwatch");
    when(activities.observe(any(), any()))
        .thenReturn(new ScanObservation(alive(), List.of(), 1, false, 5, 0));

    workflow.run(request(false));

    ArgumentCaptor<LogWatchRunRecord> record =
        ArgumentCaptor.forClass(LogWatchRunRecord.class);
    verify(activities).recordRun(record.capture());
    assertThat(record.getValue().workflowId()).isEqualTo("logwatch");
    // Keying on the workflow id would collapse every scheduled run into one document, because
    // the scheduled workflow id is stable. This is the deploy_runs lesson.
    assertThat(record.getValue().id()).isNotEqualTo("logwatch");
    assertThat(record.getValue().id()).isNotBlank();
  }

  private LogWatchRequest request(final boolean dryRun) {
    return new LogWatchRequest(
        FROM, TO, dryRun ? Trigger.DRY_RUN : Trigger.SCHEDULE, dryRun, true);
  }

  private static SourceHealth alive() {
    return new SourceHealth(
        SourceHealth.Status.ALIVE, SourceHealth.Tier.ALLOY_COMPONENT, "healthy");
  }

  private static LogSignature signature(final String text) {
    return new LogSignature(text, Severity.ERROR, "backend", 4, FROM, TO, "raw " + text);
  }
}
