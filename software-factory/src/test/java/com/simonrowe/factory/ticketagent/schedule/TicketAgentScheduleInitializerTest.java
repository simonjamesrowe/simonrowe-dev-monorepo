package com.simonrowe.factory.ticketagent.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleDescription;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.ScheduleUpdate;
import io.temporal.client.schedules.ScheduleUpdateInput;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The intake leads to pull requests, so it is created paused, like the CVE schedule, and a
 * reconcile keeps whatever pause state an operator chose.
 */
class TicketAgentScheduleInitializerTest {

  private final ScheduleClient scheduleClient = mock(ScheduleClient.class);
  private final ScheduleHandle handle = mock(ScheduleHandle.class);

  private Schedule created() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Schedule> schedule = ArgumentCaptor.forClass(Schedule.class);
    verify(scheduleClient).createSchedule(
        eq(TicketAgentScheduleInitializer.SCHEDULE_ID), schedule.capture(),
        any(ScheduleOptions.class));
    return schedule.getValue();
  }

  @Test
  void isCreatedPaused() {
    new TicketAgentScheduleInitializer(scheduleClient).run(null);

    assertThat(created().getState().isPaused()).isTrue();
  }

  @Test
  void startsTheIntakeWorkflowEveryFiveMinutesAndNeverOverlaps() {
    new TicketAgentScheduleInitializer(scheduleClient).run(null);

    Schedule schedule = created();
    ScheduleActionStartWorkflow action = (ScheduleActionStartWorkflow) schedule.getAction();
    assertThat(action.getWorkflowType()).isEqualTo("TicketAgentIntakeWorkflow");
    assertThat(action.getOptions().getTaskQueue()).isEqualTo("ticket-agent");
    assertThat(action.getOptions().getWorkflowExecutionTimeout())
        .isLessThan(TicketAgentScheduleInitializer.INTERVAL.multipliedBy(3));
    assertThat(schedule.getSpec().getIntervals().getFirst().getEvery())
        .isEqualTo(Duration.ofMinutes(5));
    assertThat(schedule.getPolicy().getOverlap())
        .isEqualTo(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP);
  }

  @Test
  void reconcileKeepsAnOperatorsResume() {
    when(scheduleClient.createSchedule(any(), any(), any()))
        .thenThrow(new ScheduleAlreadyRunningException(new RuntimeException("exists")));
    when(scheduleClient.getHandle(TicketAgentScheduleInitializer.SCHEDULE_ID)).thenReturn(handle);

    new TicketAgentScheduleInitializer(scheduleClient).run(null);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Functions.Func1<ScheduleUpdateInput, ScheduleUpdate>> updater =
        ArgumentCaptor.forClass(Functions.Func1.class);
    verify(handle).update(updater.capture());
    assertThat(updater.getValue().apply(existing(false)).getSchedule().getState().isPaused())
        .isFalse();
    assertThat(updater.getValue().apply(existing(true)).getSchedule().getState().isPaused())
        .isTrue();
  }

  private static ScheduleUpdateInput existing(final boolean paused) {
    return new ScheduleUpdateInput(
        new ScheduleDescription(
            TicketAgentScheduleInitializer.SCHEDULE_ID, null,
            TicketAgentScheduleInitializer.schedule(paused), Map.of(), null, Map.of(), null));
  }
}
