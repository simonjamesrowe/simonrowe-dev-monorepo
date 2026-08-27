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

  /**
   * The status comment is opened before anything that can fail and then written through — there is
   * no separate delete step to strand an "in progress" notice beside a finished review.
   */
  @Test
  void opensTheStatusCommentBeforeLoadingThenPublishesThroughTheSameComment() {
    RecordingActivities activities = new RecordingActivities();

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-status-success");

      ReviewResult result = workflow.review(request(true));

      assertThat(activities.calls)
          .containsExactly(
              "openStatusComment",
              "loadPullRequest",
              // After loading, never before: a check run must name a commit, and the head SHA is
              // only certain here. openStatusComment holds only a ReviewRequest, whose
              // expectedHeadSha is nullable on the manual-review path.
              "openCheckRun",
              "runReview",
              "publishReview",
              "completeCheckRun");
      assertThat(activities.publishedStatusCommentId).isEqualTo("status-1");
      assertThat(result.report()).isEqualTo(REPORT);
      assertThat(workflow.progress().phase()).isEqualTo(ReviewPhase.COMPLETED);
    }
  }

  @Test
  void editsTheStatusCommentIntoTheFailureNoticeNamingThePhaseThatDied() {
    RecordingActivities activities = new RecordingActivities();
    activities.failReviewWith =
        ApplicationFailure.newNonRetryableFailure(
            "Claude exited with 1: subtype=error_max_turns", "AGENT_FAILED");

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-status-failure");

      assertThatThrownBy(() -> workflow.review(request(true)))
          .isInstanceOf(WorkflowFailedException.class);

      assertThat(activities.calls).contains("openStatusComment", "publishFailure");
      assertThat(activities.calls).doesNotContain("publishReview");
      assertThat(activities.failureStatusCommentId).isEqualTo("status-1");
      assertThat(activities.failure.phase()).isEqualTo(ReviewPhase.REVIEWING);
      assertThat(activities.failure.reason()).contains("error_max_turns");
      assertThat(activities.failure.workflowId()).isEqualTo("review-status-failure");
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
  void postsFreshFailureCommentWhenTheStatusCommentNeverOpened() {
    RecordingActivities activities = new RecordingActivities();
    activities.failOpen = true;
    activities.failReviewWith =
        ApplicationFailure.newNonRetryableFailure("Claude exited with 1", "AGENT_FAILED");

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-no-status");

      assertThatThrownBy(() -> workflow.review(request(true)))
          .isInstanceOf(WorkflowFailedException.class);

      assertThat(activities.calls).contains("publishFailure");
      assertThat(activities.failureStatusCommentId).isNull();
    }
  }

  @Test
  void statusCommentThatCannotBeOpenedDoesNotFailTheReview() {
    RecordingActivities activities = new RecordingActivities();
    activities.failOpen = true;

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-status-broken");

      ReviewResult result = workflow.review(request(true));

      assertThat(result.report()).isEqualTo(REPORT);
      assertThat(activities.publishedStatusCommentId).isNull();
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
      assertThat(activities.calls).doesNotContain("openCheckRun", "completeCheckRun");
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

      assertThat(activities.calls).doesNotContain("openStatusComment", "publishFailure");
    }
  }

  // --- the Code Review check run --------------------------------------------------------------

  @Test
  void failedReviewTurnsTheCheckRunRed() {
    RecordingActivities activities = new RecordingActivities();
    activities.failReviewWith =
        ApplicationFailure.newNonRetryableFailure("Claude exited with 1", "AGENT_FAILED");

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-check-failure");

      assertThatThrownBy(() -> workflow.review(request(true)))
          .isInstanceOf(WorkflowFailedException.class);

      assertThat(activities.calls).contains("openCheckRun", "failCheckRun");
      assertThat(activities.failedCheckRunId).isEqualTo("check-1");
    }
  }

  /**
   * The fail-closed path, and the reason silence now blocks. A review that dies before the head
   * SHA is known has no commit to attach a check to — so it creates none, and a required status
   * check that is absent blocks the merge. Creating one just to fail it would be strictly worse:
   * it would only work while the reviewer could still reach GitHub.
   */
  @Test
  void reviewThatDiesBeforeTheHeadShaIsKnownCreatesNoCheckRunAtAll() {
    RecordingActivities activities = new RecordingActivities();
    activities.failLoadWith =
        ApplicationFailure.newNonRetryableFailure(
            "GitHub App token endpoint returned 422", "GITHUB_TOKEN_REJECTED");

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-check-absent");

      assertThatThrownBy(() -> workflow.review(request(true)))
          .isInstanceOf(WorkflowFailedException.class);

      assertThat(activities.calls).doesNotContain("openCheckRun", "failCheckRun");
      // The pull request still says why, in the status comment.
      assertThat(activities.calls).contains("publishFailure");
    }
  }

  /** Losing the check run must not lose the review; the absence blocks the merge on its own. */
  @Test
  void checkRunThatCannotBeOpenedDoesNotFailTheReview() {
    RecordingActivities activities = new RecordingActivities();
    activities.failOpenCheckRun = true;

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-check-broken");

      ReviewResult result = workflow.review(request(true));

      assertThat(result.report()).isEqualTo(REPORT);
      assertThat(activities.calls).doesNotContain("completeCheckRun");
      assertThat(workflow.progress().phase()).isEqualTo(ReviewPhase.COMPLETED);
    }
  }

  @Test
  void reviewThatFailedAfterTheCheckCouldNotBeOpenedDoesNotTryToFailIt() {
    RecordingActivities activities = new RecordingActivities();
    activities.failOpenCheckRun = true;
    activities.failReviewWith =
        ApplicationFailure.newNonRetryableFailure("Claude exited with 1", "AGENT_FAILED");

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-check-broken-failure");

      assertThatThrownBy(() -> workflow.review(request(true)))
          .isInstanceOf(WorkflowFailedException.class);

      assertThat(activities.calls).doesNotContain("failCheckRun");
    }
  }

  @Test
  void dryRunPublishesNoCheckRunEither() {
    RecordingActivities activities = new RecordingActivities();
    activities.failReviewWith =
        ApplicationFailure.newNonRetryableFailure("Claude exited with 1", "AGENT_FAILED");

    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      CodeReviewWorkflow workflow = start(environment, activities, "review-dry-run-check");

      assertThatThrownBy(() -> workflow.review(request(false)))
          .isInstanceOf(WorkflowFailedException.class);

      assertThat(activities.calls).doesNotContain("openCheckRun", "failCheckRun");
    }
  }

  /** One fake for every case, so each test states only the behaviour it is about. */
  private static final class RecordingActivities implements ReviewActivities {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private boolean failOpen;
    private RuntimeException failLoadWith;
    private RuntimeException failReviewWith;
    private boolean failOpenCheckRun;
    private String publishedStatusCommentId;
    private String failureStatusCommentId;
    private String completedCheckRunId;
    private String failedCheckRunId;
    private ReviewFailure failure;

    @Override
    public String openStatusComment(final ReviewRequest request) {
      calls.add("openStatusComment");
      if (failOpen) {
        throw ApplicationFailure.newNonRetryableFailure("comment failed", "COMMENT_FAILED");
      }
      return "status-1";
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
        final PullRequestContext pullRequest,
        final ReviewReport reviewReport,
        final String statusCommentId) {
      calls.add("publishReview");
      publishedStatusCommentId = statusCommentId;
    }

    @Override
    public void publishFailure(
        final ReviewRequest request, final String statusCommentId, final ReviewFailure reported) {
      calls.add("publishFailure");
      failureStatusCommentId = statusCommentId;
      failure = reported;
    }

    @Override
    public String openCheckRun(final PullRequestContext pullRequest, final String workflowId) {
      calls.add("openCheckRun");
      if (failOpenCheckRun) {
        throw ApplicationFailure.newNonRetryableFailure("checks 422", "GITHUB_TOKEN_REJECTED");
      }
      return "check-1";
    }

    @Override
    public void completeCheckRun(
        final PullRequestContext pullRequest,
        final String checkRunId,
        final ReviewReport reviewReport) {
      calls.add("completeCheckRun");
      completedCheckRunId = checkRunId;
    }

    @Override
    public void failCheckRun(
        final PullRequestContext pullRequest,
        final String checkRunId,
        final ReviewFailure reported) {
      calls.add("failCheckRun");
      failedCheckRunId = checkRunId;
    }
  }
}
