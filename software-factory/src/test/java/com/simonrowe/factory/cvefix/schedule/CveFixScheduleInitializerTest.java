package com.simonrowe.factory.cvefix.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.cvefix.domain.CveFixRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CveFixScheduleInitializerTest {

  @Mock private ScheduleClient scheduleClient;
  @Mock private ScheduleHandle handle;

  @Captor private ArgumentCaptor<Schedule> scheduleCaptor;
  @Captor private ArgumentCaptor<ScheduleOptions> optionsCaptor;

  @Captor
  private ArgumentCaptor<Functions.Func1<ScheduleUpdateInput, ScheduleUpdate>> updaterCaptor;

  private CveFixScheduleInitializer initializer;

  @BeforeEach
  void setUp() {
    CveFixProperties.Ci ci =
        new CveFixProperties.Ci(Duration.ofMinutes(7), 5, Duration.ofHours(4), List.of("evaluate"));
    CveFixProperties properties =
        new CveFixProperties(true, null, null, null, null, null, null, null, null, null, ci);
    initializer = new CveFixScheduleInitializer(scheduleClient, properties);
  }

  private Schedule createdSchedule() {
    verify(scheduleClient)
        .createSchedule(
            eq(CveFixScheduleInitializer.SCHEDULE_ID),
            scheduleCaptor.capture(),
            optionsCaptor.capture());
    return scheduleCaptor.getValue();
  }

  private void scheduleAlreadyExists() {
    when(scheduleClient.createSchedule(any(), any(), any()))
        .thenThrow(new ScheduleAlreadyRunningException(new RuntimeException("already running")));
    when(scheduleClient.getHandle(CveFixScheduleInitializer.SCHEDULE_ID)).thenReturn(handle);
  }

  /** A stand-in for what the server already holds, which only its state is read from. */
  private static ScheduleUpdateInput existing(final boolean paused) {
    Schedule schedule =
        Schedule.newBuilder()
            .setAction(
                ScheduleActionStartWorkflow.newBuilder()
                    .setWorkflowType("CveFixWorkflow")
                    .setOptions(
                        WorkflowOptions.newBuilder()
                            .setWorkflowId("cve-fix")
                            .setTaskQueue("cve-fix")
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
            CveFixScheduleInitializer.SCHEDULE_ID, null, schedule, Map.of(), null, Map.of(), null));
  }

  @Test
  void createsTheScheduleWhenItDoesNotExist() {
    initializer.run(null);

    Schedule schedule = createdSchedule();
    assertThat(schedule.getSpec().getIntervals()).hasSize(1);
    assertThat(schedule.getSpec().getIntervals().get(0).getEvery()).isEqualTo(Duration.ofHours(24));

    ScheduleActionStartWorkflow action = (ScheduleActionStartWorkflow) schedule.getAction();
    assertThat(action.getWorkflowType()).isEqualTo("CveFixWorkflow");
    assertThat(action.getOptions().getTaskQueue()).isEqualTo("cve-fix");
    assertThat(action.getOptions().getWorkflowId()).isEqualTo("cve-fix");

    // Overlap belongs to SchedulePolicy: ScheduleOptions has no setOverlap at all, so this is
    // where an implementation has to put it for the server to honour SKIP.
    assertThat(schedule.getPolicy().getOverlap())
        .isEqualTo(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP);
    // Creating the schedule must not fire a run immediately either.
    assertThat(optionsCaptor.getValue().isTriggerImmediately()).isFalse();
  }

  @Test
  // Named without "IsAManualTrigger": consecutive capitals are capped at one by checkstyle.
  void isPausedOnCreationSoTheFirstRunIsTriggeredByHand() {
    initializer.run(null);

    assertThat(createdSchedule().getState().isPaused()).isTrue();
  }

  @Test
  void passesTheConfiguredCiSettingsAsTheWorkflowArgument() {
    initializer.run(null);

    ScheduleActionStartWorkflow action =
        (ScheduleActionStartWorkflow) createdSchedule().getAction();
    assertThat(action.getArguments().getSize()).isEqualTo(1);
    // The workflow cannot inject CveFixProperties, so the CI settings have to travel in the
    // request this initializer schedules.
    assertThat(action.getArguments().get(0, CveFixRequest.class))
        .isEqualTo(new CveFixRequest(false, Duration.ofMinutes(7), 5, Duration.ofHours(4)));
  }

  @Test
  void updatesTheExistingScheduleRatherThanFailingOnRestart() {
    scheduleAlreadyExists();

    initializer.run(null);

    verify(handle).update(updaterCaptor.capture());
    ScheduleUpdate update = updaterCaptor.getValue().apply(existing(true));
    ScheduleActionStartWorkflow action =
        (ScheduleActionStartWorkflow) update.getSchedule().getAction();
    assertThat(action.getOptions().getTaskQueue()).isEqualTo("cve-fix");
    assertThat(update.getSchedule().getSpec().getIntervals().get(0).getEvery())
        .isEqualTo(Duration.ofHours(24));
    assertThat(update.getSchedule().getPolicy().getOverlap())
        .isEqualTo(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP);
    verify(handle, never()).delete();
  }

  @Test
  void keepsTheOperatorsUnpausedStateWhenItUpdates() {
    scheduleAlreadyExists();

    initializer.run(null);

    verify(handle).update(updaterCaptor.capture());
    // A restart must not re-pause a schedule the operator unpaused, or the feature quietly
    // stops running after every deploy.
    assertThat(updaterCaptor.getValue().apply(existing(false)).getSchedule().getState().isPaused())
        .isFalse();
  }
}
