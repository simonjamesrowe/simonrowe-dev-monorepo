package com.simonrowe.factory.logwatch.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.linear.domain.AbsenceSweep;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.Fingerprint;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.domain.SweepReport;
import com.simonrowe.factory.linear.domain.SweptIssue;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/** The scan's decision-making, exercised against Temporal's test environment. */
class LogWatchWorkflowTest {

  private static final Instant FROM = Instant.parse("2026-08-31T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-09-01T00:00:00Z");
  private static final Duration QUIET_FOR = Duration.ofDays(7);

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

    // Stubbed for every test, not just the sweep ones. An unstubbed Mockito mock returns null,
    // the workflow NPEs on it, and Temporal retries a failed WORKFLOW TASK indefinitely — so the
    // symptom is a test that hangs forever rather than one that fails.
    when(linear.sweepResolved(any())).thenReturn(SweepReport.none());
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
    public SweepReport sweepResolved(final AbsenceSweep sweep) {
      return delegate.sweepResolved(sweep);
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
                List.of(), 0, false, 0, 0, 0, List.of(), List.of()));

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
                List.of(), 0, false, 0, 0, 0, List.of(), List.of()));

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
        .thenReturn(
            new ScanObservation(
                alive(), List.of(), 120, false, 9, 0, 0, List.of(), List.of()));

    LogWatchResult result = workflow.run(request(false));

    assertThat(result.status()).isEqualTo(LogWatchStatus.NO_FINDINGS);
    verify(linear, never()).fileIssue(any());
  }

  @Test
  @DisplayName("each signature is filed with the source key as its key, never the title")
  void filesOneIssuePerSignatureKeyedOnTheSignature() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), List.of(signature("boom")), 10, false, 5, 0, 0, List.of(), List.of()));

    LogWatchResult result = workflow.run(request(false));

    ArgumentCaptor<IssueFiling> filing = ArgumentCaptor.forClass(IssueFiling.class);
    verify(linear).fileIssue(filing.capture());
    assertThat(filing.getValue().keyParts()).containsExactly("backend", "ERROR", "logger:boom");
    assertThat(filing.getValue().title()).isNotEqualTo("boom");
    assertThat(result.status()).isEqualTo(LogWatchStatus.COMPLETED);
    assertThat(result.issueUrls()).containsExactly("https://linear.app/SIM-1");
  }

  @Test
  @DisplayName("a dry run creates and comments on nothing whatsoever")
  void dryRunFilesNothing() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), List.of(signature("boom")), 10, false, 5, 0, 0, List.of(), List.of()));

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
                List.of(), 0, false, 0, 0, 0, List.of(), List.of()));

    LogWatchResult result = workflow.run(request(true));

    verify(linear, never()).fileIssue(any());
    assertThat(result.status()).isEqualTo(LogWatchStatus.SOURCE_UNHEALTHY);
  }

  @Test
  @DisplayName("with the sink disabled the run completes rather than stalling on a dead queue")
  void filingDisabledStillCompletes() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), List.of(signature("boom")), 10, false, 5, 0, 0, List.of(), List.of()));

    LogWatchResult result =
        workflow.run(
            new LogWatchRequest(FROM, TO, Trigger.SCHEDULE, false, false, true, QUIET_FOR));

    verify(linear, never()).fileIssue(any());
    assertThat(result.status()).isEqualTo(LogWatchStatus.COMPLETED);
  }

  @Test
  @DisplayName("losses to the cap and to the line budget are both reported, never hidden")
  void reportsItsOwnLosses() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), List.of(signature("boom")), 5000, true, 9, 12, 0, List.of(), List.of()));

    LogWatchResult result = workflow.run(request(true));

    assertThat(result.detail()).contains("12 more were dropped");
    assertThat(result.detail()).contains("line budget");
    assertThat(result.truncated()).isTrue();
    assertThat(result.signaturesDropped()).isEqualTo(12);
  }

  /**
   * Muting is the one filter whose effect is invisible in the tickets, by definition — no ticket
   * is filed. So the run detail is the only place an over-broad rule can be seen, and it has to
   * name the rules rather than merely count them: "3 muted" tells an operator nothing they can
   * act on.
   */
  @Test
  @DisplayName("muted findings are named in the run detail, never silently withheld")
  void reportsWhatItMutedAndWhichRulesDidIt() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(),
                List.of(signature("boom")),
                400,
                false,
                9,
                0,
                3,
                List.of("Temporal cancel churn", "Alloy tailing a removed container"), List.of()));

    LogWatchResult result = workflow.run(request(false));

    assertThat(result.detail()).contains("3 muted as third-party noise");
    assertThat(result.detail()).contains("Temporal cancel churn");
    assertThat(result.detail()).contains("Alloy tailing a removed container");
    // Muted is not dropped: the cap reported nothing, and the detail must not imply it did.
    assertThat(result.detail()).doesNotContain("dropped by the per-run cap");
    assertThat(result.signaturesDropped()).isZero();
  }

  @Test
  @DisplayName("a scan that mutes nothing says nothing about muting")
  void saysNothingAboutMutingWhenNothingWasMuted() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(alive(), List.of(signature("boom")), 400, false, 9, 0, 0,
                List.of(), List.of()));

    LogWatchResult result = workflow.run(request(false));

    assertThat(result.detail()).doesNotContain("muted");
  }

  @Test
  @DisplayName("the run record is keyed on the run id, not the workflow id")
  void recordsTheRunUnderTheRunId() {
    environment.close();
    setUpWithWorkflowId("logwatch");
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), List.of(), 1, false, 5, 0, 0, List.of(), List.of()));

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

  /**
   * The single most important guarantee in the absence sweep.
   *
   * <p>An unusable source produces zero signatures for exactly the same reason a fixed production
   * does: nothing came back. Sweeping here would read "Grafana Cloud stopped accepting logs" as
   * "every problem is fixed" and close the entire backlog — including the ticket the same run
   * just filed to say the module cannot see.
   */
  @Test
  @DisplayName("a silent source never closes anything as resolved")
  void neverSweepsWhenTheSourceIsUnusable() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                new SourceHealth(
                    SourceHealth.Status.SILENT,
                    SourceHealth.Tier.ALLOY_COMPONENT,
                    "429 ingestion rate limit exceeded"),
                List.of(), 0, false, 0, 0, 0, List.of(), List.of()));

    LogWatchResult result = workflow.run(request(false));

    assertThat(result.status()).isEqualTo(LogWatchStatus.SOURCE_UNHEALTHY);
    verify(linear, never()).sweepResolved(any());
  }

  @Test
  @DisplayName("a clean scan closes the tickets whose problems have stopped")
  void sweepsOnTheCleanScan() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), List.of(), 400, false, 9, 0, 0, List.of(), List.of()));
    when(linear.sweepResolved(any()))
        .thenReturn(
            new SweepReport(
                1,
                List.of(
                    new SweptIssue(
                        "fp", List.of("backend", "ERROR", "boom"), "SIM-30",
                        "https://linear.app/SIM-30")),
                0, 0, 0, false));

    LogWatchResult result = workflow.run(request(false));

    assertThat(result.status()).isEqualTo(LogWatchStatus.NO_FINDINGS);
    assertThat(result.resolvedIssueUrls()).containsExactly("https://linear.app/SIM-30");
    assertThat(result.detail()).contains("closed 1 ticket(s) no longer reported (SIM-30)");
  }

  /**
   * The per-run cap limits how many signatures are FILED, not how many were SEEN — so with more
   * live problems than the cap, the overflow never reaches the sink and its lastSeenAt never
   * advances. The scan saw those signatures, though, so it names them and the sweep leaves exactly
   * those tickets open instead of refusing to run. Before this, every capped night closed
   * nothing, and in September 2026 that was every night.
   */
  @Test
  @DisplayName("a run that hit the per-run cap still sweeps, naming what the cap dropped")
  void sweepsPastTheCapNamingWhatItDropped() {
    LogSignature dropped1 = signature("dropped-one");
    LogSignature dropped2 = signature("dropped-two");
    when(activities.observe(any(), any()))
        .thenReturn(
            capped(List.of(signature("boom")), 2, List.of(dropped1, dropped2)));
    when(linear.sweepResolved(any()))
        .thenReturn(
            new SweepReport(
                1,
                List.of(
                    new SweptIssue(
                        "fp", List.of("backend", "ERROR", "logger:gone"), "SIM-31",
                        "https://linear.app/SIM-31")),
                0, 0, 0, false));

    LogWatchResult result = workflow.run(request(false));

    ArgumentCaptor<AbsenceSweep> sweep = ArgumentCaptor.forClass(AbsenceSweep.class);
    verify(linear).sweepResolved(sweep.capture());
    assertThat(sweep.getValue().presentKeyParts())
        .containsExactly(keyParts(dropped1), keyParts(dropped2));
    assertThat(result.resolvedIssueUrls()).containsExactly("https://linear.app/SIM-31");
    assertThat(result.detail()).contains("2 more were dropped by the per-run cap");
    assertThat(result.detail()).doesNotContain("no ticket was closed as resolved");
  }

  /**
   * What each dropped signature is called must be exactly what filing it would have called it,
   * or the name protects a fingerprint no ticket carries. The key parts are read back from a real
   * filing of the same signature — not rebuilt here — so this cannot agree with itself by
   * construction.
   */
  @Test
  @DisplayName("a dropped signature is named by the fingerprint filing it would have used")
  void droppedSignaturesAreNamedAsFilingNamesThem() {
    LogSignature sometimesDropped = signature("sometimes-dropped");
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), List.of(sometimesDropped), 400, false, 9, 0, 0, List.of(), List.of()));
    workflow.run(request(false));
    ArgumentCaptor<IssueFiling> filing = ArgumentCaptor.forClass(IssueFiling.class);
    verify(linear).fileIssue(filing.capture());
    final String filedFingerprint =
        Fingerprint.of(filing.getValue().producer(), filing.getValue().keyParts());

    tearDown();
    setUpWithWorkflowId("test-logwatch-capped");
    when(activities.observe(any(), any()))
        .thenReturn(capped(List.of(signature("boom")), 1, List.of(sometimesDropped)));
    workflow.run(request(false));

    ArgumentCaptor<AbsenceSweep> sweep = ArgumentCaptor.forClass(AbsenceSweep.class);
    verify(linear).sweepResolved(sweep.capture());
    assertThat(sweep.getValue().presentKeyParts())
        .extracting(keyParts -> Fingerprint.of(sweep.getValue().producer(), keyParts))
        .containsExactly(filedFingerprint);
  }

  /**
   * A result recorded by the previous build has no dropped key parts at all, so it reads back
   * empty while {@code signaturesDropped} is still non-zero. Those dropped signatures are live
   * problems nobody can name, so the old all-or-nothing veto must still apply to them.
   */
  @Test
  @DisplayName("a capped run that cannot name what it dropped closes nothing, and says why")
  void neverSweepsWhenTheDroppedSignaturesAreUnnamed() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), List.of(signature("boom")), 400, false, 9, 4, 0, List.of(), null));

    LogWatchResult result = workflow.run(request(false));

    verify(linear, never()).sweepResolved(any());
    assertThat(result.detail())
        .contains("dropped 4 signature(s) without naming them, so no ticket was closed");
  }

  @Test
  @DisplayName("naming only some of what the cap dropped is treated as naming none of it")
  void neverSweepsWhenOnlySomeDroppedSignaturesAreNamed() {
    when(activities.observe(any(), any()))
        .thenReturn(capped(List.of(signature("boom")), 3, List.of(signature("only-one"))));

    LogWatchResult result = workflow.run(request(false));

    verify(linear, never()).sweepResolved(any());
    assertThat(result.detail()).contains("no ticket was closed as resolved");
  }

  /**
   * Muted is not dropped. A muted group is excluded on purpose every run, and the sweep closing
   * its old ticket is how an adopted mute rule retires it — so it must never be named present.
   */
  @Test
  @DisplayName("muted signatures never protect a ticket from the sweep")
  void mutedSignaturesAreNotNamedAsPresent() {
    LogSignature dropped = signature("dropped");
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), List.of(signature("boom")), 400, false, 9, 1, 3,
                List.of("Temporal cancel churn"), List.of(keyParts(dropped))));

    workflow.run(request(false));

    ArgumentCaptor<AbsenceSweep> sweep = ArgumentCaptor.forClass(AbsenceSweep.class);
    verify(linear).sweepResolved(sweep.capture());
    assertThat(sweep.getValue().presentKeyParts()).containsExactly(keyParts(dropped));
  }

  @Test
  @DisplayName("an uncapped run names nothing as present")
  void uncappedRunNamesNothing() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), List.of(signature("boom")), 400, false, 9, 0, 2,
                List.of("Temporal cancel churn"), List.of()));

    workflow.run(request(false));

    ArgumentCaptor<AbsenceSweep> sweep = ArgumentCaptor.forClass(AbsenceSweep.class);
    verify(linear).sweepResolved(sweep.capture());
    assertThat(sweep.getValue().presentKeyParts()).isEmpty();
  }

  /**
   * Narrowing the cap veto must not loosen the other three. A truncated read has lines it never
   * examined, so unlike the cap there is nothing to name, however well the dropped part is named.
   */
  @Test
  @DisplayName("a truncated read still closes nothing when the cap's losses are all named")
  void truncationStillVetoesCappedRuns() {
    ScanObservation named = capped(List.of(signature("boom")), 1, List.of(signature("x")));
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), named.signatures(), 5000, true, 9, 1, 0, List.of(),
                named.droppedKeyParts()));

    LogWatchResult result = workflow.run(request(false));

    verify(linear, never()).sweepResolved(any());
    assertThat(result.detail()).contains("the read was truncated, so no ticket was closed");
  }

  @Test
  @DisplayName("an unusable source still closes nothing when the cap's losses are all named")
  void unusableSourceStillVetoesCappedRuns() {
    ScanObservation named = capped(List.of(signature("boom")), 1, List.of(signature("x")));
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                new SourceHealth(
                    SourceHealth.Status.SILENT, SourceHealth.Tier.ALLOY_COMPONENT, "429"),
                named.signatures(), 400, false, 9, 1, 0, List.of(), named.droppedKeyParts()));

    LogWatchResult result = workflow.run(request(false));

    assertThat(result.status()).isEqualTo(LogWatchStatus.SOURCE_UNHEALTHY);
    verify(linear, never()).sweepResolved(any());
  }

  @Test
  @DisplayName("a short window still closes nothing when the cap's losses are all named")
  void shortWindowStillVetoesCappedRuns() {
    when(activities.observe(any(), any()))
        .thenReturn(capped(List.of(signature("boom")), 1, List.of(signature("x"))));

    workflow.run(
        new LogWatchRequest(
            TO.minusSeconds(300), TO, Trigger.SCHEDULE, false, true, true, QUIET_FOR));

    verify(linear, never()).sweepResolved(any());
  }

  @Test
  @DisplayName("a truncated read closes nothing, and says why")
  void neverSweepsWhenTheReadWasTruncated() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), List.of(signature("boom")), 5000, true, 9, 0, 0, List.of(), List.of()));

    LogWatchResult result = workflow.run(request(false));

    verify(linear, never()).sweepResolved(any());
    assertThat(result.detail()).contains("truncated");
  }

  /**
   * A post-deploy scan covers about five minutes, in which almost every known problem is absent
   * purely because five minutes is short. The workflow enforces a minimum window structurally, so
   * a future trigger that forgets to pass the flag still gets the safe behaviour.
   */
  @Test
  @DisplayName("a window too short to mean anything closes nothing, whatever the request asks")
  void neverSweepsOverShortWindows() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), List.of(), 3, false, 9, 0, 0, List.of(), List.of()));

    workflow.run(
        new LogWatchRequest(
            TO.minusSeconds(300), TO, Trigger.DEPLOY, false, true, true, QUIET_FOR));

    verify(linear, never()).sweepResolved(any());
  }

  @Test
  @DisplayName("the sweep is switched off by its own flag")
  void respectsTheResolveFlag() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), List.of(), 400, false, 9, 0, 0, List.of(), List.of()));

    workflow.run(request(false, false));

    verify(linear, never()).sweepResolved(any());
  }

  @Test
  @DisplayName("the sweep runs after filing, never before")
  void sweepsAfterFiling() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), List.of(signature("boom")), 400, false, 9, 0, 0, List.of(), List.of()));
    when(linear.sweepResolved(any())).thenReturn(SweepReport.none());

    workflow.run(request(false));

    // Every filing advances that fingerprint's lastSeenAt, which is the sweep's entire input.
    // Sweeping first would consider this scan's own findings absent and close the tickets it was
    // about to update.
    InOrder order = inOrder(linear);
    order.verify(linear).fileIssue(any());
    order.verify(linear).sweepResolved(any());
  }

  /**
   * A manual dry-run scan answers "nothing will be filed" in its API response. The sweep still
   * runs — a preview that silently skips half the run is not a preview — but it must carry the
   * REQUEST's dry-run flag, not rely on the sink's configured one, or that answer is a lie on
   * every production stack.
   */
  @Test
  @DisplayName("a dry-run scan previews the sweep, and says so in the request")
  void dryRunStillPreviewsTheSweep() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), List.of(), 400, false, 9, 0, 0, List.of(), List.of()));
    when(linear.sweepResolved(any())).thenReturn(SweepReport.none());

    workflow.run(request(true));

    ArgumentCaptor<AbsenceSweep> sweep = ArgumentCaptor.forClass(AbsenceSweep.class);
    verify(linear).sweepResolved(sweep.capture());
    assertThat(sweep.getValue().dryRun()).isTrue();
  }

  @Test
  @DisplayName("the sweep is scoped to log watch, never to another producer")
  void sweepsOnlyItsOwnProducer() {
    when(activities.observe(any(), any()))
        .thenReturn(
            new ScanObservation(
                alive(), List.of(), 400, false, 9, 0, 0, List.of(), List.of()));
    when(linear.sweepResolved(any())).thenReturn(SweepReport.none());

    workflow.run(request(false));

    ArgumentCaptor<AbsenceSweep> sweep = ArgumentCaptor.forClass(AbsenceSweep.class);
    verify(linear).sweepResolved(sweep.capture());
    assertThat(sweep.getValue().producer()).isEqualTo("logwatch");
    assertThat(sweep.getValue().quietFor()).isEqualTo(QUIET_FOR);
    assertThat(sweep.getValue().comment()).contains("has not appeared in a log scan for 7 day(s)");
    assertThat(sweep.getValue().dryRun()).isFalse();
  }

  private LogWatchRequest request(final boolean dryRun) {
    return request(dryRun, true);
  }

  private LogWatchRequest request(final boolean dryRun, final boolean resolveWhenClear) {
    return new LogWatchRequest(
        FROM, TO, dryRun ? Trigger.DRY_RUN : Trigger.SCHEDULE, dryRun, true, resolveWhenClear,
        QUIET_FOR);
  }

  /**
   * A healthy observation whose cap dropped {@code dropped} signatures, naming those given. The
   * names are built by the same {@link SignatureKeyParts} that {@code observe} uses.
   */
  private static ScanObservation capped(
      final List<LogSignature> filed, final int dropped, final List<LogSignature> named) {
    return new ScanObservation(
        alive(), filed, 400, false, 9, dropped, 0, List.of(),
        named.stream().map(SignatureKeyParts::of).toList());
  }

  /**
   * The helper both sides share. That filing really sends these is pinned separately, by
   * {@code droppedSignaturesAreNamedAsFilingNamesThem}, against a captured filing.
   */
  private static List<String> keyParts(final LogSignature signature) {
    return SignatureKeyParts.of(signature);
  }

  private static SourceHealth alive() {
    return new SourceHealth(
        SourceHealth.Status.ALIVE, SourceHealth.Tier.ALLOY_COMPONENT, "healthy");
  }

  private static LogSignature signature(final String text) {
    return new LogSignature(
        text, Severity.ERROR, "backend", 4, FROM, TO, "raw " + text,
        "logger:" + text, List.of(new LogSignature.Variant(text, 4, "raw " + text)), 1);
  }
}
