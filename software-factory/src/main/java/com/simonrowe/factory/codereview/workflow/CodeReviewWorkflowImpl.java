package com.simonrowe.factory.codereview.workflow;

import com.simonrowe.factory.codereview.config.CodeReviewTaskQueues;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewFailure;
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
    String statusCommentId = request.publish() ? openStatusComment(request) : null;
    try {
      current =
          new ReviewProgress(
              ReviewPhase.LOADING_PULL_REQUEST, "Loading GitHub metadata", null, null);
      PullRequestContext pullRequest = networkActivities.loadPullRequest(request);

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
        networkActivities.publishReview(pullRequest, report, statusCommentId);
      }

      current =
          new ReviewProgress(
              ReviewPhase.COMPLETED, "Review completed", pullRequest.headSha(), report);
      return new ReviewResult(
          Workflow.getInfo().getWorkflowId(), pullRequest.headSha(), request.publish(), report);
    } catch (RuntimeException exception) {
      // Capture the phase before overwriting it — FAILED says nothing about where it died.
      ReviewPhase failedIn = current.phase();
      String reason = safeFailureMessage(exception);
      current = new ReviewProgress(ReviewPhase.FAILED, reason, current.headSha(), current.report());
      if (request.publish()) {
        reportFailure(
            request,
            statusCommentId,
            new ReviewFailure(failedIn, reason, Workflow.getInfo().getWorkflowId()));
      }
      throw exception;
    }
  }

  /**
   * Best-effort claim on the pull request's one review comment, reusing the comment an earlier push
   * left so the outcome replaces it rather than stacking beside it.
   *
   * <p>A pull request that cannot be commented on is still worth reviewing, so a failure here
   * yields a null id and the run continues; publishing then posts a fresh comment rather than
   * editing one.
   */
  private String openStatusComment(final ReviewRequest request) {
    try {
      return networkActivities.openStatusComment(request);
    } catch (RuntimeException exception) {
      Workflow.getLogger(CodeReviewWorkflowImpl.class)
          .warn("Could not open the review comment on the pull request", exception);
      return null;
    }
  }

  /**
   * Best-effort notice on the pull request. A failure to report the failure must not replace the
   * original one, which is what actually needs diagnosing.
   */
  private void reportFailure(
      final ReviewRequest request, final String statusCommentId, final ReviewFailure failure) {
    try {
      networkActivities.publishFailure(request, statusCommentId, failure);
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
