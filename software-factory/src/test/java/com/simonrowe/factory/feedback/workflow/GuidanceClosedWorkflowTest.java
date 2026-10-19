package com.simonrowe.factory.feedback.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.feedback.config.FeedbackTaskQueues;
import com.simonrowe.factory.feedback.domain.DistillationOutcome;
import com.simonrowe.factory.feedback.domain.DistillationStatus;
import com.simonrowe.factory.feedback.domain.FeedbackRequest;
import com.simonrowe.factory.feedback.domain.GuidanceClosedRequest;
import com.simonrowe.factory.feedback.domain.GuidanceClosedResult;
import com.simonrowe.factory.feedback.domain.Lesson;
import com.simonrowe.factory.feedback.domain.ReviewConversation;
import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.linear.domain.AbsenceSweep;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.LinkedIssue;
import com.simonrowe.factory.linear.domain.SweepReport;
import com.simonrowe.factory.linear.workflow.LinearActivities;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GuidanceClosedWorkflowTest {

  private static final String SETUP_PR = "https://github.com/simonjamesrowe/agent-setup/pull/50";
  private static final String REPO_PR =
      "https://github.com/simonjamesrowe/simonrowe-dev-monorepo/pull/265";
  private static final String FINGERPRINT = "https://factory.simonrowe.dev/fingerprint/abc";

  private static LinkedIssue ticket(final IssueStateType state, final String... attachments) {
    return new LinkedIssue("issue-1", "SIM-106", "https://linear/SIM-106", state,
        List.of(attachments));
  }

  @Test
  void closesTheTicketWhenItsOnlyGuidancePullRequestMerges() {
    FakeLinear linear = new FakeLinear(ticket(IssueStateType.TRIAGE, SETUP_PR, FINGERPRINT));

    GuidanceClosedResult result = run(linear, new FakeFeedback(Set.of()), merged(SETUP_PR));

    assertThat(result.completed()).containsExactly("SIM-106");
    assertThat(linear.completed).containsExactly("issue-1");
    assertThat(linear.comments).isEmpty();
  }

  /** A ticket can produce two guidance pull requests; it is done only when both have merged. */
  @Test
  void waitsForTheOtherGuidancePullRequest() {
    FakeLinear linear =
        new FakeLinear(ticket(IssueStateType.TRIAGE, SETUP_PR, REPO_PR, FINGERPRINT));
    FakeFeedback feedback = new FakeFeedback(Set.of(REPO_PR));

    GuidanceClosedResult result = run(linear, feedback, merged(SETUP_PR));

    assertThat(result.waiting()).containsExactly("SIM-106");
    assertThat(linear.completed).isEmpty();
    assertThat(linear.comments).singleElement().asString().contains("Still waiting on " + REPO_PR);
    // Only the other pull request is asked about, never the one that just merged, and never the
    // fingerprint, which is not a pull request.
    assertThat(feedback.asked).containsExactly(List.of(REPO_PR));
  }

  @Test
  void closesWhenTheOtherGuidancePullRequestHadAlreadyMerged() {
    FakeLinear linear = new FakeLinear(ticket(IssueStateType.TRIAGE, SETUP_PR, REPO_PR));

    GuidanceClosedResult result = run(linear, new FakeFeedback(Set.of()), merged(REPO_PR));

    assertThat(result.completed()).containsExactly("SIM-106");
  }

  @Test
  void guidanceClosedWithoutMergingLeavesTheTicketOpen() {
    FakeLinear linear = new FakeLinear(ticket(IssueStateType.TRIAGE, SETUP_PR));

    GuidanceClosedResult result =
        run(linear, new FakeFeedback(Set.of()),
            new GuidanceClosedRequest("simonjamesrowe", "agent-setup", 50, SETUP_PR, false));

    assertThat(result.waiting()).containsExactly("SIM-106");
    assertThat(linear.completed).isEmpty();
    assertThat(linear.comments).singleElement().asString().contains("closed without merging");
  }

  @Test
  void ticketSomeoneAlreadyClosedIsLeftAlone() {
    FakeLinear linear = new FakeLinear(ticket(IssueStateType.COMPLETED, SETUP_PR));

    GuidanceClosedResult result = run(linear, new FakeFeedback(Set.of()), merged(SETUP_PR));

    assertThat(result.completed()).isEmpty();
    assertThat(result.waiting()).isEmpty();
    assertThat(linear.completed).isEmpty();
    assertThat(linear.comments).isEmpty();
  }

  @Test
  void otherPullRequestsIgnoresNonPullRequestAttachmentsAndDuplicates() {
    LinkedIssue issue =
        ticket(IssueStateType.TRIAGE, SETUP_PR, FINGERPRINT, REPO_PR, REPO_PR + "?x=1");

    assertThat(GuidanceClosedWorkflowImpl.otherPullRequests(issue, SETUP_PR))
        .containsExactly(REPO_PR);
  }

  @Test
  void memoSaysWhatHappenedForTheConsole() {
    assertThat(GuidanceClosedWorkflowImpl.memo(merged(SETUP_PR), List.of("SIM-106"), List.of()))
        .containsEntry("title", "Guidance simonjamesrowe/agent-setup#50")
        .containsEntry("detail", "Merged; SIM-106 done")
        .containsEntry("url", SETUP_PR);
    assertThat(GuidanceClosedWorkflowImpl.memo(merged(SETUP_PR), List.of(), List.of("SIM-106")))
        .containsEntry("detail", "Merged; SIM-106 still open");
    assertThat(
            GuidanceClosedWorkflowImpl.memo(
                new GuidanceClosedRequest("o", "r", 1, SETUP_PR, false), List.of(), List.of()))
        .containsEntry("detail", "Closed unmerged; no open ticket");
  }

  private static GuidanceClosedRequest merged(final String url) {
    return new GuidanceClosedRequest("simonjamesrowe", "agent-setup", 50, url, true);
  }

  private static GuidanceClosedResult run(
      final FakeLinear linear, final FakeFeedback feedback, final GuidanceClosedRequest request) {
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker worker = environment.newWorker(FeedbackTaskQueues.REVIEW_FEEDBACK);
      worker.registerWorkflowImplementationTypes(GuidanceClosedWorkflowImpl.class);
      worker.registerActivitiesImplementations(feedback);
      environment.newWorker(LinearTaskQueues.LINEAR).registerActivitiesImplementations(linear);
      environment.start();
      return environment
          .getWorkflowClient()
          .newWorkflowStub(
              GuidanceClosedWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(FeedbackTaskQueues.REVIEW_FEEDBACK)
                  .setWorkflowId("guidance-closed-test")
                  .build())
          .close(request);
    }
  }

  private static final class FakeFeedback implements FeedbackActivities {
    private final Set<String> unmerged;
    final List<List<String>> asked = new ArrayList<>();

    FakeFeedback(final Set<String> unmerged) {
      this.unmerged = unmerged;
    }

    @Override
    public List<String> unmergedPullRequests(final List<String> pullRequestUrls) {
      asked.add(List.copyOf(pullRequestUrls));
      return pullRequestUrls.stream().filter(unmerged::contains).toList();
    }

    @Override
    public ReviewConversation fetchConversation(final FeedbackRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Lesson> harvestLessons(
        final FeedbackRequest request, final ReviewConversation conversation) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void recordLearnings(
        final FeedbackRequest request, final ReviewConversation conversation,
        final List<Lesson> lessons, final String workflowId,
        final DistillationStatus initialStatus) {
      throw new UnsupportedOperationException();
    }

    @Override
    public DistillationOutcome distillAndPropose(
        final FeedbackRequest request, final List<Lesson> lessons, final String linearIssueUrl) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void recordLinearIssue(
        final FeedbackRequest request, final String issueIdentifier, final String issueUrl) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void recordDistillation(
        final FeedbackRequest request, final DistillationOutcome outcome) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class FakeLinear implements LinearActivities {
    private final List<LinkedIssue> issues;
    final List<String> comments = new ArrayList<>();
    final List<String> completed = new ArrayList<>();

    FakeLinear(final LinkedIssue... issues) {
      this.issues = List.of(issues);
    }

    @Override
    public List<LinkedIssue> issuesLinkedTo(final String url) {
      return issues;
    }

    @Override
    public void commentOn(final String issueId, final String body) {
      comments.add(body);
    }

    @Override
    public boolean complete(final String issueId, final String body) {
      completed.add(issueId);
      return true;
    }

    @Override
    public FiledIssue fileIssue(final IssueFiling filing) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SweepReport sweepResolved(final AbsenceSweep sweep) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void attachUrl(final String issueId, final String url, final String title) {
      throw new UnsupportedOperationException();
    }
  }
}
