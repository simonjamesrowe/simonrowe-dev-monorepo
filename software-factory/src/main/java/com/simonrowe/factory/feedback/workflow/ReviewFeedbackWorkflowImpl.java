package com.simonrowe.factory.feedback.workflow;

import com.simonrowe.factory.feedback.config.FeedbackTaskQueues;
import com.simonrowe.factory.feedback.domain.DistillationOutcome;
import com.simonrowe.factory.feedback.domain.DistillationStatus;
import com.simonrowe.factory.feedback.domain.FeedbackPhase;
import com.simonrowe.factory.feedback.domain.FeedbackProgress;
import com.simonrowe.factory.feedback.domain.FeedbackRequest;
import com.simonrowe.factory.feedback.domain.FeedbackResult;
import com.simonrowe.factory.feedback.domain.Lesson;
import com.simonrowe.factory.feedback.domain.ReviewConversation;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.List;

/** Deterministic feedback flow with harvesting and distillation isolated in agent activities. */
@WorkflowImpl(taskQueues = FeedbackTaskQueues.REVIEW_FEEDBACK)
public class ReviewFeedbackWorkflowImpl implements ReviewFeedbackWorkflow {

  private static final RetryOptions NETWORK_RETRIES =
      RetryOptions.newBuilder()
          .setInitialInterval(Duration.ofSeconds(1))
          .setMaximumInterval(Duration.ofSeconds(10))
          .setMaximumAttempts(3)
          .build();

  private final FeedbackActivities networkActivities =
      Workflow.newActivityStub(
          FeedbackActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setRetryOptions(NETWORK_RETRIES)
              .build());

  private final FeedbackActivities agentActivities =
      Workflow.newActivityStub(
          FeedbackActivities.class,
          ActivityOptions.newBuilder()
              // Must comfortably exceed factory.feedback.distill.timeout (15m default) times the
              // maximum number of targets distillAndPropose processes serially in one activity
              // call (currently at most 2: agent-setup and the source repo). 20m is already tight
              // against 2 * 15m = 30m — revisit this timeout if a third target type is ever added.
              .setStartToCloseTimeout(Duration.ofMinutes(20))
              .setHeartbeatTimeout(Duration.ofSeconds(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
              .build());

  private FeedbackProgress current = FeedbackProgress.accepted();

  @Override
  public FeedbackResult harvest(final FeedbackRequest request) {
    String workflowId = Workflow.getInfo().getWorkflowId();
    try {
      current = new FeedbackProgress(FeedbackPhase.FETCHING, "Fetching review conversation", null);
      ReviewConversation conversation = networkActivities.fetchConversation(request);

      if (!conversation.hasHumanSignal()) {
        current = new FeedbackProgress(FeedbackPhase.NO_SIGNAL, "No human review activity", 0);
        networkActivities.recordLearnings(
            request, conversation, List.of(), workflowId, DistillationStatus.NO_SIGNAL);
        return new FeedbackResult(workflowId, 0, DistillationStatus.NO_SIGNAL, List.of());
      }

      current = new FeedbackProgress(FeedbackPhase.HARVESTING, "Extracting lessons", null);
      List<Lesson> lessons = agentActivities.harvestLessons(request, conversation);

      DistillationStatus initialStatus =
          request.dryRun() ? DistillationStatus.DRY_RUN : DistillationStatus.SKIPPED_NO_LESSONS;
      current =
          new FeedbackProgress(FeedbackPhase.LOGGING, "Recording learnings", lessons.size());
      networkActivities.recordLearnings(request, conversation, lessons, workflowId, initialStatus);

      if (request.dryRun() || lessons.isEmpty()) {
        current = new FeedbackProgress(FeedbackPhase.COMPLETED, "Completed", lessons.size());
        return new FeedbackResult(workflowId, lessons.size(), initialStatus, List.of());
      }

      current =
          new FeedbackProgress(
              FeedbackPhase.DISTILLING, "Proposing guidance changes", lessons.size());
      DistillationOutcome outcome;
      try {
        outcome = agentActivities.distillAndPropose(request, lessons);
      } catch (RuntimeException exception) {
        // A distillation failure must still leave the Mongo review_learnings record at
        // DistillationStatus.FAILED, not at whatever initialStatus recordLearnings first wrote
        // (e.g. SKIPPED_NO_LESSONS) — otherwise a failed run is indistinguishable from one that
        // never attempted distillation. Rethrow the original exception afterwards so the outer
        // catch below still runs its own FeedbackProgress handling.
        recordDistillationFailure(request, exception);
        throw exception;
      }
      networkActivities.recordDistillation(request, outcome);
      if (outcome.status() == DistillationStatus.FAILED) {
        // Every target failed and nothing was proposed. Mongo already durably reflects that
        // (recordDistillation above), but the workflow execution itself must not close as
        // Completed: WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY (see
        // FeedbackWorkflowService.start) only allows a manual re-drive of this workflow id when
        // the prior execution ended Failed, and the progress query must report FAILED rather than
        // a misleading COMPLETED. Throwing here is caught by the outer catch below, which sets
        // FeedbackPhase.FAILED and rethrows — recordDistillation is not called again.
        //
        // Must be an ApplicationFailure (or another TemporalFailure), not a plain JDK exception
        // (e.g. IllegalStateException): a raw exception thrown directly from workflow code here
        // (as opposed to one propagated from a failed Activity, which the SDK already wraps in
        // ActivityFailure before it reaches workflow code) is not recognised by this SDK version
        // as a deliberate business failure — it manifests as an infinite workflow-task retry loop
        // instead of a clean workflow failure, confirmed by reproducing it: the workflow never
        // closes, Attempt keeps climbing, and WorkflowClient.getResult() blocks forever.
        throw ApplicationFailure.newNonRetryableFailure(
            "Distillation failed for all targets: " + outcome.detail(), "DISTILLATION_FAILED");
      }

      current = new FeedbackProgress(FeedbackPhase.COMPLETED, "Completed", lessons.size());
      return new FeedbackResult(workflowId, lessons.size(), outcome.status(), outcome.prUrls());
    } catch (RuntimeException exception) {
      current =
          new FeedbackProgress(
              FeedbackPhase.FAILED, safeFailureMessage(exception), current.lessonCount());
      throw exception;
    }
  }

  /**
   * Best-effort: records the distillation failure so Mongo shows {@code FAILED} instead of the
   * initial status. This is a network activity so it is safe to call from a catch block, but a
   * failure to record the failure must not mask the real exception, mirroring {@code
   * CodeReviewWorkflowImpl.reportFailure}'s pattern for its own failure notice.
   */
  private void recordDistillationFailure(
      final FeedbackRequest request, final RuntimeException exception) {
    try {
      networkActivities.recordDistillation(
          request,
          new DistillationOutcome(
              DistillationStatus.FAILED, List.of(), safeFailureMessage(exception)));
    } catch (RuntimeException recordException) {
      Workflow.getLogger(ReviewFeedbackWorkflowImpl.class)
          .warn("Could not record distillation failure", recordException);
    }
  }

  @Override
  public FeedbackProgress progress() {
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
