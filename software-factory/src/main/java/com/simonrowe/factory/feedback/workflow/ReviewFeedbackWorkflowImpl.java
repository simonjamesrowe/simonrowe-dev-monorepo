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
      DistillationOutcome outcome = agentActivities.distillAndPropose(request, lessons);
      networkActivities.recordDistillation(request, outcome);

      current = new FeedbackProgress(FeedbackPhase.COMPLETED, "Completed", lessons.size());
      return new FeedbackResult(workflowId, lessons.size(), outcome.status(), outcome.prUrls());
    } catch (RuntimeException exception) {
      current =
          new FeedbackProgress(
              FeedbackPhase.FAILED, safeFailureMessage(exception), current.lessonCount());
      throw exception;
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
