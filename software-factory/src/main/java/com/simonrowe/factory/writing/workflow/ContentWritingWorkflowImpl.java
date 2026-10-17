package com.simonrowe.factory.writing.workflow;

import com.simonrowe.factory.writing.config.WritingTaskQueues;
import com.simonrowe.factory.writing.domain.WritingPhase;
import com.simonrowe.factory.writing.domain.WritingProgress;
import com.simonrowe.factory.writing.domain.WritingRequest;
import com.simonrowe.factory.writing.domain.WritingResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * One activity, bounded at every level.
 *
 * <p>The caller always has a fallback (the backend writes with OpenAI instead), so this workflow
 * is built to fail quickly and visibly rather than to try hard: two attempts, a 10-minute
 * start-to-close per attempt, and a 30-minute execution timeout set by {@code
 * ContentWritingService} when it starts the run. A run stuck behind the subscription's rate limit
 * therefore ends as {@code TIMED_OUT}, which the API reports as {@code FAILED}, rather than
 * holding its caller for ever.
 */
@WorkflowImpl(taskQueues = WritingTaskQueues.CONTENT_WRITING)
public class ContentWritingWorkflowImpl implements ContentWritingWorkflow {

  private final ContentWritingActivities activities =
      Workflow.newActivityStub(
          ContentWritingActivities.class,
          ActivityOptions.newBuilder()
              // factory.writing.timeout (5m) for the CLI run, with headroom for process start and
              // the temp directory. Workflow code cannot read configuration, so recompute this by
              // hand if that timeout is raised.
              .setStartToCloseTimeout(Duration.ofMinutes(10))
              // ProcessRunner heartbeats every 10s while the CLI runs; 2m matches the other
              // agent activities and tolerates a slow Pi.
              .setHeartbeatTimeout(Duration.ofMinutes(2))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(30))
                      .setMaximumAttempts(2)
                      .build())
              .build());

  private WritingProgress current = WritingProgress.accepted();

  @Override
  public WritingResult write(final WritingRequest request) {
    current = new WritingProgress(WritingPhase.WRITING, "Writing with Claude Code", null);
    try {
      WritingResult result = activities.write(request);
      current =
          new WritingProgress(WritingPhase.COMPLETED, "Completed", result.text().length());
      return result;
    } catch (ActivityFailure failure) {
      current = new WritingProgress(WritingPhase.FAILED, "Claude Code could not write it", null);
      throw failure;
    }
  }

  @Override
  public WritingProgress progress() {
    return current;
  }
}
