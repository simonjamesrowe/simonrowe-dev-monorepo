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
              // runReview is a clone, an agent run bounded by factory.codereview.agent.timeout
              // (25m default) and the report post-processing, all in one activity call. 35m
              // leaves room for a slow clone and checkout on the Pi around that 25m.
              .setStartToCloseTimeout(Duration.ofMinutes(35))
              // Not 30s. ProcessRunner only heartbeats while a child process is running, and
              // only every 10s, so it emits nothing at all for a git command that finishes
              // faster than that — the un-heartbeated gap is the sum of the workspace-prep
              // steps between two `heartbeat.accept` calls, not the duration of one process.
              // On the Pi a monorepo clone + partial-clone checkout + full-tree secret sweep
              // ran past 30s and killed a one-file review (PR #111) with
              // lastHeartbeatDetails still on "Cloning pull request repository".
              // GitWorkspaceFactory now heartbeats per step; this raises the ceiling too so
              // one slow step cannot end a review that is making progress.
              //
              // 2m, not 1m, because the timeout also sets its own flush cadence: the SDK
              // throttles delivery to min(0.8 * heartbeatTimeout, maxHeartbeatThrottleInterval),
              // the latter defaulting to 60s (HeartbeatContextImpl). At 30s that was a 24s
              // flush against a 30s deadline — 6s of slack, so even a beat every 10s tipped
              // over under load. 2m flushes every 60s against a 120s deadline, a clean 2x,
              // where 1m would be 48s against 60s. A wedged agent is still caught in 2m
              // rather than at the 35m ceiling.
              .setHeartbeatTimeout(Duration.ofMinutes(2))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
              .build());

  private ReviewProgress current = ReviewProgress.accepted();

  @Override
  public ReviewResult review(final ReviewRequest request) {
    String statusCommentId = request.publish() ? openStatusComment(request) : null;
    // Held outside the try so the catch block can tell "the check run exists and must be failed"
    // apart from "no check run was ever created". Those are different outcomes, and the second one
    // is load-bearing: an absent required check blocks the merge.
    String checkRunId = null;
    PullRequestContext pullRequest = null;
    try {
      current =
          new ReviewProgress(
              ReviewPhase.LOADING_PULL_REQUEST, "Loading GitHub metadata", null, null);
      pullRequest = networkActivities.loadPullRequest(request);

      if (request.publish()) {
        // Opened here, not alongside the status comment: a check run must name a commit, and the
        // head SHA is only certain now. `ReviewRequest.expectedHeadSha` is nullable on the manual
        // path, so there is nothing to attach a check to before this point.
        checkRunId = openCheckRun(pullRequest);
      }

      current =
          new ReviewProgress(
              ReviewPhase.REVIEWING, "Running read-only agent", pullRequest.headSha(), null);
      ReviewReport report = agentActivities.runReview(pullRequest);

      if (request.publish()) {
        current =
            new ReviewProgress(
                ReviewPhase.PUBLISHING,
                "Publishing review",
                pullRequest.headSha(),
                report);
        networkActivities.publishReview(pullRequest, report, statusCommentId);
        if (checkRunId != null) {
          networkActivities.completeCheckRun(pullRequest, checkRunId, report);
        }
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
        ReviewFailure failure =
            new ReviewFailure(failedIn, reason, Workflow.getInfo().getWorkflowId());
        reportFailure(request, statusCommentId, failure);
        // Deliberately only when a check run already exists. A review that died before the head
        // SHA was known has no commit to attach one to — and must not gain one, because the
        // check's absence is what blocks the merge. This is the fix for silence being the normal
        // presentation of a failed review: silence now blocks instead of passing.
        if (checkRunId != null) {
          failCheckRun(pullRequest, checkRunId, failure);
        }
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
   * Best-effort claim on the {@code Code Review} check run.
   *
   * <p>A failure here yields a null id and the review continues. Unlike the status comment, nothing
   * needs to compensate for the loss: an absent required check blocks the merge on its own, which
   * is exactly the outcome a reviewer that cannot publish should produce.
   */
  private String openCheckRun(final PullRequestContext pullRequest) {
    try {
      return networkActivities.openCheckRun(pullRequest, Workflow.getInfo().getWorkflowId());
    } catch (RuntimeException exception) {
      Workflow.getLogger(CodeReviewWorkflowImpl.class)
          .warn("Could not open the Code Review check run", exception);
      return null;
    }
  }

  /**
   * Best-effort red check. Like {@link #reportFailure}, a failure to report the failure must not
   * replace the original one.
   */
  private void failCheckRun(
      final PullRequestContext pullRequest, final String checkRunId, final ReviewFailure failure) {
    try {
      networkActivities.failCheckRun(pullRequest, checkRunId, failure);
    } catch (RuntimeException exception) {
      Workflow.getLogger(CodeReviewWorkflowImpl.class)
          .warn("Could not fail the Code Review check run", exception);
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
