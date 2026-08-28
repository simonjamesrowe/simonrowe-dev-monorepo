package com.simonrowe.factory.cvefix.schedule;

import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.cvefix.config.CveFixTaskQueues;
import com.simonrowe.factory.cvefix.domain.CveFixRequest;
import com.simonrowe.factory.cvefix.workflow.CveFixWorkflow;
import com.simonrowe.factory.linear.config.LinearProperties;
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
 * Declares the daily CVE-fix Temporal schedule at startup, so a deploy reconciles it the way
 * {@code docker compose up} reconciles containers rather than leaving it as hand-made server state
 * nothing tracks.
 *
 * <p>Two properties of this class are deliberate and load-bearing:
 *
 * <ul>
 *   <li><strong>It is idempotent.</strong> It runs on every restart, so it creates the schedule on
 *       first boot and updates it afterwards. An update rewrites the action, spec and policy — the
 *       parts this code owns — but carries the server's current paused flag forward, because
 *       re-pausing a schedule the operator unpaused would silently stop the feature on every
 *       deploy.
 *   <li><strong>The first version is active.</strong> The workflow is issue-only, so enabling the
 *       flag makes the daily ownership report effective without a second operational step.
 * </ul>
 *
 * <p>The scheduled request carries the CI settings rather than the workflow reading them, because
 * {@code @WorkflowImpl} classes are instantiated by the Temporal SDK and cannot inject
 * {@link CveFixProperties}. This class holds the bean, so the configured values travel with the
 * request.
 *
 * <p>Gated on {@code factory.cvefix.enabled} exactly as {@code CveFixIndexInitializer} is: with the
 * feature off no Temporal call is made at all, so an unreachable or empty schedule service cannot
 * fail the application context and take the GitHub webhook receiver and the {@code code-review}
 * worker down with it.
 */
@Component
@ConditionalOnProperty(name = "factory.cvefix.enabled", havingValue = "true")
public class CveFixScheduleInitializer implements ApplicationRunner {

  /** Identifier of the schedule, as it appears in the Temporal UI. */
  public static final String SCHEDULE_ID = "cve-fix-daily";

  /** Base workflow id of each scheduled run; Temporal appends the scheduled time. */
  static final String WORKFLOW_ID = "cve-fix";

  /** How often the schedule fires. */
  static final Duration INTERVAL = Duration.ofHours(24);

  private static final Logger log = LoggerFactory.getLogger(CveFixScheduleInitializer.class);

  private final ScheduleClient scheduleClient;
  private final CveFixProperties properties;
  private final LinearProperties linearProperties;

  /**
   * Creates the initializer.
   *
   * @param scheduleClient the Temporal schedule client, auto-configured by the Temporal starter
   * @param properties the bound {@code factory.cvefix} configuration, whose CI settings are copied
   *     into the scheduled request
   * @param linearProperties the bound {@code factory.linear} configuration, whose enabled flag is
   *     copied into the scheduled request for the same reason
   */
  public CveFixScheduleInitializer(
      final ScheduleClient scheduleClient,
      final CveFixProperties properties,
      final LinearProperties linearProperties) {
    this.scheduleClient = scheduleClient;
    this.properties = properties;
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

  /**
   * Rebuilds the schedule while keeping whatever paused state the server currently holds.
   *
   * @param existing the schedule Temporal already has
   * @return the reconciled schedule
   */
  private Schedule schedule(final Schedule existing) {
    ScheduleState state = existing == null ? null : existing.getState();
    return schedule(state == null || state.isPaused());
  }

  /**
   * Builds the schedule.
   *
   * @param paused whether the schedule should be paused
   * @return the schedule to create or update
   */
  private Schedule schedule(final boolean paused) {
    return Schedule.newBuilder()
        .setAction(
            ScheduleActionStartWorkflow.newBuilder()
                .setWorkflowType(CveFixWorkflow.class)
                .setOptions(
                    WorkflowOptions.newBuilder()
                        .setWorkflowId(WORKFLOW_ID)
                        .setTaskQueue(CveFixTaskQueues.CVE_FIX)
                        .build())
                .setArguments(
                    new CveFixRequest(
                        false,
                        properties.ci().pollInterval(),
                        properties.ci().repairBudget(),
                        properties.ci().maxWait(),
                        linearProperties.enabled()))
                .build())
        .setSpec(
            ScheduleSpec.newBuilder().setIntervals(List.of(new ScheduleIntervalSpec(INTERVAL)))
                .build())
        // A delayed scan never overlaps the next daily snapshot.
        .setPolicy(
            SchedulePolicy.newBuilder()
                .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                .build())
        .setState(ScheduleState.newBuilder().setPaused(paused).build())
        .build();
  }
}
