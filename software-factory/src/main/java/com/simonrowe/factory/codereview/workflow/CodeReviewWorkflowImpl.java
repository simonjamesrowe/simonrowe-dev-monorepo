package com.simonrowe.factory.codereview.workflow;

import com.simonrowe.factory.codereview.config.CodeReviewTaskQueues;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewPhase;
import com.simonrowe.factory.codereview.domain.ReviewProgress;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import com.simonrowe.factory.codereview.domain.ReviewRequest;
import com.simonrowe.factory.codereview.domain.ReviewResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/** Deterministic review flow with provider calls isolated in a single-attempt activity. */
@WorkflowImpl(taskQueues = CodeReviewTaskQueues.REVIEWS)
public class CodeReviewWorkflowImpl implements CodeReviewWorkflow {

  private static final RetryOptions NETWORK_RETRIES =
      RetryOptions.newBuilder()
          .setInitialInterval(Duration.ofSeconds(1))
          .setMaximumInterval(Duration.ofSeconds(10))
          .setMaximumAttempts(3)
          .build();

  private final ReviewActivities networkActivities =
      Workflow.newActivityStub(
          ReviewActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setRetryOptions(NETWORK_RETRIES)
              .build());

  private final ReviewActivities agentActivities =
      Workflow.newActivityStub(
          ReviewActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(20))
              .setHeartbeatTimeout(Duration.ofSeconds(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
              .build());

  private ReviewProgress current = ReviewProgress.accepted();

  @Override
  public ReviewResult review(final ReviewRequest request) {
    PullRequestContext pullRequest = null;
    try {
      current =
          new ReviewProgress(
              ReviewPhase.LOADING_PULL_REQUEST, "Loading GitHub metadata", null, null);
      pullRequest = networkActivities.loadPullRequest(request);

      current =
          new ReviewProgress(
              ReviewPhase.REVIEWING, "Running read-only agent", pullRequest.headSha(), null);
      ReviewReport report = agentActivities.runReview(pullRequest);

      if (request.publish()) {
        current =
            new ReviewProgress(
                ReviewPhase.PUBLISHING,
                "Publishing advisory comment",
                pullRequest.headSha(),
                report);
        networkActivities.publishReview(pullRequest, report);
      }

      current =
          new ReviewProgress(
              ReviewPhase.COMPLETED, "Review completed", pullRequest.headSha(), report);
      return new ReviewResult(
          Workflow.getInfo().getWorkflowId(), pullRequest.headSha(), request.publish(), report);
    } catch (RuntimeException exception) {
      String reason = safeFailureMessage(exception);
      current = new ReviewProgress(ReviewPhase.FAILED, reason, current.headSha(), current.report());
      if (request.publish() && pullRequest != null) {
        reportFailure(pullRequest, reason);
      }
      throw exception;
    }
  }

  /**
   * Best-effort notice on the pull request. A failure to report the failure must not replace the
   * original one, which is what actually needs diagnosing.
   */
  private void reportFailure(final PullRequestContext pullRequest, final String reason) {
    try {
      networkActivities.publishFailure(pullRequest, reason);
    } catch (RuntimeException exception) {
      Workflow.getLogger(CodeReviewWorkflowImpl.class)
          .warn("Could not publish review failure notice", exception);
    }
  }

  @Override
  public ReviewProgress progress() {
    return current;
  }

  /**
   * Temporal wraps the cause in an {@link io.temporal.failure.ActivityFailure} whose own message is
   * boilerplate ("Activity task failed", event ids, retry state). The message worth showing is the
   * innermost one, so unwrap before truncating.
   */
  private static String safeFailureMessage(final RuntimeException exception) {
    String message = null;
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
        message = cause.getMessage();
      }
    }
    if (message == null) {
      return exception.getClass().getSimpleName();
    }
    return message.length() > 240 ? message.substring(0, 240) : message;
  }
}
