package com.simonrowe.factory.ticketagent.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** One intake pass, fired by the {@code ticket-agent-intake} schedule. */
@WorkflowInterface
public interface TicketAgentIntakeWorkflow {

  /** Returns how many runs it started. */
  @WorkflowMethod
  int dispatch();
}
