package com.simonrowe.factory.deploy.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.simonrowe.factory.deploy.config.DeployTaskQueues;
import com.simonrowe.factory.deploy.domain.DeployPhase;
import com.simonrowe.factory.deploy.domain.DeployRequest;
import com.simonrowe.factory.deploy.domain.DeployResult;
import com.simonrowe.factory.deploy.domain.DeployStatus;
import com.simonrowe.factory.deploy.domain.PhaseOutcome;
import com.simonrowe.factory.deploy.domain.SyncDecision;
import com.simonrowe.factory.deploy.domain.SyncOutcome;
import com.simonrowe.factory.deploy.persistence.DeployRunRecord;
import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.FilingDecision;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.workflow.LinearActivities;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * The deploy flow's behaviour, with activities mocked.
 *
 * <p>The cases that matter most are the failure ones, and the single most important assertion in
 * this file is {@link #leavesMaintenancePageUpWhenRollbackAlsoFails()}: a rollback that did
 * not verify must not take the page down, because doing so exposes a broken site with nobody
 * watching.
 */
class DeployWorkflowTest {

  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private static final String NEWER_SHA = "fedcba9876543210fedcba9876543210fedcba98";
  private static final String PREVIOUS_SHA = "1111111111111111111111111111111111111111";
  private static final String LINEAR_URL = "https://linear.app/simonrowe/issue/SIM-9";

  /** The default request has the issue sink off, which is also its production default. */
  private static DeployRequest request() {
    return new DeployRequest(
        SHA, DeployRequest.TRIGGER_WEBHOOK, 999L, true, true,
        List.of("backend", "frontend", "software-factory"), false, false);
  }

  private static DeployRequest request(final boolean syncConfig, final boolean rollbackEnabled) {
    return new DeployRequest(
        SHA, DeployRequest.TRIGGER_WEBHOOK, 999L, syncConfig, rollbackEnabled,
        List.of("backend"), false, false);
  }

  /** The same request with the Linear issue sink switched on. */
  private static DeployRequest requestFilingToLinear() {
    return requestFilingToLinear(true, true);
  }

  private static DeployRequest requestFilingToLinear(
      final boolean syncConfig, final boolean rollbackEnabled) {
    return new DeployRequest(
        SHA, DeployRequest.TRIGGER_WEBHOOK, 999L, syncConfig, rollbackEnabled,
        List.of("backend", "frontend", "software-factory"), false, true);
  }

  // ---------------------------------------------------------------------------
  // Happy path
  // ---------------------------------------------------------------------------

  @Test
  void runsEveryPhaseInOrderAndReportsDeployed() {
    DeployActivities activities = activities();

    Outcome outcome = execute(activities, request());

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.DEPLOYED);
    assertThat(outcome.result().sha()).isEqualTo(SHA);

    // The order is the whole design: sync first so later phases use the new script, the page up
    // before anything changes, verify-public only after the page comes down.
    ArgumentCaptor<DeployPhase> phases = ArgumentCaptor.forClass(DeployPhase.class);
    verify(activities, times(6)).runPhase(phases.capture(), any(), anyBoolean());
    assertThat(phases.getAllValues())
        .containsExactly(
            DeployPhase.MAINTENANCE_ON,
            DeployPhase.PULL,
            DeployPhase.RECREATE,
            DeployPhase.VERIFY,
            DeployPhase.MAINTENANCE_OFF,
            DeployPhase.VERIFY_PUBLIC);
    verify(activities).syncConfig(SHA, false);
  }

  @Test
  void pullsTheExactCommitRatherThanMovingTag() {
    DeployActivities activities = activities();

    execute(activities, request());

    // The image tag is passed only for `pull`, and it is the head sha. Deploying `:latest` would
    // deploy whatever happened to be tagged when the pull ran.
    verify(activities).runPhase(eq(DeployPhase.PULL), eq(SHA), anyBoolean());
    verify(activities).runPhase(eq(DeployPhase.RECREATE), eq(null), anyBoolean());
  }

  @Test
  void recordsTheRunWithEveryPhaseOutcome() {
    DeployActivities activities = activities();

    execute(activities, request());

    DeployRunRecord record = lastRecordedRun(activities);
    assertThat(record.sha()).isEqualTo(SHA);
    assertThat(record.workflowId()).isEqualTo("deploy-prod");
    assertThat(record.trigger()).isEqualTo("workflow_run");
    assertThat(record.status()).isEqualTo(DeployStatus.DEPLOYED);
    assertThat(record.rollbackTaken()).isFalse();
    assertThat(record.maintenancePageLeftUp()).isFalse();
    assertThat(record.phases())
        .extracting(PhaseOutcome::phase)
        .contains(DeployPhase.SYNC_CONFIG, DeployPhase.PULL, DeployPhase.VERIFY_PUBLIC);
  }

  @Test
  void skipsConfigurationSyncEntirelyWhenItIsDisabled() {
    DeployActivities activities = activities();

    Outcome outcome = execute(activities, request(false, true));

    verify(activities, never()).syncConfig(anyString(), anyBoolean());
    // Still a success, and honestly labelled: images were deployed, configuration was not.
    assertThat(outcome.result().status()).isEqualTo(DeployStatus.DEPLOYED_IMAGES_ONLY);
    assertThat(outcome.result().syncDecision()).isEqualTo(SyncDecision.DISABLED);
  }

  @Test
  void treatsAlreadyCurrentCheckoutAsCleanNoOp() {
    // This is what the rehearsal deploy in the rollout does: trigger the sha already in
    // production, so sync-config is a no-op and every phase runs without changing anything.
    DeployActivities activities = activities();
    when(activities.syncConfig(anyString(), anyBoolean()))
        .thenReturn(sync(SyncDecision.ALREADY_CURRENT, null));

    Outcome outcome = execute(activities, request());

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.DEPLOYED);
    verify(activities, never()).rollbackConfig(anyString(), anyBoolean());
  }

  // ---------------------------------------------------------------------------
  // Coalescing and the drain loop
  // ---------------------------------------------------------------------------

  @Test
  void duplicateDeliveryOfSameCommitProducesOneDeploy() {
    // A duplicate webhook delivery signals the running workflow rather than starting a second
    // one. The signal is fired from inside the `pull` activity so it lands provably mid-deploy -
    // signalling from the test thread would race the workflow to completion and pass or fail
    // depending on scheduling.
    DeployActivities activities = activities();
    Outcome outcome = executeSignalling(activities, request(), SHA);

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.DEPLOYED);
    assertThat(outcome.result().sha()).isEqualTo(SHA);
    // Six phases, not twelve: the same sha is not deployed twice.
    verify(activities, times(6)).runPhase(any(), any(), anyBoolean());
    verify(activities, times(1)).recordRun(any());
  }

  @Test
  void newerCommitSignalledMidDeployGetsItsOwnDeploy() {
    // Without the drain loop, a merge landing mid-deploy would signal a workflow that never looks
    // at the field again, and its commit would never deploy at all.
    DeployActivities activities = activities();
    Outcome outcome = executeSignalling(activities, request(), NEWER_SHA);

    assertThat(outcome.result().sha()).isEqualTo(NEWER_SHA);
    // Two passes of six phases.
    verify(activities, times(12)).runPhase(any(), any(), anyBoolean());
    verify(activities).syncConfig(SHA, false);
    verify(activities).syncConfig(NEWER_SHA, false);
  }

  @Test
  void eachDeployAttemptGetsItsOwnRunRecord() {
    // Both attempts share a Temporal run id, so without the attempt suffix in the document id the
    // second would overwrite the first and only the newer commit would appear in history.
    DeployActivities activities = activities();
    executeSignalling(activities, request(), NEWER_SHA);

    ArgumentCaptor<DeployRunRecord> records = ArgumentCaptor.forClass(DeployRunRecord.class);
    verify(activities, times(2)).recordRun(records.capture());
    assertThat(records.getAllValues()).extracting(DeployRunRecord::id).doesNotHaveDuplicates();
    assertThat(records.getAllValues())
        .extracting(DeployRunRecord::sha)
        .containsExactly(SHA, NEWER_SHA);
  }

  // ---------------------------------------------------------------------------
  // Rollback
  // ---------------------------------------------------------------------------

  @Test
  void rollsBackWhenVerifyFailsAndReportsRolledBack() {
    DeployActivities activities = activities();
    failPhaseOnce(activities, DeployPhase.VERIFY, 1);

    Outcome outcome = execute(activities, request());

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.ROLLED_BACK);
    verify(activities).runPhase(eq(DeployPhase.ROLLBACK), any(), anyBoolean());
    // Restoring the commit BEFORE the images is what makes the rollback run the previous version
    // of restart-prod.sh - which is what matters when the script itself broke the deploy.
    verify(activities).rollbackConfig(PREVIOUS_SHA, false);

    DeployRunRecord record = lastRecordedRun(activities);
    assertThat(record.rollbackTaken()).isTrue();
    assertThat(record.rollbackStatus()).isEqualTo(DeployStatus.ROLLED_BACK);
    assertThat(record.maintenancePageLeftUp()).isFalse();
  }

  @Test
  void rollsBackWhenVerifyPublicFailsAfterThePageCameDown() {
    DeployActivities activities = activities();
    failPhaseOnce(activities, DeployPhase.VERIFY_PUBLIC, 1);

    Outcome outcome = execute(activities, request());

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.ROLLED_BACK);
    // The page must be re-asserted: verify-public runs with it already down, so the broken
    // version is publicly visible at the moment this failure is detected.
    verify(activities, times(2)).runPhase(eq(DeployPhase.MAINTENANCE_ON), any(), anyBoolean());
  }

  @Test
  void leavesMaintenancePageUpWhenRollbackAlsoFails() {
    DeployActivities activities = activities();
    failPhaseOnce(activities, DeployPhase.VERIFY, 1);
    failPhase(activities, DeployPhase.ROLLBACK, 1);

    Outcome outcome = execute(activities, request());

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.ROLLBACK_FAILED);
    // THE assertion of this file. Taking the page down here would expose a broken site with
    // nobody watching; leaving it up is the correct outcome.
    verify(activities, never()).runPhase(eq(DeployPhase.MAINTENANCE_OFF), any(), anyBoolean());
    assertThat(lastRecordedRun(activities).maintenancePageLeftUp()).isTrue();
  }

  @Test
  void doesNotRestoreTheCommitWhenConfigurationSyncNeverMovedHead() {
    DeployActivities activities = activities();
    when(activities.syncConfig(anyString(), anyBoolean()))
        .thenReturn(sync(SyncDecision.DIRTY_TREE, null));
    failPhaseOnce(activities, DeployPhase.VERIFY, 1);

    Outcome outcome = execute(activities, request());

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.ROLLED_BACK);
    // Nothing moved, so there is nothing to restore - and resetting anyway would move HEAD for
    // the first time on the failure path, which is the last place that should happen.
    verify(activities, never()).rollbackConfig(anyString(), anyBoolean());
  }

  @Test
  void doesNotRollBackWhenRollbackIsDisabled() {
    DeployActivities activities = activities();
    failPhaseOnce(activities, DeployPhase.VERIFY, 1);

    Outcome outcome = execute(activities, request(true, false));

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.ROLLBACK_DISABLED);
    verify(activities, never()).runPhase(eq(DeployPhase.ROLLBACK), any(), anyBoolean());
    verify(activities, never()).rollbackConfig(anyString(), anyBoolean());
    // The broken version is still up, so the page stays up with it: the operator has explicitly
    // said not to touch it.
    assertThat(lastRecordedRun(activities).maintenancePageLeftUp()).isTrue();
  }

  @Test
  void takesThePageDownWhenTheDeployFailsBeforeAnythingWasRecreated() {
    // A failed pull changed nothing, so leaving the site behind a maintenance page over it would
    // be an outage caused by the deploy machinery rather than by the deploy.
    DeployActivities activities = activities();
    failPhase(activities, DeployPhase.PULL, 1);

    Outcome outcome = execute(activities, request());

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.FAILED);
    verify(activities, never()).runPhase(eq(DeployPhase.RECREATE), any(), anyBoolean());
    verify(activities).runPhase(eq(DeployPhase.MAINTENANCE_OFF), any(), anyBoolean());
    assertThat(lastRecordedRun(activities).maintenancePageLeftUp()).isFalse();
  }

  // ---------------------------------------------------------------------------
  // Reporting
  // ---------------------------------------------------------------------------

  @Test
  void diagnosesAndReportsEveryFailure() {
    DeployActivities activities = activities();
    failPhaseOnce(activities, DeployPhase.VERIFY, 1);

    execute(activities, request());

    verify(activities)
        .captureEvidence(eq(DeployPhase.VERIFY), anyString(), eq(PREVIOUS_SHA), eq(SHA));
    verify(activities).triage("/tmp/evidence");
    verify(activities).report(any(), any(), eq(999L), any());
    // The scratch directory does not outlive the run.
    verify(activities).discardEvidence("/tmp/evidence");
  }

  @Test
  void stillRecordsTheRunWhenReportingFails() {
    DeployActivities activities = activities();
    failPhaseOnce(activities, DeployPhase.VERIFY, 1);
    when(activities.report(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("GitHub is unreachable"));

    Outcome outcome = execute(activities, request());

    // Losing the notice is bad; losing the record of a rollback would be worse.
    assertThat(outcome.result().status()).isEqualTo(DeployStatus.ROLLED_BACK);
    // commitCommentUrl, not issueUrl: the sink is off in this request, so issueUrl is null
    // whether or not report threw, and asserting it would discriminate nothing.
    assertThat(lastRecordedRun(activities).commitCommentUrl()).isNull();
  }

  // ---------------------------------------------------------------------------
  // The Linear issue sink
  // ---------------------------------------------------------------------------

  @Test
  void filesToLinearBeforeCommentingAndPassesTheUrlToTheReport() {
    // The order is deliberate: the commit comment names the ticket, so the ticket has to exist
    // before the comment is written.
    DeployActivities activities = activities();
    LinearActivities linear = linearActivities();
    failPhaseOnce(activities, DeployPhase.VERIFY, 1);

    Outcome outcome = executeFiling(activities, linear, requestFilingToLinear());

    ArgumentCaptor<IssueFiling> filing = ArgumentCaptor.forClass(IssueFiling.class);
    InOrder order = inOrder(linear, activities);
    order.verify(linear).fileIssue(filing.capture());
    order.verify(activities).report(any(), any(), eq(999L), eq(LINEAR_URL));

    assertThat(filing.getValue().producer()).isEqualTo("deploy");
    // Phase and status, never the agent's headline: the fingerprint must be deterministic, and
    // two phrasings of one failure must not become two tickets.
    assertThat(filing.getValue().keyParts()).containsExactly("verify", "ROLLED_BACK");
    // The rendered title and body are the ones the GitHub issue used to carry.
    assertThat(filing.getValue().title()).contains("Deploy failed");
    assertThat(filing.getValue().body()).contains("previous version");
    assertThat(filing.getValue().occurrenceDetail()).contains(SHA);
    // The commit is part of the occurrence id, not just of the prose detail: the run id alone is
    // not unique per filing, because one run can file once per pass of the drain loop.
    assertThat(filing.getValue().occurrenceId()).contains(SHA);
    assertThat(filing.getValue().workflowId()).isEqualTo(DeployWorkflow.WORKFLOW_ID);

    assertThat(outcome.result().issueUrl()).isEqualTo(LINEAR_URL);
    DeployRunRecord record = lastRecordedRun(activities);
    assertThat(record.issueUrl()).isEqualTo(LINEAR_URL);
    assertThat(record.linearFilingFailed()).isFalse();
  }

  @Test
  void filesOneOccurrencePerCommitInTheDrainLoop() {
    // Two failing passes of the drain loop in ONE Temporal run. `verify` fails forever here, so
    // both passes fail the same way and produce identical key parts - which is the point: the
    // fingerprint is the same problem, so the occurrence id is the only thing that can tell the
    // second real failure apart from a replay of the first.
    //
    // With a bare run id as the occurrence id, IssueFiler's replay guard
    // (LinearIssueRecord.hasOccurrence) short-circuits the second filing and returns the first
    // pass's decision without touching Linear: no comment, no ticket, and the second commit's
    // failure is silently lost.
    DeployActivities activities = activities();
    LinearActivities linear = linearActivities();
    failPhase(activities, DeployPhase.VERIFY, 1);

    Outcome outcome =
        executeSignalling(activities, linear, requestFilingToLinear(), NEWER_SHA);

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.ROLLBACK_FAILED);

    ArgumentCaptor<IssueFiling> filings = ArgumentCaptor.forClass(IssueFiling.class);
    verify(linear, times(2)).fileIssue(filings.capture());
    List<IssueFiling> filed = filings.getAllValues();

    // Same problem, so deliberately the same key parts - the fingerprint must collapse these onto
    // one ticket.
    assertThat(filed)
        .extracting(IssueFiling::keyParts)
        .containsOnly(List.of("verify", "ROLLBACK_FAILED"));
    // One Temporal run, so the same workflow id throughout.
    assertThat(filed)
        .extracting(IssueFiling::workflowId)
        .containsOnly(DeployWorkflow.WORKFLOW_ID);
    // But two distinct occurrences, each naming its own commit.
    assertThat(filed).extracting(IssueFiling::occurrenceId).doesNotHaveDuplicates();
    assertThat(filed.get(0).occurrenceId()).contains(SHA);
    assertThat(filed.get(1).occurrenceId()).contains(NEWER_SHA);
  }

  @Test
  void stillRollsBackAndReportsWhenLinearFilingFails() {
    // The tracker being down must never change the deploy's outcome.
    DeployActivities activities = activities();
    LinearActivities linear = linearActivities();
    when(linear.fileIssue(any()))
        .thenThrow(ApplicationFailure.newNonRetryableFailure("Linear down", "LINEAR_API_ERROR"));
    failPhaseOnce(activities, DeployPhase.VERIFY, 1);

    Outcome outcome = executeFiling(activities, linear, requestFilingToLinear());

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.ROLLED_BACK);
    verify(activities).runPhase(eq(DeployPhase.ROLLBACK), any(), anyBoolean());
    // Reported anyway, with no ticket to name.
    verify(activities).report(any(), any(), eq(999L), eq(null));
    DeployRunRecord record = lastRecordedRun(activities);
    assertThat(record.linearFilingFailed()).isTrue();
    assertThat(record.issueUrl()).isNull();
  }

  @Test
  void stillRecordsTheRunWhenRenderingTheFailureFails() {
    // renderFailure is only markdown, but it is still an activity, and an exhausted one throws
    // into the workflow. Escaping there would fail the whole workflow and lose the record of a
    // rollback - which is the one thing this method must never trade away.
    DeployActivities activities = activities();
    LinearActivities linear = linearActivities();
    when(activities.renderFailure(any(), any()))
        .thenThrow(ApplicationFailure.newNonRetryableFailure("no renderer", "IllegalState"));
    failPhaseOnce(activities, DeployPhase.VERIFY, 1);

    Outcome outcome = executeFiling(activities, linear, requestFilingToLinear());

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.ROLLED_BACK);
    verify(linear, never()).fileIssue(any());
    DeployRunRecord record = lastRecordedRun(activities);
    assertThat(record.linearFilingFailed()).isTrue();
    assertThat(record.issueUrl()).isNull();
  }

  // 60s, where every other test here takes about a second. Narrowing the catch this test guards
  // does not make it fail - it makes it WAIT, because the workflow task is retried forever and
  // the test environment will not skip that. Without the timeout a regression hangs the suite
  // with no output; with it, the suite says which test and stops.
  @Timeout(60)
  @Test
  void stillRecordsTheRunWhenFilingThrowsSomethingOtherThanAnActivityFailure() {
    // The catch has to be as wide as the invariant. An exhausted activity arrives as
    // ActivityFailure, but plenty of things on the workflow thread do not - encoding the
    // IssueFiling payload, for one - and those are not TemporalFailures, so they fail the
    // workflow task and Temporal retries it forever: the deploy hangs and recordRun never runs.
    // A null Rendered reproduces that class cheaply, by NPE-ing on rendered.title().
    DeployActivities activities = activities();
    LinearActivities linear = linearActivities();
    when(activities.renderFailure(any(), any())).thenReturn(null);
    failPhaseOnce(activities, DeployPhase.VERIFY, 1);

    Outcome outcome = executeFiling(activities, linear, requestFilingToLinear());

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.ROLLED_BACK);
    verify(linear, never()).fileIssue(any());
    DeployRunRecord record = lastRecordedRun(activities);
    assertThat(record.linearFilingFailed()).isTrue();
    assertThat(record.issueUrl()).isNull();
  }

  @Test
  void nothingIsFiledWhenTheSinkIsDisabled() {
    // With factory.linear.enabled false nothing polls the `linear` queue, so scheduling the
    // activity at all would stall the deploy until its schedule-to-close timeout. The queue IS
    // polled here, which is what makes "nothing was scheduled" the only reading of this result.
    DeployActivities activities = activities();
    LinearActivities linear = linearActivities();
    failPhaseOnce(activities, DeployPhase.VERIFY, 1);

    Outcome outcome = executeFiling(activities, linear, request());

    verify(linear, never()).fileIssue(any());
    verify(activities).report(any(), any(), eq(999L), eq(null));
    assertThat(outcome.result().issueUrl()).isNull();
    assertThat(lastRecordedRun(activities).linearFilingFailed()).isFalse();
  }

  @Test
  void filesNothingWhenTheDeploySucceeds() {
    DeployActivities activities = activities();
    LinearActivities linear = linearActivities();

    Outcome outcome = executeFiling(activities, linear, requestFilingToLinear());

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.DEPLOYED);
    verify(linear, never()).fileIssue(any());
  }

  @Test
  void filesNothingWhenOnlyImagesWereDeployed() {
    // DEPLOYED_IMAGES_ONLY still comments, because a half-applied deploy must not be silent - but
    // it is not a failure, so it does not open a ticket.
    DeployActivities activities = activities();
    LinearActivities linear = linearActivities();

    Outcome outcome = executeFiling(activities, linear, requestFilingToLinear(false, true));

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.DEPLOYED_IMAGES_ONLY);
    verify(linear, never()).fileIssue(any());
    verify(activities).report(any(), eq(null), eq(999L), eq(null));
  }

  // ---------------------------------------------------------------------------
  // Held-back configuration
  // ---------------------------------------------------------------------------

  @Test
  void deploysImagesAndReportsWhenConfigurationSyncIsHeldBack() {
    DeployActivities activities = activities();
    when(activities.syncConfig(anyString(), anyBoolean()))
        .thenReturn(
            new SyncOutcome(
                SyncDecision.HELD_BACK, null, SHA, List.of("mongodb"), List.of("mongodb"), null,
                "docker compose -f docker-compose.prod.yml up -d mongodb",
                "a configuration change affects a service outside the recreate allowlist"));

    Outcome outcome = execute(activities, request());

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.DEPLOYED_IMAGES_ONLY);
    // Reported even though the deploy succeeded: "deployed, but not all of it" must not be silent.
    verify(activities).report(any(), eq(null), eq(999L), eq(null));
    DeployRunRecord record = lastRecordedRun(activities);
    assertThat(record.configSync().heldBackServices()).containsExactly("mongodb");
    assertThat(record.configSync().manualCommand()).contains("up -d mongodb");
  }

  @Test
  void failsWithoutTouchingTheStackWhenConfigurationSyncErrors() {
    DeployActivities activities = activities();
    when(activities.syncConfig(anyString(), anyBoolean()))
        .thenReturn(sync(SyncDecision.FAILED, null));

    Outcome outcome = execute(activities, request());

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.FAILED);
    verify(activities, never()).runPhase(any(), any(), anyBoolean());
  }

  // ---------------------------------------------------------------------------
  // Idempotency
  // ---------------------------------------------------------------------------

  @Test
  void phaseRetriedAfterInfrastructureFaultIsNotAppliedTwice() {
    // The activity is retried by Temporal for an infrastructure fault. The script guarantees each
    // phase is safe to re-run, and the workflow must not double-count the outcome.
    DeployActivities activities = activities();
    AtomicInteger recreateCalls = new AtomicInteger();
    when(activities.runPhase(eq(DeployPhase.RECREATE), any(), anyBoolean()))
        .thenAnswer(
            invocation -> {
              if (recreateCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("docker daemon was briefly unreachable");
              }
              return succeeded(DeployPhase.RECREATE);
            });

    Outcome outcome = execute(activities, request());

    assertThat(outcome.result().status()).isEqualTo(DeployStatus.DEPLOYED);
    assertThat(recreateCalls.get()).isEqualTo(2);
    // One RECREATE entry in the record, not two.
    assertThat(lastRecordedRun(activities).phases())
        .filteredOn(phase -> phase.phase() == DeployPhase.RECREATE)
        .hasSize(1);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static DeployActivities activities() {
    DeployActivities activities =
        mock(DeployActivities.class, withSettings().withoutAnnotations());
    when(activities.syncConfig(anyString(), anyBoolean()))
        .thenReturn(sync(SyncDecision.APPLIED, PREVIOUS_SHA));
    when(activities.runPhase(any(), any(), anyBoolean()))
        .thenAnswer(invocation -> succeeded(invocation.getArgument(0)));
    when(activities.rollbackConfig(anyString(), anyBoolean()))
        .thenReturn(succeeded(DeployPhase.ROLLBACK_CONFIG));
    when(activities.captureEvidence(any(), any(), any(), any())).thenReturn("/tmp/evidence");
    when(activities.triage(anyString()))
        .thenReturn(
            new DeployActivities.Triage(
                "backend never became healthy", "diagnosis", "high", "container-startup",
                List.of("backend"), List.of(), "check the backend logs"));
    when(activities.renderFailure(any(), any()))
        .thenAnswer(
            invocation ->
                new DeployActivities.Rendered(
                    "Deploy failed: backend never became healthy (0123456)",
                    "The site is up, on the previous version."));
    when(activities.report(any(), any(), any(), any()))
        .thenReturn(new DeployActivities.Report("https://github.com/o/r/commit/x#c1"));
    return activities;
  }

  /** The issue sink, mocked, filing successfully. */
  private static LinearActivities linearActivities() {
    LinearActivities linear = mock(LinearActivities.class, withSettings().withoutAnnotations());
    when(linear.fileIssue(any()))
        .thenReturn(new FiledIssue(FilingDecision.FILED_NEW, "SIM-9", LINEAR_URL, "fp"));
    return linear;
  }

  /** Fails a phase on every call — for a phase the rollback path does not itself re-run. */
  private static void failPhase(
      final DeployActivities activities, final DeployPhase phase, final int exitCode) {
    when(activities.runPhase(eq(phase), any(), anyBoolean()))
        .thenReturn(new PhaseOutcome(phase, false, exitCode, phase + " failed", 10L));
  }

  /**
   * Fails a phase on its first call only.
   *
   * <p>Needed for {@code verify} and {@code verify-public}, because the rollback path runs them
   * again against the restored version. Failing them forever would model "the previous version is
   * also broken", which is a different scenario — and stubbing it that way by accident is how a
   * rollback test silently becomes a rollback-failure test.
   */
  private static void failPhaseOnce(
      final DeployActivities activities, final DeployPhase phase, final int exitCode) {
    AtomicInteger calls = new AtomicInteger();
    when(activities.runPhase(eq(phase), any(), anyBoolean()))
        .thenAnswer(
            invocation ->
                calls.getAndIncrement() == 0
                    ? new PhaseOutcome(phase, false, exitCode, phase + " failed", 10L)
                    : succeeded(phase));
  }

  private static PhaseOutcome succeeded(final DeployPhase phase) {
    return new PhaseOutcome(phase, true, 0, phase + " ok", 10L);
  }

  private static SyncOutcome sync(final SyncDecision decision, final String previousSha) {
    return new SyncOutcome(
        decision, previousSha, SHA, List.of("backend"), List.of(), null, null, decision.name());
  }

  private static DeployRunRecord lastRecordedRun(final DeployActivities activities) {
    ArgumentCaptor<DeployRunRecord> captor = ArgumentCaptor.forClass(DeployRunRecord.class);
    verify(activities, org.mockito.Mockito.atLeastOnce()).recordRun(captor.capture());
    return captor.getValue();
  }

  /**
   * The two-queue environment production runs.
   *
   * <p>{@code linear} is a second worker on its own task queue, exactly as in production, where it
   * is polled by {@code software-factory} and not by the {@code deployer}. Passing null models the
   * sink not being deployed at all; passing a mock while leaving {@code linearFilingEnabled} false
   * is the stronger case, because then "nothing was filed" cannot be explained away by nothing
   * polling the queue.
   */
  private TestWorkflowEnvironment environment(
      final DeployActivities activities, final LinearActivities linear) {
    TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance();
    Worker worker = environment.newWorker(DeployTaskQueues.DEPLOY);
    worker.registerWorkflowImplementationTypes(DeployWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities);
    if (linear != null) {
      environment.newWorker(LinearTaskQueues.LINEAR).registerActivitiesImplementations(linear);
    }
    environment.start();
    return environment;
  }

  private DeployWorkflow stub(final TestWorkflowEnvironment environment) {
    return environment
        .getWorkflowClient()
        .newWorkflowStub(
            DeployWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(DeployTaskQueues.DEPLOY)
                .setWorkflowId(DeployWorkflow.WORKFLOW_ID)
                .build());
  }

  private Outcome execute(final DeployActivities activities, final DeployRequest request) {
    return executeFiling(activities, null, request);
  }

  private Outcome executeFiling(
      final DeployActivities activities,
      final LinearActivities linear,
      final DeployRequest request) {
    // A filing scheduled on a queue nothing polls does not fail - it waits, and the test
    // environment will not skip that timer, so the whole suite hangs indefinitely instead of
    // reporting anything. This class has been bitten by that twice; trade it for a legible throw.
    if (linear == null && request.linearFilingEnabled()) {
      throw new IllegalArgumentException(
          "linearFilingEnabled needs a LinearActivities worker - use executeFiling(..), "
              + "not execute(..), or the suite will hang rather than fail");
    }
    try (TestWorkflowEnvironment environment = environment(activities, linear)) {
      DeployWorkflow workflow = stub(environment);
      DeployResult result = workflow.run(request);
      return new Outcome(result);
    }
  }

  /**
   * Runs a deploy that signals itself with {@code signalledSha} from inside the {@code pull}
   * activity, exactly once.
   *
   * <p>Signalling from within an activity rather than from the test thread is what makes the
   * coalescing tests deterministic: the test environment skips time, so a signal sent from outside
   * would race the workflow to completion.
   */
  private Outcome executeSignalling(
      final DeployActivities activities,
      final DeployRequest request,
      final String signalledSha) {
    return executeSignalling(activities, null, request, signalledSha);
  }

  private Outcome executeSignalling(
      final DeployActivities activities,
      final LinearActivities linear,
      final DeployRequest request,
      final String signalledSha) {
    // Same trade as executeFiling: a filing on an unpolled queue hangs the suite rather than
    // failing it, so refuse the combination up front.
    if (linear == null && request.linearFilingEnabled()) {
      throw new IllegalArgumentException(
          "linearFilingEnabled needs a LinearActivities worker - pass one to executeSignalling, "
              + "or the suite will hang rather than fail");
    }
    try (TestWorkflowEnvironment environment = environment(activities, linear)) {
      DeployWorkflow workflow = stub(environment);
      DeployWorkflow signaller =
          environment
              .getWorkflowClient()
              .newWorkflowStub(DeployWorkflow.class, DeployWorkflow.WORKFLOW_ID);
      AtomicInteger signals = new AtomicInteger();
      when(activities.runPhase(eq(DeployPhase.PULL), any(), anyBoolean()))
          .thenAnswer(
              invocation -> {
                if (signals.getAndIncrement() == 0) {
                  signaller.deployRequested(signalledSha);
                }
                return succeeded(DeployPhase.PULL);
              });
      DeployResult result = workflow.run(request);
      return new Outcome(result);
    }
  }

  private record Outcome(DeployResult result) {
  }
}
