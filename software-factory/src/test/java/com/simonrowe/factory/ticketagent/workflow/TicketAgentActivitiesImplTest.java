package com.simonrowe.factory.ticketagent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.cvefix.domain.NpmRepairTitle;
import com.simonrowe.factory.git.DependencyPins;
import com.simonrowe.factory.git.NpmLockPins;
import com.simonrowe.factory.git.RepositoryWorkspace;
import com.simonrowe.factory.git.RepositoryWorkspaceFactory;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.LinearTicket;
import com.simonrowe.factory.linear.domain.TrackedIssue;
import com.simonrowe.factory.linear.linear.LinearApiException;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.testsupport.TicketAgentTestProperties;
import com.simonrowe.factory.ticketagent.agent.AgentEdit;
import com.simonrowe.factory.ticketagent.agent.TicketAgentEngine;
import com.simonrowe.factory.ticketagent.api.TicketAgentService;
import com.simonrowe.factory.ticketagent.domain.AttemptRequest;
import com.simonrowe.factory.ticketagent.domain.AttemptResult;
import com.simonrowe.factory.ticketagent.domain.ChecksReport;
import com.simonrowe.factory.ticketagent.domain.PullRequestRef;
import com.simonrowe.factory.ticketagent.domain.PullRequestState;
import com.simonrowe.factory.ticketagent.domain.TicketAgentSettings;
import com.simonrowe.factory.ticketagent.github.TicketAgentGitHub;
import com.simonrowe.factory.ticketagent.npm.NpmLockfileRepair;
import com.simonrowe.factory.ticketagent.npm.NpmRepairUnavailableException;
import io.temporal.failure.ApplicationFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

class TicketAgentActivitiesImplTest {

  private static final Consumer<String> NO_HEARTBEAT = detail -> { };
  private static final PullRequestRef PR =
      new PullRequestRef(271, "https://github.com/o/r/pull/271", "PR_271");

  private final LinearGateway linear = mock(LinearGateway.class);
  private final TicketAgentGitHub gitHub = mock(TicketAgentGitHub.class);
  private final TicketAgentEngine engine = mock(TicketAgentEngine.class);
  private final RepositoryWorkspaceFactory workspaces = mock(RepositoryWorkspaceFactory.class);
  private final RepositoryWorkspace workspace = mock(RepositoryWorkspace.class);
  private final NpmLockfileRepair npmRepair = mock(NpmLockfileRepair.class);
  private final TicketAgentActivitiesImpl activities =
      new TicketAgentActivitiesImpl(
          TicketAgentTestProperties.of(true), linear, gitHub, engine, workspaces,
          mock(TicketAgentService.class), npmRepair);

  @TempDir Path checkout;

  private static LinearTicket ticket() {
    return new LinearTicket("issue-1", "SIM-107", "ERROR in backend", "Seen 41 times.",
        "https://linear/SIM-107", IssueStateType.TRIAGE, "Triage", List.of("factory:agent"), 2,
        Instant.EPOCH);
  }

  @BeforeEach
  void workspace() {
    when(gitHub.installationId()).thenReturn(7L);
    when(workspace.repository()).thenReturn(checkout);
    when(workspaces.create(anyString(), anyString(), any(), any(), anyString(), any(), any()))
        .thenReturn(workspace);
    when(linear.teamContext())
        .thenReturn(new LinearGateway.TeamContext("t", "s-triage", "s-done", Map.of()));
  }

  private AttemptResult attempt(final int attempt, final boolean dryRun) {
    return activities.attempt(
        new AttemptRequest(ticket(), "agent/sim-107", attempt,
            attempt == 1 ? null : "Backend failed", dryRun),
        NO_HEARTBEAT);
  }

  private void agentChanges(final List<String> paths) {
    when(engine.attempt(eq(checkout), anyString(), any()))
        .thenReturn(new AgentEdit(true, "Guarded the exporter.", "fix: guard exporter", "Body"));
    when(workspaces.changedPaths(workspace, NO_HEARTBEAT)).thenReturn(paths);
  }

  @Test
  void settingsCarryTheConfiguredBudget() {
    TicketAgentSettings settings = activities.settings();

    assertThat(settings.maxAttempts()).isEqualTo(3);
    assertThat(settings.label()).isEqualTo("factory:agent");
    assertThat(settings.branchPrefix()).isEqualTo("agent/");
  }

  @Test
  void theFirstAttemptClonesMainAndForcePushes() {
    agentChanges(List.of("backend/src/main/java/A.java"));
    when(workspaces.stagedDiff(workspace, NO_HEARTBEAT)).thenReturn("+++ b/A.java\n+ ok\n");
    when(workspaces.commitAndPush(eq(workspace), eq("agent/sim-107"), eq("fix: guard exporter"),
        anyString(), anyString(), eq(7L), eq(true), any())).thenReturn("sha1");

    AttemptResult result = attempt(1, false);

    assertThat(result.status()).isEqualTo(AttemptResult.Status.PUSHED);
    assertThat(result.headSha()).isEqualTo("sha1");
    assertThat(result.silencing()).isEmpty();
    verify(workspaces).create(eq("simonjamesrowe"), eq("simonrowe-dev-monorepo"), eq(7L), any(),
        eq("ticket-agent-"), isNull(), any());
    verify(engine).attempt(eq(checkout), contains("attempt 1 of 3"), any());
  }

  @Test
  void repairClonesTheBranchCarriesTheFailureAndNeverForces() {
    agentChanges(List.of("backend/src/main/java/A.java"));
    when(workspaces.stagedDiff(workspace, NO_HEARTBEAT)).thenReturn("");
    when(workspaces.commitAndPush(any(), anyString(), anyString(), anyString(), anyString(),
        any(), anyBoolean(), any())).thenReturn("sha2");

    attempt(2, false);

    verify(workspaces).create(anyString(), anyString(), any(), any(), anyString(),
        eq("agent/sim-107"), any());
    verify(engine).attempt(eq(checkout), contains("Backend failed"), any());
    verify(workspaces).commitAndPush(eq(workspace), eq("agent/sim-107"),
        eq("fix: address CI and review feedback (attempt 2)"), anyString(), anyString(), eq(7L),
        eq(false), any());
  }

  @Test
  void agentThatChangedNothingPushesNothing() {
    when(engine.attempt(any(), anyString(), any()))
        .thenReturn(new AgentEdit(false, "Third-party noise.", "", ""));
    when(workspaces.changedPaths(workspace, NO_HEARTBEAT)).thenReturn(List.of());

    AttemptResult result = attempt(1, false);

    assertThat(result.status()).isEqualTo(AttemptResult.Status.NO_CHANGE);
    assertThat(result.summary()).isEqualTo("Third-party noise.");
    verify(workspaces, never()).commitAndPush(any(), anyString(), anyString(), anyString(),
        anyString(), any(), anyBoolean(), any());
  }

  @Test
  void deniedPathIsNeverPushed() {
    agentChanges(List.of("backend/A.java", "scripts/restart-prod.sh"));

    AttemptResult result = attempt(1, false);

    assertThat(result.status()).isEqualTo(AttemptResult.Status.DENIED);
    assertThat(result.summary()).contains("scripts/restart-prod.sh");
    verify(workspaces, never()).stagedDiff(any(), any());
    verify(workspaces, never()).commitAndPush(any(), anyString(), anyString(), anyString(),
        anyString(), any(), anyBoolean(), any());
  }

  @Test
  void dryRunReturnsTheDiffAndPushesNothing() {
    agentChanges(List.of("backend/src/main/resources/application.yml"));
    when(workspaces.stagedDiff(workspace, NO_HEARTBEAT))
        .thenReturn("+++ b/backend/src/main/resources/application.yml\n+    a.b: ERROR\n");

    AttemptResult result = attempt(1, true);

    assertThat(result.status()).isEqualTo(AttemptResult.Status.DRY_RUN);
    assertThat(result.diff()).contains("a.b: ERROR");
    assertThat(result.silencing()).hasSize(1);
    verify(workspaces, never()).commitAndPush(any(), anyString(), anyString(), anyString(),
        anyString(), any(), anyBoolean(), any());
  }

  @Test
  void blankTitleStillMakesConventionalCommit() {
    when(engine.attempt(any(), anyString(), any()))
        .thenReturn(new AgentEdit(true, "Done.", " ", "Body"));
    when(workspaces.changedPaths(workspace, NO_HEARTBEAT)).thenReturn(List.of("backend/A.java"));
    when(workspaces.stagedDiff(workspace, NO_HEARTBEAT)).thenReturn("");

    attempt(1, false);

    verify(workspaces).commitAndPush(any(), anyString(), eq("fix: resolve SIM-107"), anyString(),
        anyString(), any(), anyBoolean(), any());
  }

  @Test
  void claimMovesToInProgressAndNamesTheBranch() {
    activities.claim("issue-1", "agent/sim-107");

    verify(linear).moveToState("issue-1", "In Progress");
    verify(linear).addComment(eq("issue-1"), contains("agent/sim-107"));
  }

  @Test
  void opensThePullRequestAndAttachesItOnce() {
    when(gitHub.openPullRequest(eq("agent/sim-107"), eq("fix: x"), anyString(), eq(7L)))
        .thenReturn(PR);
    when(linear.issuesForFingerprint(PR.url())).thenReturn(List.of());

    assertThat(activities.openPullRequest("issue-1", "agent/sim-107", "fix: x", "Body", "url"))
        .isEqualTo(PR);
    verify(linear).attachUrl("issue-1", PR.url(), "Agent pull request");
  }

  @Test
  void retriedOpenDoesNotAttachTwice() {
    when(gitHub.openPullRequest(anyString(), anyString(), anyString(), any())).thenReturn(PR);
    when(linear.issuesForFingerprint(PR.url())).thenReturn(List.of(
        new TrackedIssue("issue-1", "SIM-107", "u", IssueStateType.STARTED, Instant.EPOCH)));

    activities.openPullRequest("issue-1", "agent/sim-107", null, null, "url");

    verify(gitHub).openPullRequest(eq("agent/sim-107"), eq("fix: resolve ticket"), anyString(),
        eq(7L));
    verify(linear, never()).attachUrl(anyString(), anyString(), anyString());
  }

  @Test
  void delegatesTheGitHubReads() {
    ChecksReport report = new ChecksReport("sha1", true, true, 1, true, true);
    when(gitHub.checks("sha1", 7L)).thenReturn(report);
    when(gitHub.failureBrief(report, 271, 7L)).thenReturn("brief");
    when(gitHub.readPullRequest(271, 7L)).thenReturn(new PullRequestState(true, false, true));

    assertThat(activities.readChecks("sha1")).isEqualTo(report);
    assertThat(activities.failureBrief(report, 271)).isEqualTo("brief");
    assertThat(activities.readPullRequest(271).autoMergeArmed()).isTrue();
    activities.markSilencing(271, true);
    verify(gitHub).setLabel(271, "no-auto-merge", true, 7L);
  }

  @Test
  void backendBuildScriptGetsNoAutoMergeEvenOnRepair() {
    when(gitHub.changedFiles(271, 7L))
        .thenReturn(files("backend/build.gradle.kts", "backend/src/main/java/A.java"));

    activities.markSilencing(271, false);

    verify(gitHub).setLabel(271, "no-auto-merge", true, 7L);
    verify(gitHub).noteDependencyReview(271, 7L);
  }

  @Test
  void ordinaryNonSilencingCodeRemovesTheLabel() {
    when(gitHub.changedFiles(271, 7L)).thenReturn(files("backend/src/main/java/A.java"));

    activities.markSilencing(271, false);

    InOrder order = inOrder(gitHub);
    order.verify(gitHub).setLabel(271, "no-auto-merge", true, 7L);
    order.verify(gitHub).changedFiles(271, 7L);
    order.verify(gitHub).setLabel(271, "no-auto-merge", false, 7L);
    verify(gitHub, never()).noteDependencyReview(anyInt(), any());
  }

  @Test
  void failedPathReadLeavesTheManualMergeLabelInPlace() {
    when(gitHub.changedFiles(271, 7L)).thenThrow(new IllegalStateException("GitHub unavailable"));

    assertThatThrownBy(() -> activities.markSilencing(271, false))
        .hasMessageContaining("GitHub unavailable");

    verify(gitHub).setLabel(271, "no-auto-merge", true, 7L);
    verify(gitHub, never()).setLabel(271, "no-auto-merge", false, 7L);
  }

  private static List<DependencyPins.FileDiff> files(final String... paths) {
    return Arrays.stream(paths)
        .map(path -> new DependencyPins.FileDiff(path, null, "modified", "@@ -1 +1 @@\n-a\n+b"))
        .toList();
  }

  private static final DependencyPins.FileDiff OVERRIDE_BUMP = new DependencyPins.FileDiff(
      "build.gradle.kts", null, "modified", """
          @@ -7,1 +7,1 @@
          -    ext["tomcat.version"] = "11.0.25"\s
          +    ext["tomcat.version"] = "11.0.26"
          """);

  @Test
  void vulnerabilityRepairThatOnlyMovesVersionsIsLeftForTheReviewer() {
    when(gitHub.changedFiles(271, 7L)).thenReturn(List.of(OVERRIDE_BUMP));
    when(gitHub.headSha(271, 7L)).thenReturn("sha1");

    activities.markMergePolicy(271, false, true);

    InOrder order = inOrder(gitHub);
    order.verify(gitHub).setLabel(271, "no-auto-merge", true, 7L);
    order.verify(gitHub).setLabel(271, "no-auto-merge", false, 7L);
    verify(gitHub).noteVersionBump(271, 7L);
    verify(gitHub, never()).noteDependencyReview(anyInt(), any());
  }

  /** The diff and the files it is checked against must come from one commit. */
  @Test
  void pullRequestThatMovesDuringTheReadNeedsSomeone() {
    when(gitHub.changedFiles(271, 7L)).thenReturn(List.of(OVERRIDE_BUMP));
    when(gitHub.headSha(271, 7L)).thenReturn("sha1", "sha2");

    activities.markMergePolicy(271, false, true);

    verify(gitHub, never()).setLabel(271, "no-auto-merge", false, 7L);
    verify(gitHub).noteDependencyReview(271, 7L);
  }

  /** The same diff on any other ticket still needs a person. */
  @Test
  void versionOnlyBumpOnOrdinaryTicketStillNeedsSomeone() {
    when(gitHub.changedFiles(271, 7L)).thenReturn(List.of(OVERRIDE_BUMP));

    activities.markMergePolicy(271, false, false);

    verify(gitHub, never()).setLabel(271, "no-auto-merge", false, 7L);
    verify(gitHub).noteDependencyReview(271, 7L);
  }

  @Test
  void vulnerabilityRepairThatChangesTheBuildNeedsSomeone() {
    when(gitHub.changedFiles(271, 7L)).thenReturn(List.of(new DependencyPins.FileDiff(
        "build.gradle.kts", null, "modified", """
            @@ -7,1 +7,2 @@
                 ext["tomcat.version"] = "11.0.25"\s
            +    tasks.register("x") { }
            """)));
    when(gitHub.headSha(271, 7L)).thenReturn("sha1");

    activities.markMergePolicy(271, false, true);

    verify(gitHub, never()).setLabel(271, "no-auto-merge", false, 7L);
    verify(gitHub).noteDependencyReview(271, 7L);
  }

  private static final String INTEGRITY = "sha512-" + "abcdefghi+".repeat(8) + "abcdef==";
  private static final String LOCK_BASE = """
      {"lockfileVersion": 3, "packages": {"": {"name": "f"},
        "node_modules/postcss": {"version": "8.5.6"}}}""";
  private static final String LOCK_HEAD = """
      {"lockfileVersion": 3, "packages": {"": {"name": "f"},
        "node_modules/postcss": {"version": "8.5.31",
          "resolved": "https://registry.npmjs.org/postcss/-/postcss-8.5.31.tgz",
          "integrity": "%s"}}}""".formatted(INTEGRITY);

  @Test
  void npmRepairWhoseLockfileOnlyMovesVersionsIsLeftForTheReviewer() {
    when(gitHub.changedFiles(271, 7L)).thenReturn(files("frontend/package-lock.json"));
    when(gitHub.headSha(271, 7L)).thenReturn("sha1");
    when(gitHub.baseSha(271, 7L)).thenReturn("base1");
    when(gitHub.lockfileAt("base1", 7L)).thenReturn(LOCK_BASE);
    when(gitHub.lockfileAt("sha1", 7L)).thenReturn(LOCK_HEAD);

    activities.markMergePolicy(271, false, true);

    verify(gitHub).setLabel(271, "no-auto-merge", false, 7L);
    verify(gitHub).noteVersionBump(271, 7L);
  }

  @Test
  void npmRepairWhoseLockfileAddsPackageNeedsSomeone() {
    when(gitHub.changedFiles(271, 7L)).thenReturn(files("frontend/package-lock.json"));
    when(gitHub.headSha(271, 7L)).thenReturn("sha1");
    when(gitHub.baseSha(271, 7L)).thenReturn("base1");
    when(gitHub.lockfileAt("base1", 7L)).thenReturn(LOCK_BASE);
    String postcss = "\"node_modules/postcss\"";
    when(gitHub.lockfileAt("sha1", 7L))
        .thenReturn(LOCK_HEAD.replace(postcss, "\"node_modules/evil\": {}, " + postcss));

    activities.markMergePolicy(271, false, true);

    verify(gitHub, never()).setLabel(271, "no-auto-merge", false, 7L);
    verify(gitHub).noteDependencyReview(271, 7L);
  }

  @Test
  void unreadableLockfileNeedsSomeone() {
    when(gitHub.changedFiles(271, 7L)).thenReturn(files("frontend/package-lock.json"));
    when(gitHub.headSha(271, 7L)).thenReturn("sha1");
    when(gitHub.baseSha(271, 7L)).thenReturn("base1");

    activities.markMergePolicy(271, false, true);

    verify(gitHub, never()).setLabel(271, "no-auto-merge", false, 7L);
  }

  @Test
  void npmManifestChangeNeedsSomeone() {
    when(gitHub.changedFiles(271, 7L)).thenReturn(files("frontend/package.json"));

    activities.markMergePolicy(271, false, true);

    verify(gitHub, never()).setLabel(271, "no-auto-merge", false, 7L);
    verify(gitHub).noteDependencyReview(271, 7L);
  }

  private static LinearTicket npmTicket(final String... labels) {
    return new LinearTicket("issue-2", "SIM-120", "Bump npm package postcss to 8.5.10 (GHSA-a)",
        "Body.", "https://linear/SIM-120", IssueStateType.TRIAGE, "Triage", List.of(labels), 2,
        Instant.EPOCH);
  }

  private AttemptResult npmAttempt(final int attempt, final boolean dryRun) {
    return activities.attempt(
        new AttemptRequest(npmTicket("factory:agent", "factory:cvefix"), "agent/sim-120",
            attempt, null, dryRun),
        NO_HEARTBEAT);
  }

  private void baseLockfile() throws Exception {
    Files.createDirectories(checkout.resolve("frontend"));
    Files.writeString(checkout.resolve("frontend/package-lock.json"), LOCK_BASE);
  }

  private static NpmLockfileRepair.Outcome pushable() {
    return new NpmLockfileRepair.Outcome(true, "", LOCK_HEAD,
        List.of(new NpmLockPins.Change("postcss", "node_modules/postcss", "8.5.6", "8.5.31")),
        "https://github.com/o/r/actions/runs/9");
  }

  @Test
  void npmRepairPushesTheCheckedLockfileWithNoAgent() throws Exception {
    baseLockfile();
    when(npmRepair.run(eq(new NpmRepairTitle.Parsed("postcss", "8.5.10")), eq(LOCK_BASE), eq(7L),
        any())).thenReturn(pushable());
    when(workspaces.changedPaths(workspace, NO_HEARTBEAT))
        .thenReturn(List.of("frontend/package-lock.json"));
    when(workspaces.stagedDiff(workspace, NO_HEARTBEAT)).thenReturn("+++ b/x\n+ \"8.5.31\"\n");
    when(workspaces.commitAndPush(eq(workspace), eq("agent/sim-120"),
        eq("fix: update npm package postcss to 8.5.31"), anyString(), anyString(), eq(7L),
        eq(true), any())).thenReturn("sha3");

    AttemptResult result = npmAttempt(1, false);

    assertThat(result.status()).isEqualTo(AttemptResult.Status.PUSHED);
    assertThat(result.headSha()).isEqualTo("sha3");
    assertThat(result.prBody()).contains("| `postcss` | 8.5.6 | 8.5.31 |", "SIM-120",
        "No agent wrote this change", "actions/runs/9");
    assertThat(Files.readString(checkout.resolve("frontend/package-lock.json")))
        .isEqualTo(LOCK_HEAD);
    verify(engine, never()).attempt(any(), anyString(), any());
    verify(workspaces).create(anyString(), anyString(), any(), any(), anyString(), isNull(),
        any());
  }

  @Test
  void refusedNpmRepairPushesNothingAndSaysWhy() throws Exception {
    baseLockfile();
    when(npmRepair.run(any(), any(), any(), any())).thenReturn(
        new NpmLockfileRepair.Outcome(false, "A dependent does not allow it.", null, List.of(),
            null));

    AttemptResult result = npmAttempt(1, false);

    assertThat(result.status()).isEqualTo(AttemptResult.Status.NO_CHANGE);
    assertThat(result.summary()).isEqualTo("A dependent does not allow it.");
    verify(workspaces, never()).commitAndPush(any(), anyString(), anyString(), anyString(),
        anyString(), any(), anyBoolean(), any());
  }

  @Test
  void npmRepairThatCannotStartSaysWhichGrantIsMissing() throws Exception {
    baseLockfile();
    when(npmRepair.run(any(), any(), any(), any())).thenThrow(
        new NpmRepairUnavailableException("grant it `actions: write`"));

    AttemptResult result = npmAttempt(1, false);

    assertThat(result.status()).isEqualTo(AttemptResult.Status.NO_CHANGE);
    assertThat(result.summary()).contains("could not run", "actions: write");
  }

  @Test
  void npmRepairThatTouchedAnotherFileIsNotPushed() throws Exception {
    baseLockfile();
    when(npmRepair.run(any(), any(), any(), any())).thenReturn(pushable());
    when(workspaces.changedPaths(workspace, NO_HEARTBEAT))
        .thenReturn(List.of("frontend/package-lock.json", "frontend/package.json"));

    assertThat(npmAttempt(1, false).status()).isEqualTo(AttemptResult.Status.NO_CHANGE);
    verify(workspaces, never()).commitAndPush(any(), anyString(), anyString(), anyString(),
        anyString(), any(), anyBoolean(), any());
  }

  @Test
  void laterNpmAttemptHandsBackWithoutRegenerating() {
    AttemptResult result = npmAttempt(2, false);

    assertThat(result.status()).isEqualTo(AttemptResult.Status.NO_CHANGE);
    assertThat(result.summary()).contains("needs a person");
    verify(npmRepair, never()).run(any(), any(), any(), any());
    verify(workspaces, never()).create(anyString(), anyString(), any(), any(), anyString(), any(),
        any());
  }

  @Test
  void npmDryRunReturnsTheDiffAndPushesNothing() throws Exception {
    baseLockfile();
    when(npmRepair.run(any(), any(), any(), any())).thenReturn(pushable());
    when(workspaces.changedPaths(workspace, NO_HEARTBEAT))
        .thenReturn(List.of("frontend/package-lock.json"));
    when(workspaces.stagedDiff(workspace, NO_HEARTBEAT)).thenReturn("diff");

    AttemptResult result = npmAttempt(1, true);

    assertThat(result.status()).isEqualTo(AttemptResult.Status.DRY_RUN);
    assertThat(result.diff()).isEqualTo("diff");
    verify(workspaces, never()).commitAndPush(any(), anyString(), anyString(), anyString(),
        anyString(), any(), anyBoolean(), any());
  }

  /** Only a ticket the scan filed takes the npm path; a hand-written one goes to the agent. */
  @Test
  void onlyVulnerabilityTicketsInTheScansFormatTakeTheNpmPath() {
    assertThat(TicketAgentActivitiesImpl.npmRepair(npmTicket("factory:agent", "factory:cvefix")))
        .contains(new NpmRepairTitle.Parsed("postcss", "8.5.10"));
    assertThat(TicketAgentActivitiesImpl.npmRepair(npmTicket("factory:agent"))).isEmpty();
    assertThat(TicketAgentActivitiesImpl.npmRepair(ticket())).isEmpty();
  }

  @Test
  void silencingVulnerabilityRepairNeedsSomeone() {
    when(gitHub.changedFiles(271, 7L)).thenReturn(List.of(OVERRIDE_BUMP));

    activities.markMergePolicy(271, true, true);

    verify(gitHub, never()).setLabel(271, "no-auto-merge", false, 7L);
  }

  @Test
  void dependencyDryRunReturnsCatalogueDiffWithoutPushing() {
    agentChanges(List.of("gradle/libs.versions.toml"));
    when(workspaces.stagedDiff(workspace, NO_HEARTBEAT))
        .thenReturn("+++ b/gradle/libs.versions.toml\n+netty = \"4.2.8.Final\"\n");

    AttemptResult result = attempt(1, true);

    assertThat(result.status()).isEqualTo(AttemptResult.Status.DRY_RUN);
    assertThat(result.diff()).contains("gradle/libs.versions.toml");
    verify(workspaces, never()).commitAndPush(any(), anyString(), anyString(), anyString(),
        anyString(), any(), anyBoolean(), any());
  }

  @Test
  void movesCommentsAndCompletes() {
    activities.comment("issue-1", "Hello");
    activities.moveToReview("issue-1", "Green");
    activities.complete("issue-1", "Merged");

    InOrder order = inOrder(linear);
    order.verify(linear).addComment("issue-1", "Hello");
    order.verify(linear).addComment("issue-1", "Green");
    order.verify(linear).moveToState("issue-1", "In Review");
    order.verify(linear).addComment("issue-1", "Merged");
    order.verify(linear).updateIssue("issue-1", null, "s-done");
  }

  @Test
  void handingBackStuckParksThePullRequestAndSwapsTheLabels() {
    activities.handBack("issue-1", "Three attempts failed.", true, "PR_271");

    verify(gitHub).park("PR_271", 7L);
    verify(linear).addComment("issue-1", "Three attempts failed.");
    verify(linear).removeLabel("issue-1", "factory:agent");
    verify(linear).addLabel("issue-1", "agent:stuck");
    verify(linear).moveToState("issue-1", "Todo");
  }

  @Test
  void plainHandBackAddsNoStuckLabelAndParksNothing() {
    activities.handBack("issue-1", "No change warranted.", false, null);

    verify(gitHub, never()).park(anyString(), any());
    verify(linear, never()).addLabel(anyString(), anyString());
    verify(linear).removeLabel("issue-1", "factory:agent");
  }

  @Test
  void linearFaultsAreTranslatedForTemporal() {
    when(linear.ticket("SIM-1")).thenThrow(new LinearApiException("401", false));
    when(linear.ticket("SIM-2")).thenThrow(new LinearApiException("503", true));

    assertThatThrownBy(() -> activities.loadTicket("SIM-1"))
        .isInstanceOf(ApplicationFailure.class)
        .satisfies(e -> assertThat(((ApplicationFailure) e).isNonRetryable()).isTrue());
    assertThatThrownBy(() -> activities.loadTicket("SIM-2"))
        .isInstanceOf(LinearApiException.class);
  }
}
