package com.simonrowe.factory.staticanalysis.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.staticanalysis.config.StaticAnalysisProperties;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisRequest;
import com.simonrowe.factory.staticanalysis.domain.Trigger;
import com.simonrowe.factory.temporal.ScheduledRuns;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleDescription;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.client.schedules.ScheduleIntervalSpec;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.client.schedules.ScheduleState;
import io.temporal.client.schedules.ScheduleUpdate;
import io.temporal.client.schedules.ScheduleUpdateInput;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Mirrors {@code LogWatchScheduleInitializerTest}: created active, reconciled, never re-paused. */
@ExtendWith(MockitoExtension.class)
class StaticAnalysisScheduleInitializerTest {

  @Mock private ScheduleClient scheduleClient;
  @Mock private ScheduleHandle handle;

  @Captor private ArgumentCaptor<Schedule> scheduleCaptor;

  @Captor
  private ArgumentCaptor<Functions.Func1<ScheduleUpdateInput, ScheduleUpdate>> updaterCaptor;

  private StaticAnalysisScheduleInitializer initializer(final boolean linearEnabled) {
    return new StaticAnalysisScheduleInitializer(
        scheduleClient,
        new LinearProperties(linearEnabled, "key", null, "SIM", null, false, null, null),
        new StaticAnalysisProperties(
            true, null, null, null, null, null, null, null, null, 0, null, null, null, null,
            null));
  }

  private Schedule createdSchedule() {
    verify(scheduleClient).createSchedule(
        eq(StaticAnalysisScheduleInitializer.SCHEDULE_ID), scheduleCaptor.capture(),
        any(ScheduleOptions.class));
    return scheduleCaptor.getValue();
  }

  @Test
  void createsTheScheduleActive() {
    initializer(true).run(null);

    assertThat(createdSchedule().getState().isPaused()).isFalse();
  }

  @Test
  void firesDailyAtThreeInTheMorning() {
    initializer(true).run(null);

    List<ScheduleIntervalSpec> intervals = createdSchedule().getSpec().getIntervals();
    assertThat(intervals).hasSize(1);
    assertThat(intervals.getFirst().getEvery()).isEqualTo(Duration.ofHours(24));
    assertThat(intervals.getFirst().getOffset()).isEqualTo(Duration.ofHours(3));
  }

  @Test
  void targetsTheWorkflowAndQueueAndBoundsEachRun() {
    initializer(true).run(null);

    ScheduleActionStartWorkflow action =
        (ScheduleActionStartWorkflow) createdSchedule().getAction();
    assertThat(action.getWorkflowType()).isEqualTo("StaticAnalysisWorkflow");
    assertThat(action.getOptions().getTaskQueue()).isEqualTo("static-analysis");
    assertThat(action.getOptions().getWorkflowExecutionTimeout())
        .isEqualTo(ScheduledRuns.EXECUTION_TIMEOUT)
        .isLessThan(StaticAnalysisScheduleInitializer.INTERVAL);
    assertThat(createdSchedule().getPolicy().getOverlap())
        .isEqualTo(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP);
  }

  @Test
  @DisplayName("the request carries the sink flag and the sweep settings")
  void carriesConfigurationOnTheRequest() {
    initializer(false).run(null);

    ScheduleActionStartWorkflow action =
        (ScheduleActionStartWorkflow) createdSchedule().getAction();
    StaticAnalysisRequest request = action.getArguments().get(0, StaticAnalysisRequest.class);
    assertThat(request.trigger()).isEqualTo(Trigger.SCHEDULED);
    assertThat(request.dryRun()).isFalse();
    assertThat(request.linearFilingEnabled()).isFalse();
    assertThat(request.resolveWhenClear()).isTrue();
    assertThat(request.resolveAfter()).isEqualTo(Duration.ofHours(12));
  }

  @Test
  @DisplayName("an operator's pause survives a redeploy")
  void updatePreservesAnOperatorPause() {
    when(scheduleClient.createSchedule(any(), any(), any()))
        .thenThrow(new ScheduleAlreadyRunningException(new RuntimeException("already running")));
    when(scheduleClient.getHandle(StaticAnalysisScheduleInitializer.SCHEDULE_ID))
        .thenReturn(handle);

    initializer(true).run(null);

    verify(handle).update(updaterCaptor.capture());
    Schedule existing = Schedule.newBuilder()
        .setAction(ScheduleActionStartWorkflow.newBuilder()
            .setWorkflowType("StaticAnalysisWorkflow")
            .setOptions(WorkflowOptions.newBuilder()
                .setWorkflowId("static-analysis").setTaskQueue("static-analysis").build())
            .build())
        .setSpec(ScheduleSpec.newBuilder().build())
        .setState(ScheduleState.newBuilder().setPaused(true).build())
        .build();
    ScheduleUpdate update = updaterCaptor.getValue().apply(new ScheduleUpdateInput(
        new ScheduleDescription(StaticAnalysisScheduleInitializer.SCHEDULE_ID, null, existing,
            Map.of(), null, Map.of(), null)));
    assertThat(update.getSchedule().getState().isPaused()).isTrue();
  }
}
