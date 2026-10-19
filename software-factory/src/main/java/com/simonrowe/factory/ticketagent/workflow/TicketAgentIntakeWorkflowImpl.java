package com.simonrowe.factory.ticketagent.workflow;

import com.simonrowe.factory.ticketagent.config.TicketAgentTaskQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * One activity, because the decision is all I/O: which tickets carry the label, which are already
 * running. The schedule's overlap policy is what stops two passes racing to start the same ticket,
 * and the fixed workflow id per ticket is what makes a race harmless if one ever did.
 */
@WorkflowImpl(taskQueues = TicketAgentTaskQueues.TICKET_AGENT)
public class TicketAgentIntakeWorkflowImpl implements TicketAgentIntakeWorkflow {

  private final TicketAgentActivities activities =
      Workflow.newActivityStub(
          TicketAgentActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
              .build());

  @Override
  public int dispatch() {
    return activities.dispatch();
  }
}
