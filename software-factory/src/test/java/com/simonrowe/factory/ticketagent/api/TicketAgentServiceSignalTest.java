package com.simonrowe.factory.ticketagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.testsupport.TicketAgentTestProperties;
import com.simonrowe.factory.ticketagent.domain.ChecksSignal;
import com.simonrowe.factory.ticketagent.schedule.TicketAgentScheduleInitializer;
import com.simonrowe.factory.ticketagent.workflow.TicketAgentWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleHandle;
import org.junit.jupiter.api.Test;

class TicketAgentServiceSignalTest {

  private final WorkflowClient workflowClient = mock(WorkflowClient.class);
  private final ScheduleClient scheduleClient = mock(ScheduleClient.class);
  private final TicketAgentWorkflow workflow = mock(TicketAgentWorkflow.class);
  private final TicketAgentService service =
      new TicketAgentService(workflowClient, scheduleClient, TicketAgentTestProperties.of(true));

  @Test
  void routesSignalToTheRunThatOwnsTheBranch() {
    when(workflowClient.newWorkflowStub(TicketAgentWorkflow.class, "ticket-agent-SIM-107"))
        .thenReturn(workflow);
    ChecksSignal signal = new ChecksSignal("sha1", "failure", 12);

    service.signalChecks("agent/sim-107", signal);
    service.signalClosed("agent/sim-107", true);

    verify(workflow).checksCompleted(signal);
    verify(workflow).pullRequestClosed(true);
  }

  @Test
  void ignoresBranchesThatAreNotTheAgents() {
    service.signalChecks("feat/sim-107", new ChecksSignal("sha1", "success", 1));

    verify(workflowClient, never()).newWorkflowStub(eq(TicketAgentWorkflow.class), anyString());
  }

  @Test
  void ticketWithNoLiveRunCannotBeStopped() {
    when(workflowClient.newWorkflowStub(TicketAgentWorkflow.class, "ticket-agent-SIM-1"))
        .thenReturn(workflow);
    doThrow(new WorkflowNotFoundException(
        WorkflowExecution.newBuilder().setWorkflowId("ticket-agent-SIM-1").build(),
        "TicketAgentWorkflow", null))
        .when(workflow).stop(any());

    assertThat(service.stop("SIM-1", "Stopped")).isFalse();
  }

  @Test
  void stopsLiveRun() {
    when(workflowClient.newWorkflowStub(TicketAgentWorkflow.class, "ticket-agent-SIM-1"))
        .thenReturn(workflow);

    assertThat(service.stop("SIM-1", "Stopped")).isTrue();
    verify(workflow).stop("Stopped");
  }

  @Test
  void pausesAndResumesTheIntake() {
    ScheduleHandle handle = mock(ScheduleHandle.class);
    when(scheduleClient.getHandle(TicketAgentScheduleInitializer.SCHEDULE_ID)).thenReturn(handle);

    service.setIntakePaused(true);
    service.setIntakePaused(false);

    verify(handle).pause(anyString());
    verify(handle).unpause(anyString());
  }
}
