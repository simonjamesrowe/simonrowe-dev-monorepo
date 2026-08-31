package com.simonrowe.factory.logwatch.workflow;

import com.simonrowe.factory.logwatch.domain.LogWatchProgress;
import com.simonrowe.factory.logwatch.domain.LogWatchRequest;
import com.simonrowe.factory.logwatch.domain.LogWatchResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Orchestrates one log-watch scan: establish the source is alive, read the window, group what it
 * finds, and file each distinct problem into Linear through the existing sink.
 *
 * <p>Deduplication, suppression and reopening are inherited from that sink and deliberately not
 * reimplemented here.
 */
@WorkflowInterface
public interface LogWatchWorkflow {

  /**
   * Runs one scan.
   *
   * @param request the window, trigger and flags
   * @return how the run ended
   */
  @WorkflowMethod
  LogWatchResult run(LogWatchRequest request);

  /**
   * Reports where the run has got to.
   *
   * @return the current progress snapshot
   */
  @QueryMethod
  LogWatchProgress progress();
}
