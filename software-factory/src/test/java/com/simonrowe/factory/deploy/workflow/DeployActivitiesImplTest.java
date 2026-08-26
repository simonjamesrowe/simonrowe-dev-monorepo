package com.simonrowe.factory.deploy.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.deploy.agent.TriageEngine;
import com.simonrowe.factory.deploy.config.DeployProperties;
import com.simonrowe.factory.deploy.domain.DeployPhase;
import com.simonrowe.factory.deploy.domain.PhaseOutcome;
import com.simonrowe.factory.deploy.domain.SyncDecision;
import com.simonrowe.factory.deploy.domain.SyncOutcome;
import com.simonrowe.factory.deploy.github.DeployReportGateway;
import com.simonrowe.factory.deploy.github.DeployReportRenderer;
import com.simonrowe.factory.deploy.persistence.DeployRunRepository;
import com.simonrowe.factory.deploy.shell.PhaseRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeployActivitiesImplTest {

  private final PhaseRunner phaseRunner = mock(PhaseRunner.class);
  private final DeployRunRepository runs = mock(DeployRunRepository.class);
  private final TriageEngine triageEngine = mock(TriageEngine.class);
  private final DeployReportGateway reportGateway = mock(DeployReportGateway.class);

  private final DeployProperties properties =
      new DeployProperties(
          true, false, null, null, null, null, null, null, null, null, null, null, null, null,
          "/tmp/deploy-state-test", Duration.ofMinutes(30), null);

  private final DeployActivitiesImpl activities =
      new DeployActivitiesImpl(
          properties,
          phaseRunner,
          runs,
          triageEngine,
          reportGateway,
          new DeployReportRenderer());

  private static PhaseRunner.PhaseExecution execution(
      final int exitCode, final String output, final Map<String, String> values) {
    return new PhaseRunner.PhaseExecution(exitCode, output, values, 1234L);
  }

  // ---------------------------------------------------------------------------
  // runPhase
  // ---------------------------------------------------------------------------

  @Test
  void mapsExitZeroToSuccess() {
    when(phaseRunner.run(any(), any(), any(), anyBoolean(), any()))
        .thenReturn(execution(0, "all good", Map.of()));

    PhaseOutcome outcome = activities.runPhase(DeployPhase.PULL, "sha", false);

    assertThat(outcome.succeeded()).isTrue();
    assertThat(outcome.exitCode()).isZero();
    assertThat(outcome.durationMillis()).isEqualTo(1234L);
  }

  @Test
  void mapsExitOneToFailureWithoutThrowing() {
    // Deliberately does not throw. A phase that legitimately fails is the signal to roll back,
    // not an activity error - throwing would put it through the retry policy and turn one failed
    // verification into three, tripling the outage before the rollback even starts.
    when(phaseRunner.run(any(), any(), any(), anyBoolean(), any()))
        .thenReturn(execution(1, "verify failed", Map.of()));

    PhaseOutcome outcome = activities.runPhase(DeployPhase.VERIFY, null, false);

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.exitCode()).isEqualTo(1);
    assertThat(outcome.declined()).isFalse();
  }

  @Test
  void mapsExitTwoToDeclined() {
    when(phaseRunner.run(any(), any(), any(), anyBoolean(), any()))
        .thenReturn(execution(2, "declined", Map.of()));

    PhaseOutcome outcome = activities.runPhase(DeployPhase.SYNC_CONFIG, null, false);

    assertThat(outcome.declined()).isTrue();
    assertThat(outcome.succeeded()).isFalse();
  }

  @Test
  void boundsThePhaseDetailSoTheRecordNeverBecomesLogStore() {
    when(phaseRunner.run(any(), any(), any(), anyBoolean(), any()))
        .thenReturn(execution(1, "x".repeat(PhaseOutcome.MAX_DETAIL + 5000), Map.of()));

    PhaseOutcome outcome = activities.runPhase(DeployPhase.VERIFY, null, false);

    assertThat(outcome.detail()).hasSize(PhaseOutcome.MAX_DETAIL);
  }

  @Test
  void passesTheImageTagOnlyForTheGivenPhase() {
    when(phaseRunner.run(any(), any(), any(), anyBoolean(), any()))
        .thenReturn(execution(0, "", Map.of()));

    activities.runPhase(DeployPhase.PULL, "abc123", true);

    verify(phaseRunner).run(eq(DeployPhase.PULL), eq(null), eq("abc123"), eq(true), any());
  }

  // ---------------------------------------------------------------------------
  // syncConfig
  // ---------------------------------------------------------------------------

  @Test
  void readsTheAppliedDecisionAndThePreviousCommit() {
    when(phaseRunner.run(any(), any(), any(), anyBoolean(), any()))
        .thenReturn(
            execution(
                0,
                "",
                Map.of("decision", "applied", "previous-sha", "old", "affected", "backend nginx")));

    SyncOutcome outcome = activities.syncConfig("new", false);

    assertThat(outcome.decision()).isEqualTo(SyncDecision.APPLIED);
    assertThat(outcome.previousSha()).isEqualTo("old");
    assertThat(outcome.targetSha()).isEqualTo("new");
    assertThat(outcome.affectedServices()).containsExactly("backend", "nginx");
  }

  @Test
  void dropsThePreviousCommitForEveryDecisionThatDidNotMoveHead() {
    // The invariant the rollback path rests on: a non-null previousSha means, and only means,
    // that HEAD moved and must be restored. Keeping it on a decline would make the rollback reset
    // a checkout that was never moved.
    List<String> declines =
        List.of("dirty-tree", "not-an-ancestor", "held-back", "missing-variable");
    for (String decision : declines) {
      when(phaseRunner.run(any(), any(), any(), anyBoolean(), any()))
          .thenReturn(execution(2, "", Map.of("decision", decision, "previous-sha", "old")));

      SyncOutcome outcome = activities.syncConfig("new", false);

      assertThat(outcome.previousSha()).as(decision).isNull();
      assertThat(outcome.decision().movedHead()).as(decision).isFalse();
    }
  }

  @Test
  void readsTheHeldBackServicesAndTheManualCommand() {
    when(phaseRunner.run(any(), any(), any(), anyBoolean(), any()))
        .thenReturn(
            execution(
                2,
                "",
                Map.of(
                    "decision", "held-back",
                    "held-back", "mongodb,elasticsearch",
                    "manual-command", "docker compose up -d mongodb")));

    SyncOutcome outcome = activities.syncConfig("new", false);

    assertThat(outcome.decision()).isEqualTo(SyncDecision.HELD_BACK);
    assertThat(outcome.heldBackServices()).containsExactly("mongodb", "elasticsearch");
    assertThat(outcome.manualCommand()).isEqualTo("docker compose up -d mongodb");
  }

  @Test
  void readsTheMissingVariableName() {
    when(phaseRunner.run(any(), any(), any(), anyBoolean(), any()))
        .thenReturn(
            execution(
                2, "", Map.of("decision", "missing-variable", "missing-variable", "NEW_THING")));

    SyncOutcome outcome = activities.syncConfig("new", false);

    assertThat(outcome.decision()).isEqualTo(SyncDecision.MISSING_VARIABLE);
    assertThat(outcome.missingVariable()).isEqualTo("NEW_THING");
  }

  @Test
  void treatsUnrecognisedDecisionAsFailureRatherThanApplied() {
    // Fail-safe direction: a decision string this code does not know must never be read as
    // "HEAD moved successfully".
    when(phaseRunner.run(any(), any(), any(), anyBoolean(), any()))
        .thenReturn(execution(1, "something went wrong", Map.of("decision", "who-knows")));

    SyncOutcome outcome = activities.syncConfig("new", false);

    assertThat(outcome.decision()).isEqualTo(SyncDecision.FAILED);
    assertThat(outcome.previousSha()).isNull();
  }

  // ---------------------------------------------------------------------------
  // triage and report
  // ---------------------------------------------------------------------------

  @Test
  void fallsBackToAnUnavailableDiagnosisWhenTheAgentFails() {
    when(triageEngine.diagnose(any(), any())).thenThrow(new IllegalStateException("claude died"));

    DeployActivities.Triage triage = activities.triage("/tmp/evidence");

    // Knowing the deploy failed and being told the diagnosis is missing beats no issue at all.
    assertThat(triage.confidence()).isEqualTo("low");
    assertThat(triage.diagnosis()).contains("claude died");
  }

  @Test
  void reportsWhatItManagedToPostWhenTheIssueCannotBeOpened() {
    when(reportGateway.openIssue(anyString(), anyString(), any(), any()))
        .thenThrow(new IllegalStateException("GitHub 500"));
    when(reportGateway.commentOnCommit(anyString(), anyString(), any()))
        .thenReturn("https://github.com/o/r/commit/x#c1");

    DeployActivities.Report report = activities.report(record(), null, 1L);

    assertThat(report.issueUrl()).isNull();
    assertThat(report.commitCommentUrl()).isEqualTo("https://github.com/o/r/commit/x#c1");
  }

  @Test
  void stillOpensTheIssueWhenTheCommitCommentFails() {
    when(reportGateway.openIssue(anyString(), anyString(), any(), any()))
        .thenReturn("https://github.com/o/r/issues/1");
    when(reportGateway.commentOnCommit(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("GitHub 500"));

    DeployActivities.Report report = activities.report(record(), null, 1L);

    assertThat(report.issueUrl()).isEqualTo("https://github.com/o/r/issues/1");
    assertThat(report.commitCommentUrl()).isNull();
  }

  // ---------------------------------------------------------------------------
  // recordRun and evidence cleanup
  // ---------------------------------------------------------------------------

  @Test
  void upsertsTheRunRecord() {
    activities.recordRun(record());

    verify(runs).save(any());
  }

  @Test
  void refusesToDeleteAnythingOutsideTheStateDirectory() {
    // This method takes a string across an activity boundary and deletes a tree. The only caller
    // passes a path this class created, but the guard costs three lines.
    activities.discardEvidence("/etc");

    assertThat(java.nio.file.Files.exists(java.nio.file.Path.of("/etc"))).isTrue();
  }

  @Test
  void toleratesDiscardOfNothing() {
    activities.discardEvidence(null);
    activities.discardEvidence("");
  }

  private static com.simonrowe.factory.deploy.persistence.DeployRunRecord record() {
    return new com.simonrowe.factory.deploy.persistence.DeployRunRecord(
        "run-1",
        "deploy-prod",
        "sha-1",
        "workflow_run",
        java.time.Instant.parse("2026-08-26T10:00:00Z"),
        java.time.Instant.parse("2026-08-26T10:05:00Z"),
        com.simonrowe.factory.deploy.domain.DeployStatus.ROLLED_BACK,
        List.of(),
        new SyncOutcome(SyncDecision.APPLIED, "old", "sha-1", List.of(), List.of(), null, null, ""),
        true,
        com.simonrowe.factory.deploy.domain.DeployStatus.ROLLED_BACK,
        false,
        null,
        null,
        "rolled back");
  }

  @Test
  void heartbeatOutsideAnActivityContextDoesNotThrow() {
    // The same helper is used in production and in unit tests. A unit test has no Temporal
    // activity context, and must not fail because of it.
    when(phaseRunner.run(any(), any(), any(), anyBoolean(), any()))
        .thenReturn(execution(0, "", Map.of()));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Consumer<String>> heartbeat = ArgumentCaptor.forClass(Consumer.class);

    activities.runPhase(DeployPhase.VERIFY, null, false);

    verify(phaseRunner).run(any(), any(), any(), anyBoolean(), heartbeat.capture());
    assertThatNoException().isThrownBy(() -> heartbeat.getValue().accept("still going"));
  }
}
