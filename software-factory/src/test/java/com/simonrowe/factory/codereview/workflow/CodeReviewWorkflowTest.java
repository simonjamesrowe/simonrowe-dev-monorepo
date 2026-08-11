package com.simonrowe.factory.codereview.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.factory.codereview.config.CodeReviewTaskQueues;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFailure;
import com.simonrowe.factory.codereview.domain.ReviewFinding;
import com.simonrowe.factory.codereview.domain.ReviewPhase;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import com.simonrowe.factory.codereview.domain.ReviewResult;
import com.simonrowe.factory.codereview.domain.Severity;
import com.simonrowe.factory.codereview.domain.Verdict;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class CodeReviewWorkflowTest {

  private static final ReviewReport REPORT =
      new ReviewReport(
          "One concrete problem.",
          Verdict.COMMENT,
          List.of(
              new ReviewFinding(
                  Severity.WARNING,
                  "src/App.java",
                  12,
                  "Null result is dereferenced",
                  "The new null branch reaches this dereference.",
                  "Return before dereferencing.")));

  private static ReviewRequest request(final boolean publish) {
    return new ReviewRequest("owner", "repo", 7, "head-sha", 123L, publish);
  }

  private static CodeReviewWorkflow start(
      final TestWorkflowEnvironment environment, final Object activities, final String id) {
    Worker worker = environment.newWorker(CodeReviewTaskQueues.REVIEWS);
    worker.registerWorkflowImplementationTypes(CodeReviewWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities);
    environment.start();
    return environment
        .getWorkflowClient()
        .newWorkflowStub(
            CodeReviewWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(CodeReviewTaskQueues.REVIEWS)
                .setWorkflowId(id)
                .build());
  }

  @Test
  void acknowledgesBeforeLoadingThePullRequestThenDeletesTheAckOnSuccess() {
    RecordingActivities activities = new RecordingActivities();

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-ack-success");

      ReviewResult result = workflow.review(request(true));

      assertThat(activities.calls)
          .containsExactly(
              "publishAck", "loadPullRequest", "runReview", "publishReview", "resolveAck");
      assertThat(activities.resolvedAckId).isEqualTo("ack-1");
      assertThat(result.report()).isEqualTo(REPORT);
      assertThat(workflow.progress().phase()).isEqualTo(ReviewPhase.COMPLETED);
    }
  }

  @Test
  void editsTheAckIntoTheFailureNoticeNamingThePhaseThatDied() {
    RecordingActivities activities = new RecordingActivities();
    activities.failReviewWith =
        ApplicationFailure.newNonRetryableFailure(
            "Claude exited with 1: subtype=error_max_turns", "AGENT_FAILED");

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-ack-failure");

      assertThatThrownBy(() -> workflow.review(request(true)))
          .isInstanceOf(WorkflowFailedException.class);

      assertThat(activities.calls).contains("publishAck", "publishFailure");
      assertThat(activities.calls).doesNotContain("resolveAck", "publishReview");
      assertThat(activities.failureAckId).isEqualTo("ack-1");
      assertThat(activities.failure.phase()).isEqualTo(ReviewPhase.REVIEWING);
      assertThat(activities.failure.reason()).contains("error_max_turns");
      assertThat(activities.failure.workflowId()).isEqualTo("review-ack-failure");
    }
  }

  /**
   * The regression test for the 2026-08-11 outage: a 422 minting the installation token is thrown
   * inside loadPullRequest, and the old guard on a non-null PullRequestContext meant the commonest
   * failure posted nothing at all.
   */
  @Test
  void reportsTheFailureThatHappenedWhileLoadingThePullRequest() {
    RecordingActivities activities = new RecordingActivities();
    activities.failLoadWith =
        ApplicationFailure.newNonRetryableFailure(
            "GitHub App token endpoint returned 422", "GITHUB_TOKEN_REJECTED");

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-load-failure");

      assertThatThrownBy(() -> workflow.review(request(true)))
          .isInstanceOf(WorkflowFailedException.class);

      assertThat(activities.calls).contains("publishFailure");
      assertThat(activities.failure.phase()).isEqualTo(ReviewPhase.LOADING_PULL_REQUEST);
      assertThat(activities.failure.reason()).contains("422");
    }
  }

  @Test
  void postsFreshFailureCommentWhenTheAckNeverLanded() {
    RecordingActivities activities = new RecordingActivities();
    activities.failAck = true;
    activities.failReviewWith =
        ApplicationFailure.newNonRetryableFailure("Claude exited with 1", "AGENT_FAILED");

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-no-ack");

      assertThatThrownBy(() -> workflow.review(request(true)))
          .isInstanceOf(WorkflowFailedException.class);

      assertThat(activities.calls).contains("publishFailure");
      assertThat(activities.failureAckId).isNull();
    }
  }

  @Test
  void anAckThatCannotBePostedDoesNotFailTheReview() {
    RecordingActivities activities = new RecordingActivities();
    activities.failAck = true;

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-ack-broken");

      ReviewResult result = workflow.review(request(true));

      assertThat(result.report()).isEqualTo(REPORT);
      assertThat(workflow.progress().phase()).isEqualTo(ReviewPhase.COMPLETED);
    }
  }

  @Test
  void anAckThatCannotBeDeletedDoesNotFailTheReview() {
    RecordingActivities activities = new RecordingActivities();
    activities.failResolve = true;

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-resolve-broken");

      ReviewResult result = workflow.review(request(true));

      assertThat(result.report()).isEqualTo(REPORT);
      assertThat(workflow.progress().phase()).isEqualTo(ReviewPhase.COMPLETED);
    }
  }

  @Test
  void dryRunTouchesThePullRequestNotAtAll() {
    RecordingActivities activities = new RecordingActivities();

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-dry-run");

      ReviewResult result = workflow.review(request(false));

      assertThat(activities.calls).containsExactly("loadPullRequest", "runReview");
      assertThat(result.published()).isFalse();
    }
  }

  @Test
  void dryRunStaysSilentEvenWhenItFails() {
    RecordingActivities activities = new RecordingActivities();
    activities.failReviewWith =
        ApplicationFailure.newNonRetryableFailure("Claude exited with 1", "AGENT_FAILED");

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-dry-run-failure");

      assertThatThrownBy(() -> workflow.review(request(false)))
          .isInstanceOf(WorkflowFailedException.class);

      assertThat(activities.calls).doesNotContain("publishAck", "publishFailure");
    }
  }

  /** One fake for every case, so each test states only the behaviour it is about. */
  private static final class RecordingActivities implements ReviewActivities {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private boolean failAck;
    private boolean failResolve;
    private RuntimeException failLoadWith;
    private RuntimeException failReviewWith;
    private String resolvedAckId;
    private String failureAckId;
    private ReviewFailure failure;

    @Override
    public String publishAck(final ReviewRequest request) {
      calls.add("publishAck");
      if (failAck) {
        throw ApplicationFailure.newNonRetryableFailure("ack failed", "ACK_FAILED");
      }
      return "ack-1";
    }

    @Override
    public PullRequestContext loadPullRequest(final ReviewRequest request) {
      calls.add("loadPullRequest");
      if (failLoadWith != null) {
        throw failLoadWith;
      }
      return new PullRequestContext(
          request.owner(),
          request.repository(),
          request.pullNumber(),
          "Title",
          "Body",
          "https://github.com/owner/repo.git",
          "base-sha",
          "head-sha",
          request.installationId());
    }

    @Override
    public ReviewReport runReview(final PullRequestContext pullRequest) {
      calls.add("runReview");
      if (failReviewWith != null) {
        throw failReviewWith;
      }
      return REPORT;
    }

    @Override
    public void publishReview(
        final PullRequestContext pullRequest, final ReviewReport reviewReport) {
      calls.add("publishReview");
    }

    @Override
    public void resolveAck(final ReviewRequest request, final String ackCommentId) {
      calls.add("resolveAck");
      resolvedAckId = ackCommentId;
      if (failResolve) {
        throw ApplicationFailure.newNonRetryableFailure("delete failed", "DELETE_FAILED");
      }
    }

    @Override
    public void publishFailure(
        final ReviewRequest request, final String ackCommentId, final ReviewFailure reported) {
      calls.add("publishFailure");
      failureAckId = ackCommentId;
      failure = reported;
    }
  }
}
