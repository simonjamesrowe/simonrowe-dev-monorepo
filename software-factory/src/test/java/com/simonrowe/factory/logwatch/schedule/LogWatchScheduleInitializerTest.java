package com.simonrowe.factory.logwatch.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.logwatch.domain.LogWatchRequest;
import com.simonrowe.factory.logwatch.domain.Trigger;
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

/**
 * Mirrors {@code PlatformBackupScheduleInitializerTest}, with one deliberate divergence.
 *
 * <p>This schedule is created <strong>active</strong> where the backup and CVE ones are created
 * paused. A read-and-file scan cannot damage anything, and a paused-by-default observability check
 * is one nobody remembers to turn on. The assertion below pins that difference so it is a decision
 * rather than an accident.
 *
 * <p>The update assertion protects against the silent failure both initializers share: carrying
 * the server's paused flag forward, so a deploy cannot re-pause a schedule an operator unpaused —
 * which would stop the feature with no error anywhere.
 */
@ExtendWith(MockitoExtension.class)
class LogWatchScheduleInitializerTest {

  @Mock private ScheduleClient scheduleClient;
  @Mock private ScheduleHandle handle;

  @Captor private ArgumentCaptor<Schedule> scheduleCaptor;
  @Captor private ArgumentCaptor<ScheduleOptions> optionsCaptor;

  @Captor
  private ArgumentCaptor<Functions.Func1<ScheduleUpdateInput, ScheduleUpdate>> updaterCaptor;

  private LogWatchScheduleInitializer initializer(final boolean linearEnabled) {
    return new LogWatchScheduleInitializer(
        scheduleClient,
        new LinearProperties(linearEnabled, "key", null, "SIM", null, false, null, null));
  }

  private Schedule createdSchedule() {
    verify(scheduleClient)
        .createSchedule(
            eq(LogWatchScheduleInitializer.SCHEDULE_ID),
            scheduleCaptor.capture(),
            optionsCaptor.capture());
    return scheduleCaptor.getValue();
  }

  @Test
  @DisplayName("the schedule is created ACTIVE, unlike the backup and CVE schedules")
  void createsTheScheduleActive() {
    initializer(true).run(null);

    assertThat(createdSchedule().getState().isPaused()).isFalse();
  }

  @Test
  void firesEveryTwentyFourHours() {
    initializer(true).run(null);

    List<ScheduleIntervalSpec> intervals = createdSchedule().getSpec().getIntervals();
    assertThat(intervals).hasSize(1);
    assertThat(intervals.getFirst().getEvery()).isEqualTo(Duration.ofHours(24));
  }

  @Test
  void targetsTheLogWatchWorkflowAndQueue() {
    initializer(true).run(null);

    ScheduleActionStartWorkflow action =
        (ScheduleActionStartWorkflow) createdSchedule().getAction();
    assertThat(action.getWorkflowType()).isEqualTo("LogWatchWorkflow");
    assertThat(action.getOptions().getTaskQueue()).isEqualTo("logwatch");
    assertThat(action.getOptions().getWorkflowId()).isEqualTo("logwatch");
  }

  @Test
  @DisplayName("a slow scan never overlaps the next one")
  void skipsOverlappingRuns() {
    initializer(true).run(null);

    assertThat(createdSchedule().getPolicy().getOverlap())
        .isEqualTo(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP);
  }

  @Test
  @DisplayName("the scheduled request carries a null window, so the workflow resolves it itself")
  void scheduledRequestLeavesTheWindowToTheWorkflow() {
    initializer(true).run(null);

    ScheduleActionStartWorkflow action =
        (ScheduleActionStartWorkflow) createdSchedule().getAction();
    LogWatchRequest request = action.getArguments().get(0, LogWatchRequest.class);

    // Baking a window in at declaration time would make every scheduled run scan the same fixed
    // period forever.
    assertThat(request.windowStart()).isNull();
    assertThat(request.windowEnd()).isNull();
    assertThat(request.trigger()).isEqualTo(Trigger.SCHEDULE);
    assertThat(request.dryRun()).isFalse();
  }

  @Test
  @DisplayName("the sink's enabled flag travels on the request, since a workflow cannot read it")
  void copiesTheLinearFlagOntoTheRequest() {
    initializer(false).run(null);

    ScheduleActionStartWorkflow action =
        (ScheduleActionStartWorkflow) createdSchedule().getAction();
    assertThat(action.getArguments().get(0, LogWatchRequest.class).linearFilingEnabled())
        .isFalse();
  }

  @Test
  @DisplayName("an operator's pause survives a redeploy")
  void updatePreservesAnOperatorPause() {
    scheduleAlreadyExists();

    initializer(true).run(null);

    verify(handle).update(updaterCaptor.capture());
    ScheduleUpdate update = updaterCaptor.getValue().apply(existing(true));
    assertThat(update.getSchedule().getState().isPaused()).isTrue();
  }

  @Test
  void updateLeavesAnActiveScheduleActive() {
    scheduleAlreadyExists();

    initializer(true).run(null);

    verify(handle).update(updaterCaptor.capture());
    ScheduleUpdate update = updaterCaptor.getValue().apply(existing(false));
    assertThat(update.getSchedule().getState().isPaused()).isFalse();
  }

  private void scheduleAlreadyExists() {
    when(scheduleClient.createSchedule(any(), any(), any()))
        .thenThrow(new ScheduleAlreadyRunningException(new RuntimeException("already running")));
    when(scheduleClient.getHandle(LogWatchScheduleInitializer.SCHEDULE_ID)).thenReturn(handle);
  }

  private static ScheduleUpdateInput existing(final boolean paused) {
    Schedule schedule =
        Schedule.newBuilder()
            .setAction(
                ScheduleActionStartWorkflow.newBuilder()
                    .setWorkflowType("LogWatchWorkflow")
                    .setOptions(
                        WorkflowOptions.newBuilder()
                            .setWorkflowId("logwatch")
                            .setTaskQueue("logwatch")
                            .build())
                    .build())
            .setSpec(
                ScheduleSpec.newBuilder()
                    .setIntervals(List.of(new ScheduleIntervalSpec(Duration.ofHours(24))))
                    .build())
            .setState(ScheduleState.newBuilder().setPaused(paused).build())
            .build();
    return new ScheduleUpdateInput(
        new ScheduleDescription(
            LogWatchScheduleInitializer.SCHEDULE_ID,
            null, schedule, Map.of(), null, Map.of(), null));
  }
}
