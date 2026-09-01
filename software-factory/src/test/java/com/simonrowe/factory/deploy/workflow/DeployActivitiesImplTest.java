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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import io.temporal.client.WorkflowClient;

class DeployActivitiesImplTest {

  private final PhaseRunner phaseRunner = mock(PhaseRunner.class);
  private final DeployRunRepository runs = mock(DeployRunRepository.class);
  private final TriageEngine triageEngine = mock(TriageEngine.class);
  private final DeployReportGateway reportGateway = mock(DeployReportGateway.class);
  // The real renderer, not a mock: renderFailure's whole claim is that it delegates to it.
  private final DeployReportRenderer renderer = new DeployReportRenderer();

  @TempDir private Path stateDir;

  private DeployProperties properties;
  private DeployActivitiesImpl activities;

  @BeforeEach
  void setUp() {
    properties =
        new DeployProperties(
            true, false, null, null, null, null, null, null, null, null, null, null, null, null,
            stateDir.toString(), Duration.ofMinutes(30), null, false);
    activities =
        new DeployActivitiesImpl(
            properties, phaseRunner, runs, triageEngine, reportGateway, renderer,
            mock(WorkflowClient.class));
  }

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
  void namesTheLinearTicketInTheCommitComment() {
    // The comment is the in-context breadcrumb on the merge that broke prod, and its whole value
    // over the ticket is that it points at the ticket.
    when(reportGateway.commentOnCommit(anyString(), anyString(), any()))
        .thenReturn("https://github.com/o/r/commit/x#c1");

    DeployActivities.Report report =
        activities.report(record(), null, 1L, "https://linear.app/i/SIM-9");

    assertThat(report.commitCommentUrl()).isEqualTo("https://github.com/o/r/commit/x#c1");
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(reportGateway).commentOnCommit(anyString(), body.capture(), any());
    assertThat(body.getValue()).contains("https://linear.app/i/SIM-9");
  }

  @Test
  void returnsNothingRatherThanThrowingWhenTheCommitCommentFails() {
    when(reportGateway.commentOnCommit(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("GitHub 500"));

    DeployActivities.Report report = activities.report(record(), null, 1L, null);

    // A deploy that rolled back successfully but could not comment about it has still done the
    // important part, so this must not fail the activity.
    assertThat(report.commitCommentUrl()).isNull();
  }

  @Test
  void rendersTheFailureFromTheSameTitleAndBodyTheGitHubIssueUsed() {
    // Re-targeted, not rewritten: renderFailure is the existing issueTitle/issueBody, exposed as
    // an activity because a @WorkflowImpl holds no Spring bean and cannot reach the renderer.
    DeployActivities.Rendered rendered = activities.renderFailure(record(), null);

    assertThat(rendered.title()).isEqualTo(renderer.issueTitle(record(), null));
    assertThat(rendered.body()).isEqualTo(renderer.issueBody(record(), null));
    assertThat(rendered.title()).contains("Deploy failed");
  }

  // ---------------------------------------------------------------------------
  // recordRun and evidence cleanup
  // ---------------------------------------------------------------------------

  @Test
  void upsertsTheRunRecord() {
    activities.recordRun(record());

    verify(runs).save(any());
  }

  // ---------------------------------------------------------------------------
  // captureEvidence / discardEvidence
  //
  // The failure path is the only reason this feature is acceptable, and a triage with nothing to
  // read is no triage at all - so these exercise the real filesystem, in a temp state dir.
  // ---------------------------------------------------------------------------

  @Test
  void capturesTheFourEvidenceFilesTheAgentIsPromisedItsPromptNames() {
    when(phaseRunner.capture(anyString(), any(), any(String[].class)))
        .thenReturn(execution(0, "captured output", Map.of()));

    String directory =
        activities.captureEvidence(DeployPhase.VERIFY, "verify failed", "old-sha", "new-sha");

    Path evidence = Path.of(directory);
    assertThat(evidence).isDirectory();
    assertThat(evidence.resolve("phase-output.txt")).exists();
    assertThat(evidence.resolve("compose-ps.txt")).exists();
    assertThat(evidence.resolve("container-logs.txt")).exists();
    assertThat(evidence.resolve("commit-range.txt")).exists();
  }

  @Test
  void namesTheFailingPhaseInTheCapturedOutput() throws Exception {
    when(phaseRunner.capture(anyString(), any(), any(String[].class)))
        .thenReturn(execution(0, "", Map.of()));

    String directory =
        activities.captureEvidence(DeployPhase.VERIFY, "backend unhealthy", "old", "new");

    assertThat(Files.readString(Path.of(directory).resolve("phase-output.txt")))
        .contains("Failed phase: verify")
        .contains("backend unhealthy");
  }

  @Test
  void saysSoRatherThanFailingWhenThereIsNoPreviousCommit() throws Exception {
    when(phaseRunner.capture(anyString(), any(), any(String[].class)))
        .thenReturn(execution(0, "", Map.of()));

    String directory = activities.captureEvidence(DeployPhase.PULL, "pull failed", null, "new");

    assertThat(Files.readString(Path.of(directory).resolve("commit-range.txt")))
        .contains("not known");
  }

  @Test
  void keepsCapturingWhenOneEvidenceCommandFails() throws Exception {
    // Best-effort by definition: this runs because something is already broken, so a command
    // that fails here must not take the whole diagnosis down with it.
    when(phaseRunner.capture(anyString(), any(), any(String[].class)))
        .thenThrow(new IllegalStateException("docker is unreachable"));

    String directory = activities.captureEvidence(DeployPhase.VERIFY, "failed", "old", "new");

    assertThat(Files.readString(Path.of(directory).resolve("compose-ps.txt")))
        .contains("Could not capture")
        .contains("docker is unreachable");
    assertThat(Path.of(directory).resolve("container-logs.txt")).exists();
  }

  @Test
  void boundsEachEvidenceFileSoCrashLoopingContainerCannotFillVolume() throws Exception {
    when(phaseRunner.capture(anyString(), any(), any(String[].class)))
        .thenReturn(execution(0, "y".repeat(2_000_000), Map.of()));


    String directory = activities.captureEvidence(DeployPhase.VERIFY, "failed", "old", "new");

    String logs = Files.readString(Path.of(directory).resolve("container-logs.txt"));
    assertThat(logs.length()).isLessThan(1_000_000);
    // The tail is kept, and the truncation is stated rather than silent.
    assertThat(logs).startsWith("[truncated to the last");
  }

  @Test
  void discardsTheEvidenceDirectory() {
    when(phaseRunner.capture(anyString(), any(), any(String[].class)))
        .thenReturn(execution(0, "", Map.of()));
    String directory = activities.captureEvidence(DeployPhase.VERIFY, "failed", "old", "new");

    activities.discardEvidence(directory);

    assertThat(Path.of(directory)).doesNotExist();
  }

  @Test
  void refusesToDeleteAnythingOutsideTheStateDirectory() {
    // The only caller passes a path this class created, but this method takes a string across an
    // activity boundary and deletes a tree, so the guard is worth the three lines.
    activities.discardEvidence("/etc");

    assertThat(Files.exists(Path.of("/etc"))).isTrue();
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
        "rolled back",
        false);
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
