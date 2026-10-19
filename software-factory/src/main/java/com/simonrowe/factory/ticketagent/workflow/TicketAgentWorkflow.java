package com.simonrowe.factory.ticketagent.workflow;

import com.simonrowe.factory.ticketagent.domain.ChecksSignal;
import com.simonrowe.factory.ticketagent.domain.TicketAgentProgress;
import com.simonrowe.factory.ticketagent.domain.TicketAgentRequest;
import com.simonrowe.factory.ticketagent.domain.TicketAgentResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * One ticket, from claim to merge or hand-back.
 *
 * <p>Waits on signals rather than polling: GitHub's {@code workflow_run} webhook wakes it when CI
 * finishes, and {@code pull_request closed} when the pull request merges. While it waits it holds
 * no worker, so a run parked for days on a person's merge costs nothing.
 */
@WorkflowInterface
public interface TicketAgentWorkflow {

  @WorkflowMethod
  TicketAgentResult run(TicketAgentRequest request);

  /** CI finished on the agent's branch. */
  @SignalMethod
  void checksCompleted(ChecksSignal signal);

  /** The agent's pull request closed. */
  @SignalMethod
  void pullRequestClosed(boolean merged);

  /** Stop and hand the ticket back, from the console. */
  @SignalMethod
  void stop(String reason);

  @QueryMethod
  TicketAgentProgress progress();
}
