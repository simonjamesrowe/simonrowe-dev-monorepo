package com.simonrowe.factory.pageread.workflow;

import com.simonrowe.factory.pageread.config.PageReadTaskQueues;
import com.simonrowe.factory.pageread.domain.PageReadInput;
import com.simonrowe.factory.pageread.domain.PageReadPhase;
import com.simonrowe.factory.pageread.domain.PageReadProgress;
import com.simonrowe.factory.pageread.domain.PageReadResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * Schedules the read on the page-reader's queue and reports how it went.
 *
 * <p>Bounded at every level, because the caller always has a fallback (Jina Reader, then the
 * stored feed text) and is waiting on the answer:
 * <ul>
 *   <li>start-to-close 2 minutes per attempt, above the page-reader's own 90-second read
 *       deadline, which closes the browser itself;</li>
 *   <li>two attempts, for a timeout or a crashed browser. BLOCKED, REFUSED and EMPTY are
 *       results, not failures, so they are never retried: the page would answer the same way;</li>
 *   <li>schedule-to-close 4 minutes, so a read with no page-reader polling ends instead of
 *       waiting for one for ever.</li>
 * </ul>
 * There is deliberately no heartbeat timeout: the page-reader heartbeats only while a challenge
 * clears, and is silent for up to a minute while a page loads.
 */
@WorkflowImpl(taskQueues = PageReadTaskQueues.PAGE_READ)
public class PageReadWorkflowImpl implements PageReadWorkflow {

  private final PageReadActivities activities =
      Workflow.newActivityStub(
          PageReadActivities.class,
          ActivityOptions.newBuilder()
              .setTaskQueue(PageReadTaskQueues.PAGE_READER)
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setScheduleToCloseTimeout(Duration.ofMinutes(4))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(5))
                      .setMaximumAttempts(2)
                      .setDoNotRetry("UnreadableUrl")
                      .build())
              .build());

  private PageReadProgress current = PageReadProgress.accepted();

  @Override
  public PageReadResult read(final PageReadInput input) {
    current = new PageReadProgress(PageReadPhase.READING, "Reading in the page-reader", null);
    try {
      PageReadResult result = activities.readPage(input);
      current = new PageReadProgress(PageReadPhase.COMPLETED, "Completed", result.outcome());
      return result;
    } catch (ActivityFailure failure) {
      current =
          new PageReadProgress(PageReadPhase.FAILED, "The page-reader could not read it", null);
      throw failure;
    }
  }

  @Override
  public PageReadProgress progress() {
    return current;
  }
}
