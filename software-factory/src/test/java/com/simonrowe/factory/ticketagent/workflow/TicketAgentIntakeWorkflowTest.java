package com.simonrowe.factory.ticketagent.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.LinearTicket;
import com.simonrowe.factory.ticketagent.config.TicketAgentTaskQueues;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TicketAgentIntakeWorkflowTest {

  @Test
  void runsOneDispatchAndReportsWhatItStarted() {
    TicketAgentWorkflowTest.Fake fake =
        new TicketAgentWorkflowTest.Fake(new LinearTicket("i", "SIM-1", "t", "", "u",
            IssueStateType.TRIAGE, "Triage", List.of(), 0, Instant.EPOCH)) {
          @Override
          public int dispatch() {
            calls.add("dispatch");
            return 2;
          }
        };
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker worker = environment.newWorker(TicketAgentTaskQueues.TICKET_AGENT);
      worker.registerWorkflowImplementationTypes(TicketAgentIntakeWorkflowImpl.class);
      worker.registerActivitiesImplementations(fake);
      environment.start();

      int started = environment.getWorkflowClient()
          .newWorkflowStub(TicketAgentIntakeWorkflow.class,
              WorkflowOptions.newBuilder().setTaskQueue(TicketAgentTaskQueues.TICKET_AGENT)
                  .setWorkflowId("ticket-agent-intake").build())
          .dispatch();

      assertThat(started).isEqualTo(2);
      assertThat(fake.calls).containsExactly("dispatch");
    }
  }
}
