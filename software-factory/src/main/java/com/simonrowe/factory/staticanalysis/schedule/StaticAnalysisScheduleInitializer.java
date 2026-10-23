package com.simonrowe.factory.staticanalysis.schedule;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.staticanalysis.config.StaticAnalysisProperties;
import com.simonrowe.factory.staticanalysis.config.StaticAnalysisTaskQueues;
import com.simonrowe.factory.staticanalysis.domain.StaticAnalysisRequest;
import com.simonrowe.factory.staticanalysis.domain.Trigger;
import com.simonrowe.factory.staticanalysis.workflow.StaticAnalysisWorkflow;
import com.simonrowe.factory.temporal.ScheduledRuns;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
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
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Declares the daily static-analysis schedule in code, so a deploy reconciles it.
 *
 * <p>Copied from {@code LogWatchScheduleInitializer}, including its two load-bearing properties:
 * it reconciles rather than creates, carrying the server's paused flag forward so a restart never
 * re-pauses a schedule the operator unpaused; and it is created <strong>active</strong>, because a
 * scan that only reads and files cannot damage anything and a paused-by-default check is one
 * nobody turns on.
 *
 * <p>Daily rather than after every analysis, deliberately. SonarCloud's state accumulates, so one
 * read a day covers every merge since the last, and a ticket a night per problem is the pace a
 * person approving tickets for the agent can keep up with.
 */
@Component
@ConditionalOnProperty(name = "factory.staticanalysis.enabled", havingValue = "true")
public class StaticAnalysisScheduleInitializer implements ApplicationRunner {

  /** Identifier of the schedule, as it appears in the Temporal UI. */
  public static final String SCHEDULE_ID = "static-analysis-daily";

  /** Base workflow id of each scheduled run; Temporal appends the scheduled time. */
  static final String WORKFLOW_ID = "static-analysis";

  /** How often the schedule fires. */
  static final Duration INTERVAL = Duration.ofHours(24);

  /**
   * 03:00 UTC: clear of log watch at midnight and the platform backup in the small hours, and
   * after the evening's merges have had their {@code main} analysis.
   */
  static final Duration OFFSET = Duration.ofHours(3);

  private static final Logger log =
      LoggerFactory.getLogger(StaticAnalysisScheduleInitializer.class);

  private final ScheduleClient scheduleClient;
  private final LinearProperties linearProperties;
  private final StaticAnalysisProperties properties;

  /**
   * Creates the initializer.
   *
   * @param scheduleClient the Temporal schedule client
   * @param linearProperties the Linear sink's configuration, whose enabled flag travels on the
   *     request because a workflow cannot read it
   * @param properties the module's configuration, whose sweep settings travel on the request
   */
  public StaticAnalysisScheduleInitializer(
      final ScheduleClient scheduleClient,
      final LinearProperties linearProperties,
      final StaticAnalysisProperties properties) {
    this.scheduleClient = scheduleClient;
    this.linearProperties = linearProperties;
    this.properties = properties;
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
                .setWorkflowType(StaticAnalysisWorkflow.class)
                .setOptions(
                    WorkflowOptions.newBuilder()
                        .setWorkflowId(WORKFLOW_ID)
                        .setTaskQueue(StaticAnalysisTaskQueues.STATIC_ANALYSIS)
                        .setWorkflowExecutionTimeout(ScheduledRuns.EXECUTION_TIMEOUT)
                        .build())
                .setArguments(
                    new StaticAnalysisRequest(
                        Trigger.SCHEDULED,
                        false,
                        linearProperties.enabled(),
                        properties.resolveWhenClear(),
                        properties.resolveAfter()))
                .build())
        .setSpec(
            ScheduleSpec.newBuilder()
                .setIntervals(List.of(new ScheduleIntervalSpec(INTERVAL, OFFSET)))
                .build())
        // Two concurrent scans would read the same analysis and race to file the same groups.
        .setPolicy(
            SchedulePolicy.newBuilder()
                .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                .build())
        .setState(ScheduleState.newBuilder().setPaused(paused).build())
        .build();
  }
}
