package com.simonrowe.factory.cvefix.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.simonrowe.factory.cvefix.config.CveFixTaskQueues;
import com.simonrowe.factory.cvefix.domain.CiOutcome;
import com.simonrowe.factory.cvefix.domain.CiOutcome.CiState;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.CveFixPhase;
import com.simonrowe.factory.cvefix.domain.CveFixProgress;
import com.simonrowe.factory.cvefix.domain.CveFixRequest;
import com.simonrowe.factory.cvefix.domain.CveFixResult;
import com.simonrowe.factory.cvefix.domain.CveFixStatus;
import com.simonrowe.factory.cvefix.domain.Finding;
import com.simonrowe.factory.cvefix.domain.UnfixableComponent;
import com.simonrowe.factory.cvefix.github.CveFixPrGateway;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRecord;
import com.simonrowe.factory.cvefix.workflow.CveFixActivities.FixSummary;
import com.simonrowe.factory.cvefix.workflow.CveFixActivities.PushResult;
import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.workflow.LinearActivities;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CveFixWorkflowTest {

  private static final String PURL = "pkg:maven/org.example/lib@1.0.0";
  private static final String OPEN_PR_URL = "https://github.com/o/r/pull/7";
  private static final String NEW_PR_URL = "https://github.com/o/r/pull/11";
  private static final String LOGS = "checkstyle: 1 violation";

  private static final List<ComponentFindings> COMPONENTS =
      List.of(
          new ComponentFindings(
              PURL,
              "lib",
              "1.0.0",
              List.of("CVE-2026-0001"),
              List.of(new Finding(PURL, "lib", "1.0.0", "CVE-2026-0001", "HIGH", ""))));

  private static final String LINEAR_URL = "https://linear.app/simonrowe/issue/SIM-9";

  private static final UnfixableComponent UNFIXABLE =
      new UnfixableComponent(
          "pkg:maven/org.example/stuck@2.0.0", List.of("CVE-2026-0002"), "no release yet");

  private static final UnfixableComponent OTHER_UNFIXABLE =
      new UnfixableComponent(
          "pkg:npm/other-stuck@3.0.0", List.of("CVE-2026-0003"), "the fix needs a major bump");

  private static final FixSummary SUMMARY =
      new FixSummary(
          List.of("org.example/lib 1.0.0 -> 1.0.1 (CVE-2026-0001)"),
          List.of(UNFIXABLE),
          "bumped one dependency");

  private static final CveFixRequest REQUEST =
      new CveFixRequest(false, Duration.ofMinutes(3), 3, Duration.ofHours(3), false);

  /** The same request with the Linear issue sink switched on. */
  private static final CveFixRequest FILING_REQUEST =
      new CveFixRequest(false, Duration.ofMinutes(3), 3, Duration.ofHours(3), true);

  @Test
  void skipsWhenTheCveFixPullRequestIsAlreadyOpen() {
    CveFixActivities activities = activities();
    when(activities.findOpenPrUrl()).thenReturn(OPEN_PR_URL);

    CveFixResult result = run(activities, REQUEST);

    assertThat(result.status()).isEqualTo(CveFixStatus.SKIPPED_PR_OPEN);
    assertThat(result.prUrl()).isEqualTo(OPEN_PR_URL);
    verify(activities, never()).fetchActionableFindings();
    verify(activities, never()).proposeAndPush(anyList(), any(), anyList());
  }

  @Test
  void returnsNoFindingsWhenDependencyTrackReportsNothingActionable() {
    CveFixActivities activities = activities();
    when(activities.fetchActionableFindings()).thenReturn(List.of());

    CveFixResult result = run(activities, REQUEST);

    assertThat(result.status()).isEqualTo(CveFixStatus.NO_FINDINGS);
    assertThat(result.prUrl()).isNull();
    verify(activities, never()).proposeAndPush(anyList(), any(), anyList());
    verify(activities, never()).openPullRequest(any());
  }

  @Test
  void returnsNothingFixableWhenTheAgentChangesNothing() {
    CveFixActivities activities = activities();
    when(activities.proposeAndPush(anyList(), any(), anyList()))
        .thenReturn(new PushResult(null, new FixSummary(List.of(), List.of(UNFIXABLE), "stuck")));

    CveFixResult result = run(activities, REQUEST);

    assertThat(result.status()).isEqualTo(CveFixStatus.NOTHING_FIXABLE);
    assertThat(result.bumps()).isZero();
    assertThat(result.unfixable()).isEqualTo(1);
    verify(activities, never()).openPullRequest(any());
    verify(activities, never()).checkCi(anyString());
    verify(activities).recordUnfixable(List.of(UNFIXABLE), COMPONENTS);
  }

  @Test
  void completesWhenCiIsGreenOnTheFirstPoll() {
    CveFixActivities activities = activities();
    when(activities.checkCi(anyString())).thenReturn(outcome(CiState.GREEN));

    Outcome outcome = execute(activities, REQUEST);

    assertThat(outcome.result().status()).isEqualTo(CveFixStatus.COMPLETED);
    assertThat(outcome.result().prUrl()).isEqualTo(NEW_PR_URL);
    assertThat(outcome.result().bumps()).isEqualTo(1);
    assertThat(outcome.progress().phase()).isEqualTo(CveFixPhase.COMPLETED);
    verify(activities, times(1)).proposeAndPush(anyList(), any(), anyList());
    verify(activities, never()).commentOnPullRequest(anyInt(), anyString());
  }

  @Test
  void repairsOnceThenCompletesWhenCiTurnsGreen() {
    CveFixActivities activities = activities();
    when(activities.checkCi(anyString()))
        .thenReturn(outcome(CiState.RED), outcome(CiState.GREEN));
    when(activities.proposeAndPush(anyList(), any(), anyList()))
        .thenReturn(new PushResult("sha-1", SUMMARY), new PushResult("sha-2", SUMMARY));

    CveFixResult result = run(activities, REQUEST);

    assertThat(result.status()).isEqualTo(CveFixStatus.COMPLETED);
    verify(activities, times(2)).proposeAndPush(anyList(), any(), anyList());
    verify(activities).proposeAndPush(COMPONENTS, LOGS, SUMMARY.bumpDescriptions());
    verify(activities).checkCi("sha-2");
    verify(activities, never()).commentOnPullRequest(anyInt(), anyString());
  }

  /**
   * Each attempt clones the default branch afresh, so the only way the agent can obey "choose a
   * different target version" is for the workflow to hand back what earlier attempts pushed.
   * Attempt three must see attempts one <em>and</em> two, not just the immediately previous one.
   */
  @Test
  void eachRepairAttemptIsToldEveryBumpAnEarlierAttemptAlreadyPushed() {
    FixSummary second =
        new FixSummary(
            List.of("org.example/lib 1.0.0 -> 1.0.2 (CVE-2026-0001)"),
            List.of(UNFIXABLE),
            "tried a different version");
    CveFixActivities activities = activities();
    when(activities.checkCi(anyString())).thenReturn(outcome(CiState.RED));
    when(activities.proposeAndPush(anyList(), any(), anyList()))
        .thenReturn(
            new PushResult("sha-1", SUMMARY),
            new PushResult("sha-2", second),
            new PushResult("sha-3", SUMMARY));

    run(activities, new CveFixRequest(false, Duration.ofMinutes(3), 2, Duration.ofHours(3), false));

    verify(activities, times(3)).proposeAndPush(anyList(), any(), anyList());
    verify(activities).proposeAndPush(COMPONENTS, null, List.of());
    verify(activities).proposeAndPush(COMPONENTS, LOGS, SUMMARY.bumpDescriptions());
    verify(activities)
        .proposeAndPush(
            COMPONENTS,
            LOGS,
            List.of(
                "org.example/lib 1.0.0 -> 1.0.1 (CVE-2026-0001)",
                "org.example/lib 1.0.0 -> 1.0.2 (CVE-2026-0001)"));
  }

  @Test
  void leavesThePullRequestOpenAndCommentsWhenTheRepairBudgetRunsOut() {
    CveFixActivities activities = activities();
    when(activities.checkCi(anyString())).thenReturn(outcome(CiState.RED));
    when(activities.proposeAndPush(anyList(), any(), anyList()))
        .thenReturn(
            new PushResult("sha-1", SUMMARY),
            new PushResult("sha-2", SUMMARY),
            new PushResult("sha-3", SUMMARY));

    CveFixResult result =
        run(
            activities,
            new CveFixRequest(false, Duration.ofMinutes(3), 2, Duration.ofHours(3), false));

    assertThat(result.status()).isEqualTo(CveFixStatus.CI_UNRESOLVED);
    assertThat(result.prUrl()).isEqualTo(NEW_PR_URL);
    assertThat(result.detail()).contains("repair budget of 2").doesNotContain("wall-clock");
    verify(activities, times(3)).proposeAndPush(anyList(), any(), anyList());
    verify(activities, times(1)).commentOnPullRequest(eq(11), anyString());
  }

  @Test
  void givesUpWhenTheRepairProducesNoFurtherChange() {
    CveFixActivities activities = activities();
    when(activities.checkCi(anyString())).thenReturn(outcome(CiState.RED));
    when(activities.proposeAndPush(anyList(), any(), anyList()))
        .thenReturn(new PushResult("sha-1", SUMMARY), new PushResult(null, SUMMARY));

    // The default budget of 3 is deliberately not spent and the 3h cap is nowhere near: the only
    // reason this run can stop is the repair that changed nothing, and the detail must say so.
    CveFixResult result = run(activities, REQUEST);

    assertThat(result.status()).isEqualTo(CveFixStatus.CI_UNRESOLVED);
    assertThat(result.detail())
        .contains("no further change")
        .doesNotContain("repair budget")
        .doesNotContain("wall-clock");
    verify(activities, times(2)).proposeAndPush(anyList(), any(), anyList());
    verify(activities, times(1)).commentOnPullRequest(eq(11), anyString());
    verify(activities, times(1)).checkCi("sha-1");
  }

  @Test
  void endsCiUnresolvedWhenTheWallClockCapElapsesBeforeTheBudget() {
    CveFixActivities activities = activities();
    when(activities.checkCi(anyString())).thenReturn(outcome(CiState.PENDING));

    CveFixResult result =
        run(
            activities,
            new CveFixRequest(false, Duration.ofMinutes(3), 3, Duration.ofMinutes(10), false));

    assertThat(result.status()).isEqualTo(CveFixStatus.CI_UNRESOLVED);
    assertThat(result.detail()).contains("wall-clock cap").doesNotContain("repair budget");
    verify(activities, times(1)).proposeAndPush(anyList(), any(), anyList());
    verify(activities, times(1)).commentOnPullRequest(eq(11), anyString());
  }

  @Test
  void keepsPollingWhileCiIsPending() {
    CveFixActivities activities = activities();
    when(activities.checkCi(anyString()))
        .thenReturn(outcome(CiState.PENDING), outcome(CiState.PENDING), outcome(CiState.GREEN));

    CveFixResult result = run(activities, REQUEST);

    assertThat(result.status()).isEqualTo(CveFixStatus.COMPLETED);
    verify(activities, times(3)).checkCi("sha-1");
    verify(activities, times(1)).proposeAndPush(anyList(), any(), anyList());
  }

  @Test
  void dryRunStopsBeforeOpeningThePullRequest() {
    CveFixActivities activities = activities();

    CveFixResult result =
        run(
            activities,
            new CveFixRequest(true, Duration.ofMinutes(3), 3, Duration.ofHours(3), false));

    // DRY_RUN rather than COMPLETED: the run pushed a branch and recorded suppressions, so the
    // status has to say so on its own without an operator reading detail().
    assertThat(result.status()).isEqualTo(CveFixStatus.DRY_RUN);
    assertThat(result.detail()).contains("dry run");
    assertThat(result.prUrl()).isNull();
    verify(activities, never()).openPullRequest(any());
    verify(activities, never()).checkCi(anyString());
  }

  @Test
  void readsThePollIntervalAndBudgetFromTheRequestNotFromProperties() {
    CveFixActivities polling = activities();
    when(polling.checkCi(anyString())).thenReturn(outcome(CiState.PENDING));

    run(polling, new CveFixRequest(false, Duration.ofSeconds(30), 3, Duration.ofMinutes(2), false));

    // A 30s interval inside a 2m cap polls roughly four times; the 3m production default,
    // which this workflow cannot read because it has no CveFixProperties, would poll at most once.
    verify(polling, atLeast(3)).checkCi(anyString());

    CveFixActivities repairing = activities();
    when(repairing.checkCi(anyString())).thenReturn(outcome(CiState.RED));
    when(repairing.proposeAndPush(anyList(), any(), anyList()))
        .thenReturn(new PushResult("sha-1", SUMMARY), new PushResult("sha-2", SUMMARY));

    CveFixResult result =
        run(
            repairing,
            new CveFixRequest(false, Duration.ofSeconds(30), 1, Duration.ofHours(3), false));

    assertThat(result.detail()).contains("repair budget of 1");
    verify(repairing, times(2)).proposeAndPush(anyList(), any(), anyList());
  }

  @Test
  void failsAndRecordsTheRunWhenThePullRequestWasNotOpened() {
    CveFixActivities activities = activities();
    when(activities.openPullRequest(any())).thenReturn(null);

    assertThatThrownBy(() -> run(activities, REQUEST)).isInstanceOf(WorkflowFailedException.class);

    CveFixRunRecord runRecord = recordedRun(activities);
    assertThat(runRecord.status()).isEqualTo(CveFixStatus.FAILED);
    assertThat(runRecord.detail()).contains("The CVE pull request was not opened");
    verify(activities, never()).checkCi(anyString());
  }

  @Test
  void recordsTheRunOnEveryTerminalPath() {
    CveFixActivities skipped = activities();
    when(skipped.findOpenPrUrl()).thenReturn(OPEN_PR_URL);
    run(skipped, REQUEST);
    assertThat(recordedRun(skipped).status()).isEqualTo(CveFixStatus.SKIPPED_PR_OPEN);

    CveFixActivities empty = activities();
    when(empty.fetchActionableFindings()).thenReturn(List.of());
    run(empty, REQUEST);
    assertThat(recordedRun(empty).status()).isEqualTo(CveFixStatus.NO_FINDINGS);

    CveFixActivities green = activities();
    when(green.checkCi(anyString())).thenReturn(outcome(CiState.GREEN));
    run(green, REQUEST);
    CveFixRunRecord completed = recordedRun(green);
    assertThat(completed.status()).isEqualTo(CveFixStatus.COMPLETED);
    assertThat(completed.findingsSeen()).isEqualTo(1);
    assertThat(completed.bumps()).isEqualTo(SUMMARY.bumpDescriptions());
    assertThat(completed.prUrl()).isEqualTo(NEW_PR_URL);
    assertThat(completed.startedAt()).isNotNull();
    assertThat(completed.id()).isEqualTo(completed.workflowId());

    CveFixActivities red = activities();
    when(red.checkCi(anyString())).thenReturn(outcome(CiState.RED));
    when(red.proposeAndPush(anyList(), any(), anyList()))
        .thenReturn(new PushResult("sha-1", SUMMARY), new PushResult("sha-2", SUMMARY));
    run(red, new CveFixRequest(false, Duration.ofMinutes(3), 1, Duration.ofHours(3), false));
    CveFixRunRecord unresolved = recordedRun(red);
    assertThat(unresolved.status()).isEqualTo(CveFixStatus.CI_UNRESOLVED);
    assertThat(unresolved.ciAttempts()).isEqualTo(1);
  }

  @Test
  void recordsUnfixableComponentsOnlyWhenAnAgentActuallyRan() {
    CveFixActivities green = activities();
    when(green.checkCi(anyString())).thenReturn(outcome(CiState.GREEN));
    run(green, REQUEST);
    verify(green).recordUnfixable(List.of(UNFIXABLE), COMPONENTS);

    CveFixActivities red = activities();
    when(red.checkCi(anyString())).thenReturn(outcome(CiState.RED));
    when(red.proposeAndPush(anyList(), any(), anyList()))
        .thenReturn(new PushResult("sha-1", SUMMARY), new PushResult("sha-2", SUMMARY));
    run(red, new CveFixRequest(false, Duration.ofMinutes(3), 1, Duration.ofHours(3), false));
    verify(red).recordUnfixable(List.of(UNFIXABLE), COMPONENTS);

    CveFixActivities nothingFixable = activities();
    when(nothingFixable.proposeAndPush(anyList(), any(), anyList()))
        .thenReturn(new PushResult(null, new FixSummary(List.of(), List.of(UNFIXABLE), "stuck")));
    run(nothingFixable, REQUEST);
    verify(nothingFixable).recordUnfixable(List.of(UNFIXABLE), COMPONENTS);

    CveFixActivities skipped = activities();
    when(skipped.findOpenPrUrl()).thenReturn(OPEN_PR_URL);
    run(skipped, REQUEST);
    verify(skipped, never()).recordUnfixable(anyList(), anyList());

    CveFixActivities empty = activities();
    when(empty.fetchActionableFindings()).thenReturn(List.of());
    run(empty, REQUEST);
    verify(empty, never()).recordUnfixable(anyList(), anyList());
  }

  // ---------------------------------------------------------------------------
  // The Linear issue sink
  // ---------------------------------------------------------------------------

  @Test
  void filesOneIssuePerNewlyRecordedComponent() {
    CveFixActivities activities = activities();
    when(activities.proposeAndPush(anyList(), any(), anyList()))
        .thenReturn(
            new PushResult(
                null, new FixSummary(List.of(), List.of(UNFIXABLE, OTHER_UNFIXABLE), "stuck")));
    // Only what recordUnfixable reports as new information files; the daily schedule re-runs with
    // an unchanged finding set most days and must file nothing then.
    when(activities.recordUnfixable(anyList(), anyList()))
        .thenReturn(List.of(UNFIXABLE, OTHER_UNFIXABLE));
    LinearActivities linear = linearActivities();

    CveFixResult result = runFiling(activities, linear, FILING_REQUEST);

    assertThat(result.status()).isEqualTo(CveFixStatus.NOTHING_FIXABLE);
    ArgumentCaptor<IssueFiling> filings = ArgumentCaptor.forClass(IssueFiling.class);
    verify(linear, times(2)).fileIssue(filings.capture());
    assertThat(filings.getAllValues())
        .extracting(IssueFiling::producer)
        .containsOnly("cvefix");
    // The purl alone, the key UnfixableFindingRecord itself uses: one ticket per component
    // however many advisories accumulate against it.
    assertThat(filings.getAllValues())
        .extracting(IssueFiling::keyParts)
        .containsExactly(List.of(UNFIXABLE.purl()), List.of(OTHER_UNFIXABLE.purl()));
    assertThat(filings.getAllValues())
        .extracting(IssueFiling::title)
        .containsExactly(
            "Cannot auto-fix " + UNFIXABLE.purl(), "Cannot auto-fix " + OTHER_UNFIXABLE.purl());
    assertThat(filings.getAllValues().get(0).body()).contains(UNFIXABLE.reason());

    // The run id PLUS the purl. One run files several components, and a bare run id would make
    // the second look like a replay of the first and be silently dropped.
    List<String> occurrenceIds =
        filings.getAllValues().stream().map(IssueFiling::occurrenceId).toList();
    assertThat(occurrenceIds).doesNotHaveDuplicates();
    assertThat(occurrenceIds.get(0)).endsWith(":" + UNFIXABLE.purl());
    assertThat(occurrenceIds.get(1)).endsWith(":" + OTHER_UNFIXABLE.purl());
    // indexOf, not lastIndexOf: a purl is full of colons, so only the first one separates the
    // run id from it. The occurrence id is an identity key, never parsed in production.
    String runId = occurrenceIds.get(0).substring(0, occurrenceIds.get(0).indexOf(':'));
    assertThat(runId).isNotBlank();
    assertThat(occurrenceIds.get(1)).startsWith(runId + ":");
    assertThat(filings.getAllValues().get(0).occurrenceDetail()).contains(runId);
    assertThat(filings.getAllValues().get(0).workflowId()).isEqualTo("cve-fix-test");
  }

  @Test
  void filesNothingWhenNoComponentIsNewInformation() {
    CveFixActivities activities = activities();
    when(activities.proposeAndPush(anyList(), any(), anyList()))
        .thenReturn(new PushResult(null, new FixSummary(List.of(), List.of(UNFIXABLE), "stuck")));
    // The give-up was already stored under the same fingerprint, so nothing is new.
    when(activities.recordUnfixable(anyList(), anyList())).thenReturn(List.of());
    LinearActivities linear = linearActivities();

    runFiling(activities, linear, FILING_REQUEST);

    verify(linear, never()).fileIssue(any());
  }

  @Test
  void filesNothingWhenTheSinkIsDisabled() {
    // With factory.linear.enabled false nothing polls the `linear` queue, so scheduling the
    // activity at all would stall the run until its schedule-to-close timeout. The queue IS
    // polled here, which is what makes "nothing was scheduled" the only reading of this result.
    CveFixActivities activities = activities();
    when(activities.proposeAndPush(anyList(), any(), anyList()))
        .thenReturn(new PushResult(null, new FixSummary(List.of(), List.of(UNFIXABLE), "stuck")));
    when(activities.recordUnfixable(anyList(), anyList())).thenReturn(List.of(UNFIXABLE));
    LinearActivities linear = linearActivities();

    CveFixResult result = runFiling(activities, linear, REQUEST);

    assertThat(result.status()).isEqualTo(CveFixStatus.NOTHING_FIXABLE);
    verify(linear, never()).fileIssue(any());
  }

  @Test
  void filesOnEveryGiveUpPathIncludingAfterTheRepairBudget() {
    CveFixActivities activities = activities();
    when(activities.checkCi(anyString())).thenReturn(outcome(CiState.RED));
    when(activities.proposeAndPush(anyList(), any(), anyList()))
        .thenReturn(new PushResult("sha-1", SUMMARY), new PushResult("sha-2", SUMMARY));
    when(activities.recordUnfixable(anyList(), anyList())).thenReturn(List.of(UNFIXABLE));
    LinearActivities linear = linearActivities();

    CveFixResult result =
        runFiling(
            activities,
            linear,
            new CveFixRequest(false, Duration.ofMinutes(3), 1, Duration.ofHours(3), true));

    assertThat(result.status()).isEqualTo(CveFixStatus.CI_UNRESOLVED);
    verify(linear, times(1)).fileIssue(any());
  }

  @Test
  void filingFailureLeavesTheRunsStatusAndSuppressionRecordsUntouched() {
    // The suppression record is already written; the ticket is a nicety by comparison. Note the
    // failure is thrown at us as an ActivityFailure, but the workflow catches RuntimeException -
    // encoding the payload happens on the workflow thread, and a raw JDK exception there is not a
    // TemporalFailure, so it would fail the workflow task and Temporal retries those forever.
    CveFixActivities activities = activities();
    when(activities.checkCi(anyString())).thenReturn(outcome(CiState.GREEN));
    when(activities.recordUnfixable(anyList(), anyList())).thenReturn(List.of(UNFIXABLE));
    LinearActivities linear = linearActivities();
    when(linear.fileIssue(any()))
        .thenThrow(ApplicationFailure.newNonRetryableFailure("Linear down", "LinearApiError"));

    CveFixResult result = runFiling(activities, linear, FILING_REQUEST);

    assertThat(result.status()).isEqualTo(CveFixStatus.COMPLETED);
    assertThat(recordedRun(activities).status()).isEqualTo(CveFixStatus.COMPLETED);
    verify(activities).recordUnfixable(List.of(UNFIXABLE), COMPONENTS);
  }

  /**
   * {@code withoutAnnotations()} is required: Mockito copies {@code @ActivityMethod} onto the
   * mock's own methods, and Temporal rejects a class carrying that annotation outside the
   * interface that declares it.
   */
  private static CveFixActivities activities() {
    CveFixActivities activities = mock(CveFixActivities.class, withSettings().withoutAnnotations());
    when(activities.fetchActionableFindings()).thenReturn(COMPONENTS);
    when(activities.proposeAndPush(anyList(), any(), anyList()))
        .thenReturn(new PushResult("sha-1", SUMMARY));
    when(activities.openPullRequest(any()))
        .thenReturn(new CveFixPrGateway.OpenPullRequest(11, NEW_PR_URL));
    when(activities.ciFailureLogs(anyString())).thenReturn(LOGS);
    return activities;
  }

  private static LinearActivities linearActivities() {
    LinearActivities linear = mock(LinearActivities.class, withSettings().withoutAnnotations());
    when(linear.fileIssue(any()))
        .thenReturn(new FiledIssue(FilingDecision.FILED_NEW, "SIM-9", LINEAR_URL, "fp"));
    return linear;
  }

  private static CiOutcome outcome(final CiState state) {
    return new CiOutcome(
        state, state == CiState.RED ? List.of("build") : List.of(), "CI is " + state);
  }

  private static CveFixRunRecord recordedRun(final CveFixActivities activities) {
    ArgumentCaptor<CveFixRunRecord> captor = ArgumentCaptor.forClass(CveFixRunRecord.class);
    verify(activities).recordRun(captor.capture());
    return captor.getValue();
  }

  private CveFixResult run(final CveFixActivities activities, final CveFixRequest request) {
    return execute(activities, request).result();
  }

  private CveFixResult runFiling(
      final CveFixActivities activities,
      final LinearActivities linear,
      final CveFixRequest request) {
    return execute(activities, linear, request).result();
  }

  private Outcome execute(final CveFixActivities activities, final CveFixRequest request) {
    return execute(activities, null, request);
  }

  /**
   * Runs the workflow against a time-skipping test environment so the CI loop's {@code
   * Workflow.sleep} calls cost no wall-clock time, and queries the final progress before the
   * environment closes.
   *
   * <p>{@code linear} is a second worker on its own task queue, exactly as in production, where it
   * is polled by {@code software-factory}. Passing null models the sink not being deployed at all;
   * passing a mock while leaving {@code linearFilingEnabled} false is the stronger case, because
   * then "nothing was filed" cannot be explained away by nothing polling the queue.
   */
  private Outcome execute(
      final CveFixActivities activities,
      final LinearActivities linear,
      final CveFixRequest request) {
    // A filing scheduled on a queue nothing polls does not fail - it waits, and the test
    // environment will not skip that timer, so the whole suite hangs indefinitely instead of
    // reporting anything. Trade that for a legible throw.
    if (linear == null && request.linearFilingEnabled()) {
      throw new IllegalArgumentException(
          "linearFilingEnabled needs a LinearActivities worker - use runFiling(..), "
              + "not run(..), or the suite will hang rather than fail");
    }
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker worker = environment.newWorker(CveFixTaskQueues.CVE_FIX);
      worker.registerWorkflowImplementationTypes(CveFixWorkflowImpl.class);
      worker.registerActivitiesImplementations(activities);
      if (linear != null) {
        environment.newWorker(LinearTaskQueues.LINEAR).registerActivitiesImplementations(linear);
      }
      environment.start();
      CveFixWorkflow workflow =
          environment
              .getWorkflowClient()
              .newWorkflowStub(
                  CveFixWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setTaskQueue(CveFixTaskQueues.CVE_FIX)
                      .setWorkflowId("cve-fix-test")
                      .build());
      CveFixResult result = workflow.run(request);
      return new Outcome(result, workflow.progress());
    }
  }

  private record Outcome(CveFixResult result, CveFixProgress progress) {
  }
}
