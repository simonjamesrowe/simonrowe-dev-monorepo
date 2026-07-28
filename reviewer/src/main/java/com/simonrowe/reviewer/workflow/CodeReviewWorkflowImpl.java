package com.simonrowe.reviewer.workflow;

import com.simonrowe.reviewer.config.ReviewerTaskQueues;
import com.simonrowe.reviewer.domain.PullRequestContext;
import com.simonrowe.reviewer.domain.ReviewPhase;
import com.simonrowe.reviewer.domain.ReviewProgress;
import com.simonrowe.reviewer.domain.ReviewReport;
import com.simonrowe.reviewer.domain.ReviewRequest;
import com.simonrowe.reviewer.domain.ReviewResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/** Deterministic review flow with provider calls isolated in a single-attempt activity. */
@WorkflowImpl(taskQueues = ReviewerTaskQueues.REVIEWS)
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
        networkActivities.publishReview(pullRequest, report);
      }

      current =
          new ReviewProgress(
              ReviewPhase.COMPLETED, "Review completed", pullRequest.headSha(), report);
      return new ReviewResult(
          Workflow.getInfo().getWorkflowId(), pullRequest.headSha(), request.publish(), report);
    } catch (RuntimeException exception) {
      current =
          new ReviewProgress(
              ReviewPhase.FAILED,
              safeFailureMessage(exception),
              current.headSha(),
              current.report());
      throw exception;
    }
  }

  @Override
  public ReviewProgress progress() {
    return current;
  }

  private static String safeFailureMessage(final RuntimeException exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return exception.getClass().getSimpleName();
    }
    return message.length() > 240 ? message.substring(0, 240) : message;
  }
}
