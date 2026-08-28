package com.simonrowe.factory.platformbackup.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleCalendarSpec;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleDescription;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.ScheduleRange;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.client.schedules.ScheduleState;
import io.temporal.client.schedules.ScheduleUpdate;
import io.temporal.client.schedules.ScheduleUpdateInput;
import io.temporal.workflow.Functions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mirrors {@code CveFixScheduleInitializerTest}, because the two initializers must behave
 * identically — an operator reasons about them the same way.
 *
 * <p>Two assertions here protect against silent failures rather than loud ones: creating the
 * schedule <em>paused</em> (so enabling the flag does not immediately start reading production
 * datastores), and carrying the server's paused flag forward on update (so a deploy does not
 * re-pause a schedule the operator unpaused, which would stop the backup with no error anywhere).
 */
@ExtendWith(MockitoExtension.class)
class PlatformBackupScheduleInitializerTest {

  @Mock private ScheduleClient scheduleClient;
  @Mock private ScheduleHandle handle;

  @Captor private ArgumentCaptor<Schedule> scheduleCaptor;
  @Captor private ArgumentCaptor<ScheduleOptions> optionsCaptor;

  @Captor
  private ArgumentCaptor<Functions.Func1<ScheduleUpdateInput, ScheduleUpdate>> updaterCaptor;

  private PlatformBackupScheduleInitializer initializer() {
    return new PlatformBackupScheduleInitializer(scheduleClient);
  }

  private Schedule createdSchedule() {
    verify(scheduleClient)
        .createSchedule(
            eq(PlatformBackupScheduleInitializer.SCHEDULE_ID),
            scheduleCaptor.capture(),
            optionsCaptor.capture());
    return scheduleCaptor.getValue();
  }

  private void scheduleAlreadyExists() {
    when(scheduleClient.createSchedule(any(), any(), any()))
        .thenThrow(new ScheduleAlreadyRunningException(new RuntimeException("already running")));
    when(scheduleClient.getHandle(PlatformBackupScheduleInitializer.SCHEDULE_ID))
        .thenReturn(handle);
  }

  private static ScheduleUpdateInput existing(final boolean paused) {
    Schedule schedule =
        Schedule.newBuilder()
            .setAction(
                ScheduleActionStartWorkflow.newBuilder()
                    .setWorkflowType("PlatformBackupWorkflow")
                    .setOptions(
                        WorkflowOptions.newBuilder()
                            .setWorkflowId("platform-backup")
                            .setTaskQueue("platform-backup")
                            .build())
                    .build())
            .setSpec(
                ScheduleSpec.newBuilder()
                    .setCalendars(
                        List.of(
                            ScheduleCalendarSpec.newBuilder()
                                .setHour(List.of(new ScheduleRange(2)))
                                .build()))
                    .build())
            .setState(ScheduleState.newBuilder().setPaused(paused).build())
            .build();
    return new ScheduleUpdateInput(
        new ScheduleDescription(
            PlatformBackupScheduleInitializer.SCHEDULE_ID,
            null, schedule, Map.of(), null, Map.of(), null));
  }

  @Test
  void createsTheScheduleAtTwoAmLondon() {
    initializer().run(null);

    Schedule schedule = createdSchedule();
    ScheduleCalendarSpec calendar = schedule.getSpec().getCalendars().get(0);
    assertThat(calendar.getHour()).extracting(ScheduleRange::getStart).containsExactly(2);
    assertThat(calendar.getMinutes()).extracting(ScheduleRange::getStart).containsExactly(0);
    assertThat(schedule.getSpec().getTimeZoneName()).isEqualTo("Europe/London");
  }

  @Test
  void targetsThePlatformBackupWorkflowAndQueue() {
    initializer().run(null);

    ScheduleActionStartWorkflow action =
        (ScheduleActionStartWorkflow) createdSchedule().getAction();
    assertThat(action.getWorkflowType()).isEqualTo("PlatformBackupWorkflow");
    assertThat(action.getOptions().getTaskQueue()).isEqualTo("platform-backup");
    assertThat(action.getOptions().getWorkflowId()).isEqualTo("platform-backup");
  }

  /**
   * A fresh enabled deployment protects the platform without a separate unpause step.
   */
  @Test
  void createsTheScheduleActiveWithoutTriggeringImmediately() {
    initializer().run(null);

    Schedule schedule = createdSchedule();
    assertThat(schedule.getState().isPaused()).isFalse();
    assertThat(optionsCaptor.getValue().isTriggerImmediately()).isFalse();
  }

  /**
   * A capture still running when the next one fires means one taking over 24 hours. Starting a
   * second would have both writing the same ClickHouse staging file.
   */
  @Test
  void skipsOverlappingRuns() {
    initializer().run(null);

    assertThat(createdSchedule().getPolicy().getOverlap())
        .isEqualTo(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP);
  }

  /**
   * The assertion that matters most on redeploy: an operator who unpaused the schedule must not
   * find it paused again after the next deploy, because nothing would report that the backup had
   * stopped.
   */
  @Test
  void keepsTheScheduleUnpausedOnUpdateWhenTheOperatorUnpausedIt() {
    scheduleAlreadyExists();

    initializer().run(null);

    verify(handle).update(updaterCaptor.capture());
    ScheduleUpdate update = updaterCaptor.getValue().apply(existing(false));
    assertThat(update.getSchedule().getState().isPaused()).isFalse();
  }

  @Test
  void keepsTheSchedulePausedOnUpdateWhenItWasPaused() {
    scheduleAlreadyExists();

    initializer().run(null);

    verify(handle).update(updaterCaptor.capture());
    ScheduleUpdate update = updaterCaptor.getValue().apply(existing(true));
    assertThat(update.getSchedule().getState().isPaused()).isTrue();
  }

  /** An update must still rewrite the parts this class owns, or config changes never land. */
  @Test
  void rewritesTheSpecOnUpdate() {
    scheduleAlreadyExists();

    initializer().run(null);

    verify(handle).update(updaterCaptor.capture());
    ScheduleUpdate update = updaterCaptor.getValue().apply(existing(false));
    assertThat(update.getSchedule().getSpec().getTimeZoneName()).isEqualTo("Europe/London");
    assertThat(update.getSchedule().getPolicy().getOverlap())
        .isEqualTo(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP);
  }
}
