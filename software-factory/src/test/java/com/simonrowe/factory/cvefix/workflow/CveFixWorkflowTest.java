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
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
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

  private static final UnfixableComponent UNFIXABLE =
      new UnfixableComponent(
          "pkg:maven/org.example/stuck@2.0.0", List.of("CVE-2026-0002"), "no release yet");

  private static final FixSummary SUMMARY =
      new FixSummary(
          List.of("org.example/lib 1.0.0 -> 1.0.1 (CVE-2026-0001)"),
          List.of(UNFIXABLE),
          "bumped one dependency");

  private static final CveFixRequest REQUEST =
      new CveFixRequest(false, Duration.ofMinutes(3), 3, Duration.ofHours(3));

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

    run(activities, new CveFixRequest(false, Duration.ofMinutes(3), 2, Duration.ofHours(3)));

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
        run(activities, new CveFixRequest(false, Duration.ofMinutes(3), 2, Duration.ofHours(3)));

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
        run(activities, new CveFixRequest(false, Duration.ofMinutes(3), 3, Duration.ofMinutes(10)));

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
        run(activities, new CveFixRequest(true, Duration.ofMinutes(3), 3, Duration.ofHours(3)));

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

    run(polling, new CveFixRequest(false, Duration.ofSeconds(30), 3, Duration.ofMinutes(2)));

    // A 30s interval inside a 2m cap polls roughly four times; the 3m production default,
    // which this workflow cannot read because it has no CveFixProperties, would poll at most once.
    verify(polling, atLeast(3)).checkCi(anyString());

    CveFixActivities repairing = activities();
    when(repairing.checkCi(anyString())).thenReturn(outcome(CiState.RED));
    when(repairing.proposeAndPush(anyList(), any(), anyList()))
        .thenReturn(new PushResult("sha-1", SUMMARY), new PushResult("sha-2", SUMMARY));

    CveFixResult result =
        run(repairing, new CveFixRequest(false, Duration.ofSeconds(30), 1, Duration.ofHours(3)));

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
    run(red, new CveFixRequest(false, Duration.ofMinutes(3), 1, Duration.ofHours(3)));
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
    run(red, new CveFixRequest(false, Duration.ofMinutes(3), 1, Duration.ofHours(3)));
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

  /**
   * Runs the workflow against a time-skipping test environment so the CI loop's {@code
   * Workflow.sleep} calls cost no wall-clock time, and queries the final progress before the
   * environment closes.
   */
  private Outcome execute(final CveFixActivities activities, final CveFixRequest request) {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker worker = environment.newWorker(CveFixTaskQueues.CVE_FIX);
      worker.registerWorkflowImplementationTypes(CveFixWorkflowImpl.class);
      worker.registerActivitiesImplementations(activities);
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
