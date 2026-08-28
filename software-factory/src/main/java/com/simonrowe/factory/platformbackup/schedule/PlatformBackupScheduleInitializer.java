package com.simonrowe.factory.platformbackup.schedule;

import com.simonrowe.factory.platformbackup.config.PlatformBackupTaskQueues;
import com.simonrowe.factory.platformbackup.workflow.PlatformBackupWorkflow;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleCalendarSpec;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.client.schedules.ScheduleRange;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.client.schedules.ScheduleState;
import io.temporal.client.schedules.ScheduleUpdate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Declares the nightly platform-backup Temporal schedule at startup, so a deploy reconciles it the
 * way {@code docker compose up} reconciles containers rather than leaving it as hand-made server
 * state nothing tracks. Modelled on {@code CveFixScheduleInitializer}, including the two
 * properties that matter:
 *
 * <ul>
 *   <li><strong>Idempotent.</strong> It runs on every restart, creating the schedule on first boot
 *       and updating it afterwards. An update rewrites the action, spec and policy — the parts this
 *       code owns — but carries the server's current paused flag forward, because re-pausing a
 *       schedule the operator unpaused would silently stop the backup on every deploy.
 *   <li><strong>Created active.</strong> Enabling backup means the first nightly capture runs
 *       without a second unpause step; an existing operator pause is still preserved.
 * </ul>
 *
 * <p>A calendar spec, not an interval: 02:00 Europe/London must stay 02:00 across a restart, and a
 * 24-hour interval would drift to whenever the schedule was last created. The 02:00 slot is four
 * hours clear of the 22:00 application backup — those two no longer share a mutex, but they do
 * share four CPU cores and one uplink.
 */
@Component
@ConditionalOnProperty(name = "factory.platform-backup.enabled", havingValue = "true")
public class PlatformBackupScheduleInitializer implements ApplicationRunner {

  /** Identifier of the schedule, as it appears in the Temporal UI. */
  public static final String SCHEDULE_ID = "platform-backup-nightly";

  /** Base workflow id of each scheduled run; Temporal appends the scheduled time. */
  static final String WORKFLOW_ID = "platform-backup";

  static final int HOUR = 2;
  static final String TIME_ZONE = "Europe/London";

  private static final Logger LOG =
      LoggerFactory.getLogger(PlatformBackupScheduleInitializer.class);

  private final ScheduleClient scheduleClient;

  public PlatformBackupScheduleInitializer(final ScheduleClient scheduleClient) {
    this.scheduleClient = scheduleClient;
  }

  @Override
  public void run(final ApplicationArguments args) {
    try {
      scheduleClient.createSchedule(
          SCHEDULE_ID, schedule(false), ScheduleOptions.newBuilder().build());
      LOG.info("Created active Temporal schedule {}", SCHEDULE_ID);
    } catch (ScheduleAlreadyRunningException alreadyRunning) {
      scheduleClient
          .getHandle(SCHEDULE_ID)
          .update(input -> new ScheduleUpdate(schedule(input.getDescription().getSchedule())));
      LOG.info("Updated existing Temporal schedule {}", SCHEDULE_ID);
    }
  }

  /** Rebuilds the schedule while keeping whatever paused state the server currently holds. */
  private Schedule schedule(final Schedule existing) {
    ScheduleState state = existing == null ? null : existing.getState();
    return schedule(state == null || state.isPaused());
  }

  private Schedule schedule(final boolean paused) {
    return Schedule.newBuilder()
        .setAction(
            ScheduleActionStartWorkflow.newBuilder()
                .setWorkflowType(PlatformBackupWorkflow.class)
                .setOptions(
                    WorkflowOptions.newBuilder()
                        .setWorkflowId(WORKFLOW_ID)
                        .setTaskQueue(PlatformBackupTaskQueues.PLATFORM_BACKUP)
                        .build())
                .setArguments(false)
                .build())
        .setSpec(
            ScheduleSpec.newBuilder()
                .setCalendars(
                    List.of(
                        ScheduleCalendarSpec.newBuilder()
                            .setHour(List.of(new ScheduleRange(HOUR)))
                            .setMinutes(List.of(new ScheduleRange(0)))
                            .build()))
                .setTimeZoneName(TIME_ZONE)
                .build())
        // SKIP: a capture still running at the next fire is a capture taking over 24 hours, and
        // starting a second one would have both writing the same ClickHouse staging file.
        .setPolicy(
            SchedulePolicy.newBuilder()
                .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                .build())
        .setState(ScheduleState.newBuilder().setPaused(paused).build())
        .build();
  }
}
