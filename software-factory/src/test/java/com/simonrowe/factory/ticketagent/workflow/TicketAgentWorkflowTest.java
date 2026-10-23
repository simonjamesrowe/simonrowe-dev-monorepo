package com.simonrowe.factory.ticketagent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.LinearTicket;
import com.simonrowe.factory.ticketagent.config.TicketAgentTaskQueues;
import com.simonrowe.factory.ticketagent.domain.AttemptRequest;
import com.simonrowe.factory.ticketagent.domain.AttemptResult;
import com.simonrowe.factory.ticketagent.domain.ChecksReport;
import com.simonrowe.factory.ticketagent.domain.ChecksSignal;
import com.simonrowe.factory.ticketagent.domain.PullRequestRef;
import com.simonrowe.factory.ticketagent.domain.PullRequestState;
import com.simonrowe.factory.ticketagent.domain.TicketAgentOutcome;
import com.simonrowe.factory.ticketagent.domain.TicketAgentPhase;
import com.simonrowe.factory.ticketagent.domain.TicketAgentRequest;
import com.simonrowe.factory.ticketagent.domain.TicketAgentResult;
import com.simonrowe.factory.ticketagent.domain.TicketAgentSettings;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The run's decisions, against Temporal's time-skipping test server.
 *
 * <p>Every wait here is hours or days long in production, and skipped to nothing here, so these
 * cases also prove the fallback reads: a webhook that never arrives costs a delay, not a stuck run.
 */
class TicketAgentWorkflowTest {

  private static final String ISSUE = "issue-1";
  private static final PullRequestRef PR =
      new PullRequestRef(271, "https://github.com/o/r/pull/271", "PR_node");

  private static LinearTicket ticket(final String... labels) {
    return new LinearTicket(
        ISSUE, "SIM-107", "ERROR in backend", "Seen 41 times.",
        "https://linear.app/x/issue/SIM-107", IssueStateType.TRIAGE, "Triage",
        List.of(labels), 2, Instant.EPOCH);
  }

  private static AttemptResult pushed(final String sha) {
    return new AttemptResult(
        AttemptResult.Status.PUSHED, sha, "Null-checked the exporter.", "fix: guard exporter",
        "Body", List.of("backend/A.java"), List.of(), null);
  }

  private static ChecksReport green(final String sha) {
    return new ChecksReport(sha, true, true, 1, true, true);
  }

  private static ChecksReport red(final String sha) {
    return new ChecksReport(sha, true, false, 1, false, false);
  }

  @Test
  void greenFirstTimeAndAutoMergedEndsDone() {
    Fake fake = new Fake(ticket("factory:agent"));
    fake.attempts.add(pushed("sha1"));
    fake.checks.put("sha1", green("sha1"));
    fake.pullRequestStates.add(new PullRequestState(true, false, true));
    fake.pullRequestStates.add(new PullRequestState(false, true, true));

    TicketAgentResult result = run(fake, false);

    assertThat(result.outcome()).isEqualTo(TicketAgentOutcome.MERGED);
    assertThat(result.attempts()).isEqualTo(1);
    assertThat(fake.calls)
        .containsSubsequence("claim", "attempt:1", "openPullRequest", "markMergePolicy:false:false",
            "readChecks:sha1", "moveToReview", "complete");
    assertThat(fake.calls).noneMatch(call -> call.startsWith("handBack"));
  }

  @Test
  void vulnerabilityRepairTicketTellsTheMergePolicy() {
    Fake fake = new Fake(ticket("factory:agent", "factory:cvefix"));
    fake.attempts.add(pushed("sha1"));
    fake.checks.put("sha1", green("sha1"));
    fake.pullRequestStates.add(new PullRequestState(true, false, true));
    fake.pullRequestStates.add(new PullRequestState(false, true, true));

    TicketAgentResult result = run(fake, false);

    assertThat(result.outcome()).isEqualTo(TicketAgentOutcome.MERGED);
    assertThat(fake.calls).contains("markMergePolicy:false:true");
  }

  @Test
  void repairsWithTheFailureUntilCiGoesGreen() {
    Fake fake = new Fake(ticket("factory:agent"));
    fake.attempts.addAll(List.of(pushed("sha1"), pushed("sha2"), pushed("sha3")));
    fake.checks.put("sha1", red("sha1"));
    fake.checks.put("sha2", red("sha2"));
    fake.checks.put("sha3", green("sha3"));
    fake.pullRequestStates.add(new PullRequestState(false, true, true));

    TicketAgentResult result = run(fake, false);

    assertThat(result.outcome()).isEqualTo(TicketAgentOutcome.MERGED);
    assertThat(result.attempts()).isEqualTo(3);
    assertThat(fake.attemptRequests).extracting(AttemptRequest::failure)
        .containsExactly(null, "brief for sha1", "brief for sha2");
    // One pull request for the whole run, whatever the attempt count.
    assertThat(fake.calls.stream().filter("openPullRequest"::equals)).hasSize(1);
  }

  @Test
  void threeRedAttemptsHandTheTicketBackStuckAndStopThere() {
    Fake fake = new Fake(ticket("factory:agent"));
    fake.attempts.addAll(List.of(pushed("sha1"), pushed("sha2"), pushed("sha3"), pushed("sha4")));
    fake.checks.put("sha1", red("sha1"));
    fake.checks.put("sha2", red("sha2"));
    fake.checks.put("sha3", red("sha3"));

    TicketAgentResult result = run(fake, false);

    assertThat(result.outcome()).isEqualTo(TicketAgentOutcome.HANDED_BACK);
    assertThat(result.attempts()).isEqualTo(3);
    assertThat(fake.attemptRequests).hasSize(3);
    assertThat(fake.calls).contains("handBack:stuck:PR_node");
  }

  @Test
  void noChangeWarrantedHandsBackWithoutTheStuckLabel() {
    Fake fake = new Fake(ticket("factory:agent"));
    fake.attempts.add(new AttemptResult(
        AttemptResult.Status.NO_CHANGE, null, "Third-party Temporal noise.", "", "", List.of(),
        List.of(), null));

    TicketAgentResult result = run(fake, false);

    assertThat(result.outcome()).isEqualTo(TicketAgentOutcome.HANDED_BACK);
    assertThat(result.detail()).contains("Third-party Temporal noise.");
    assertThat(fake.calls).contains("handBack:not-stuck:none").doesNotContain("openPullRequest");
  }

  @Test
  void deniedPathIsHandedBackStuckWithNothingPushed() {
    Fake fake = new Fake(ticket("factory:agent"));
    fake.attempts.add(new AttemptResult(
        AttemptResult.Status.DENIED, null, "It changed scripts/x.sh.", "", "", List.of(),
        List.of(), null));

    TicketAgentResult result = run(fake, false);

    assertThat(result.outcome()).isEqualTo(TicketAgentOutcome.HANDED_BACK);
    assertThat(fake.calls).contains("handBack:stuck:none").doesNotContain("openPullRequest");
  }

  @Test
  void silencingDiffIsLabelledForPerson() {
    Fake fake = new Fake(ticket("factory:agent"));
    fake.attempts.add(new AttemptResult(
        AttemptResult.Status.PUSHED, "sha1", "Muted it.", "fix: mute", "Body",
        List.of("software-factory/src/main/resources/application.yml"),
        List.of("adds a log-watch mute rule"), null));
    fake.checks.put("sha1", green("sha1"));
    fake.pullRequestStates.add(new PullRequestState(true, false, false));
    fake.pullRequestStates.add(new PullRequestState(true, false, false));

    TicketAgentResult result = run(fake, false);

    assertThat(fake.calls).contains("markMergePolicy:true:false");
    // Not armed, and still unmerged when the merge wait ran out: left in review for a person.
    assertThat(result.outcome()).isEqualTo(TicketAgentOutcome.IN_REVIEW);
  }

  @Test
  void dryRunPostsTheDiffAndChangesNothing() {
    Fake fake = new Fake(ticket());
    fake.attempts.add(new AttemptResult(
        AttemptResult.Status.DRY_RUN, null, "Would null-check.", "fix: x", "Body",
        List.of("backend/A.java"), List.of(), "+  if (x == null) return;"));

    TicketAgentResult result = run(fake, true);

    assertThat(result.outcome()).isEqualTo(TicketAgentOutcome.DRY_RUN);
    assertThat(fake.calls).containsExactly("settings", "loadTicket", "attempt:1", "comment");
    assertThat(fake.comments.get(0)).contains("```diff").contains("if (x == null) return;");
  }

  @Test
  void dryRunThatWouldHandBackOnlyComments() {
    Fake fake = new Fake(ticket());
    fake.attempts.add(new AttemptResult(
        AttemptResult.Status.DENIED, null, "It changed scripts/x.sh.", "", "", List.of(),
        List.of(), null));

    TicketAgentResult result = run(fake, true);

    assertThat(result.outcome()).isEqualTo(TicketAgentOutcome.DRY_RUN);
    assertThat(fake.calls).noneMatch(call -> call.startsWith("handBack") || call.equals("claim"));
  }

  @Test
  void anUnlabelledTicketIsNotWorkedOn() {
    Fake fake = new Fake(ticket());

    TicketAgentResult result = run(fake, false);

    assertThat(result.outcome()).isEqualTo(TicketAgentOutcome.NOT_ELIGIBLE);
    assertThat(fake.calls).containsExactly("settings", "loadTicket");
  }

  @Test
  void crashedAttemptHandsBackAndFailsTheRun() {
    Fake fake = new Fake(ticket("factory:agent"));
    fake.attemptFailure = new IllegalStateException("Claude exited with 1: rate limited");

    assertThatThrownBy(() -> run(fake, false)).isInstanceOf(WorkflowFailedException.class);
    assertThat(fake.calls).contains("handBack:stuck:none");
  }

  @Test
  void ciThatNeverReportsHandsBack() {
    Fake fake = new Fake(ticket("factory:agent"));
    fake.attempts.add(pushed("sha1"));
    fake.checks.put("sha1", new ChecksReport("sha1", false, false, 0, false, false));

    TicketAgentResult result = run(fake, false);

    assertThat(result.outcome()).isEqualTo(TicketAgentOutcome.HANDED_BACK);
    assertThat(result.detail()).contains("CI did not finish");
  }

  /** The webhook path: a signal wakes the wait, and one for an older commit does not. */
  @Test
  void theCiSignalWakesTheRunAndStaleOneIsIgnored() throws Exception {
    Fake fake = new Fake(ticket("factory:agent"));
    fake.attempts.add(pushed("sha1"));
    fake.checks.put("sha1", green("sha1"));
    fake.pullRequestStates.add(new PullRequestState(true, false, true));

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      TicketAgentWorkflow workflow = start(environment, fake, false);
      awaitPhase(workflow, TicketAgentPhase.WAITING_FOR_CHECKS);
      int readsBefore = fake.readChecksCount();

      workflow.checksCompleted(new ChecksSignal("stale-sha", "success", 1));
      Thread.sleep(300);
      assertThat(fake.readChecksCount()).isEqualTo(readsBefore);

      workflow.checksCompleted(new ChecksSignal("sha1", "success", 1));
      awaitPhase(workflow, TicketAgentPhase.AWAITING_MERGE);
      workflow.pullRequestClosed(true);

      TicketAgentResult result =
          WorkflowStub.fromTyped(workflow).getResult(TicketAgentResult.class);
      assertThat(result.outcome()).isEqualTo(TicketAgentOutcome.MERGED);
    }
  }

  @Test
  void stopFromTheConsoleHandsBackWithoutTheStuckLabel() throws Exception {
    Fake fake = new Fake(ticket("factory:agent"));
    fake.attempts.add(pushed("sha1"));
    fake.checks.put("sha1", red("sha1"));

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      TicketAgentWorkflow workflow = start(environment, fake, false);
      awaitPhase(workflow, TicketAgentPhase.WAITING_FOR_CHECKS);
      workflow.stop("Stopped from the console");

      TicketAgentResult result =
          WorkflowStub.fromTyped(workflow).getResult(TicketAgentResult.class);
      assertThat(result.outcome()).isEqualTo(TicketAgentOutcome.HANDED_BACK);
      assertThat(fake.calls).contains("handBack:not-stuck:PR_node");
      assertThat(fake.attemptRequests).hasSize(1);
    }
  }

  @Test
  void closedWithoutMergingHandsBack() throws Exception {
    Fake fake = new Fake(ticket("factory:agent"));
    fake.attempts.add(pushed("sha1"));
    fake.checks.put("sha1", green("sha1"));
    fake.pullRequestStates.add(new PullRequestState(true, false, false));
    fake.pullRequestStates.add(new PullRequestState(false, false, false));

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      TicketAgentWorkflow workflow = start(environment, fake, false);
      awaitPhase(workflow, TicketAgentPhase.WAITING_FOR_CHECKS);
      workflow.checksCompleted(new ChecksSignal("sha1", "success", 1));
      awaitPhase(workflow, TicketAgentPhase.AWAITING_MERGE);
      workflow.pullRequestClosed(false);

      TicketAgentResult result =
          WorkflowStub.fromTyped(workflow).getResult(TicketAgentResult.class);
      assertThat(result.outcome()).isEqualTo(TicketAgentOutcome.HANDED_BACK);
      assertThat(result.detail()).contains("closed without merging");
    }
  }

  private static TicketAgentResult run(final Fake fake, final boolean dryRun) {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      return WorkflowStub.fromTyped(start(environment, fake, dryRun))
          .getResult(TicketAgentResult.class);
    }
  }

  private static TicketAgentWorkflow start(
      final TestWorkflowEnvironment environment, final Fake fake, final boolean dryRun) {
    Worker worker = environment.newWorker(TicketAgentTaskQueues.TICKET_AGENT);
    worker.registerWorkflowImplementationTypes(TicketAgentWorkflowImpl.class);
    worker.registerActivitiesImplementations(fake);
    environment.start();
    TicketAgentWorkflow workflow =
        environment
            .getWorkflowClient()
            .newWorkflowStub(
                TicketAgentWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(TicketAgentTaskQueues.TICKET_AGENT)
                    .setWorkflowId("ticket-agent-SIM-107")
                    .build());
    WorkflowClient.start(
        workflow::run,
        new TicketAgentRequest("SIM-107", dryRun, TicketAgentRequest.TRIGGER_CONSOLE));
    return workflow;
  }

  private static void awaitPhase(final TicketAgentWorkflow workflow, final TicketAgentPhase phase)
      throws InterruptedException {
    for (int tries = 0; tries < 200; tries++) {
      if (workflow.progress().phase() == phase) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("never reached " + phase + "; at " + workflow.progress());
  }

  /** Scripted activities, recording every call in order. */
  static class Fake implements TicketAgentActivities {
    private final LinearTicket ticket;
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final List<AttemptResult> attempts = new ArrayList<>();
    final List<AttemptRequest> attemptRequests = new ArrayList<>();
    final Map<String, ChecksReport> checks = new HashMap<>();
    final List<PullRequestState> pullRequestStates = new ArrayList<>();
    final List<String> comments = new ArrayList<>();
    RuntimeException attemptFailure;
    private int stateReads;

    Fake(final LinearTicket ticket) {
      this.ticket = ticket;
    }

    int readChecksCount() {
      return (int) calls.stream().filter(call -> call.startsWith("readChecks")).count();
    }

    @Override
    public TicketAgentSettings settings() {
      calls.add("settings");
      return new TicketAgentSettings(
          "factory:agent", "agent/", 3, Duration.ofHours(2), Duration.ofMinutes(40),
          Duration.ofDays(14));
    }

    @Override
    public LinearTicket loadTicket(final String identifier) {
      calls.add("loadTicket");
      return ticket;
    }

    @Override
    public void claim(final String issueId, final String branch) {
      calls.add("claim");
      assertThat(branch).isEqualTo("agent/sim-107");
    }

    @Override
    public AttemptResult attempt(final AttemptRequest request) {
      calls.add("attempt:" + request.attempt());
      attemptRequests.add(request);
      if (attemptFailure != null) {
        throw attemptFailure;
      }
      return attempts.isEmpty() ? pushed("unscripted-" + request.attempt()) : attempts.remove(0);
    }

    @Override
    public PullRequestRef openPullRequest(
        final String issueId, final String branch, final String title, final String body,
        final String ticketUrl) {
      calls.add("openPullRequest");
      return PR;
    }

    @Override
    public void markSilencing(final int pullNumber, final boolean silencing) {
      calls.add("markSilencing:" + silencing);
    }

    @Override
    public void markMergePolicy(
        final int pullNumber, final boolean silencing, final boolean vulnerabilityRepair) {
      calls.add("markMergePolicy:" + silencing + ":" + vulnerabilityRepair);
    }

    @Override
    public ChecksReport readChecks(final String headSha) {
      calls.add("readChecks:" + headSha);
      // An unscripted commit reads as red rather than null, so a run that tries one attempt
      // too many fails its assertions instead of hanging on a workflow task retried for ever.
      return checks.getOrDefault(headSha, red(headSha));
    }

    @Override
    public String failureBrief(final ChecksReport report, final int pullNumber) {
      calls.add("failureBrief");
      return "brief for " + report.headSha();
    }

    @Override
    public PullRequestState readPullRequest(final int pullNumber) {
      calls.add("readPullRequest");
      PullRequestState state =
          pullRequestStates.get(Math.min(stateReads, pullRequestStates.size() - 1));
      stateReads++;
      return state;
    }

    @Override
    public void comment(final String issueId, final String body) {
      calls.add("comment");
      comments.add(body);
    }

    @Override
    public void moveToReview(final String issueId, final String body) {
      calls.add("moveToReview");
    }

    @Override
    public void complete(final String issueId, final String body) {
      calls.add("complete");
    }

    @Override
    public void handBack(
        final String issueId, final String body, final boolean stuck,
        final String pullRequestNodeId) {
      String node = pullRequestNodeId == null ? "none" : pullRequestNodeId;
      calls.add("handBack:%s:%s".formatted(stuck ? "stuck" : "not-stuck", node));
    }

    @Override
    public int dispatch() {
      throw new UnsupportedOperationException();
    }
  }
}
