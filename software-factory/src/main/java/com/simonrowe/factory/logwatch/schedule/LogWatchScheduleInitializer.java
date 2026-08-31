package com.simonrowe.factory.logwatch.schedule;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.logwatch.config.LogWatchTaskQueues;
import com.simonrowe.factory.logwatch.domain.LogWatchRequest;
import com.simonrowe.factory.logwatch.domain.Trigger;
import com.simonrowe.factory.logwatch.workflow.LogWatchWorkflow;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleIntervalSpec;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.client.schedules.ScheduleState;
import io.temporal.client.schedules.ScheduleUpdate;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Declares the 24-hour log-watch schedule in code, so a deploy reconciles it.
 *
 * <p>Two properties of this class are deliberate and load-bearing, both copied from
 * {@code CveFixScheduleInitializer}:
 *
 * <ul>
 *   <li>It reconciles rather than creates: an existing schedule is updated to match the parts this
 *       code owns, but carries the server's current paused flag forward, because re-pausing a
 *       schedule the operator unpaused would silently stop the feature on every restart.
 *   <li>{@code @WorkflowImpl} classes are instantiated by the Temporal SDK and cannot inject Spring
 *       properties. This class holds the beans, so configured values travel with the scheduled
 *       request.
 * </ul>
 *
 * <p>Unlike the CVE schedule, this one is created <strong>active</strong>. A scan that only reads
 * and files cannot damage anything, and the module's value is entirely in it running unattended —
 * a paused-by-default observability check is one nobody remembers to turn on.
 */
@Component
@ConditionalOnProperty(name = "factory.logwatch.enabled", havingValue = "true")
public class LogWatchScheduleInitializer implements ApplicationRunner {

  /** Identifier of the schedule, as it appears in the Temporal UI. */
  public static final String SCHEDULE_ID = "logwatch-daily";

  /** Base workflow id of each scheduled run; Temporal appends the scheduled time. */
  static final String WORKFLOW_ID = "logwatch";

  /** How often the schedule fires. */
  static final Duration INTERVAL = Duration.ofHours(24);

  private static final Logger log = LoggerFactory.getLogger(LogWatchScheduleInitializer.class);

  private final ScheduleClient scheduleClient;
  private final LinearProperties linearProperties;

  /**
   * Creates the initializer.
   *
   * @param scheduleClient the Temporal schedule client, auto-configured by the Temporal starter
   * @param linearProperties the bound {@code factory.linear} configuration, whose enabled flag is
   *     copied into the scheduled request because a workflow cannot read it itself
   */
  public LogWatchScheduleInitializer(
      final ScheduleClient scheduleClient, final LinearProperties linearProperties) {
    this.scheduleClient = scheduleClient;
    this.linearProperties = linearProperties;
  }

  /**
   * Creates the schedule, or updates it when it already exists.
   *
   * @param args the application arguments, unused
   */
  @Override
  public void run(final ApplicationArguments args) {
    try {
      scheduleClient.createSchedule(
          SCHEDULE_ID, schedule(false), ScheduleOptions.newBuilder().build());
      log.info("Created active Temporal schedule {}", SCHEDULE_ID);
    } catch (ScheduleAlreadyRunningException alreadyRunning) {
      scheduleClient
          .getHandle(SCHEDULE_ID)
          .update(input -> new ScheduleUpdate(schedule(input.getDescription().getSchedule())));
      log.info("Updated existing Temporal schedule {}", SCHEDULE_ID);
    }
  }

  private Schedule schedule(final Schedule existing) {
    ScheduleState state = existing == null ? null : existing.getState();
    return schedule(state != null && state.isPaused());
  }

  private Schedule schedule(final boolean paused) {
    return Schedule.newBuilder()
        .setAction(
            ScheduleActionStartWorkflow.newBuilder()
                .setWorkflowType(LogWatchWorkflow.class)
                .setOptions(
                    WorkflowOptions.newBuilder()
                        .setWorkflowId(WORKFLOW_ID)
                        .setTaskQueue(LogWatchTaskQueues.LOG_WATCH)
                        .build())
                // Null window: the workflow resolves it from its own clock, so a scheduled run
                // always covers the period ending at the moment it actually starts rather than a
                // window baked in when the schedule was declared.
                .setArguments(
                    new LogWatchRequest(
                        null, null, Trigger.SCHEDULE, false, linearProperties.enabled()))
                .build())
        .setSpec(
            ScheduleSpec.newBuilder()
                .setIntervals(List.of(new ScheduleIntervalSpec(INTERVAL)))
                .build())
        // A slow scan never overlaps the next one: two concurrent scans of overlapping windows
        // would both see the same signatures and race to file them.
        .setPolicy(
            SchedulePolicy.newBuilder()
                .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                .build())
        .setState(ScheduleState.newBuilder().setPaused(paused).build())
        .build();
  }
}
