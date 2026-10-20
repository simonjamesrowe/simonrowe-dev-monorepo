package com.simonrowe.factory.ticketagent.schedule;

import com.simonrowe.factory.ticketagent.config.TicketAgentTaskQueues;
import com.simonrowe.factory.ticketagent.workflow.TicketAgentIntakeWorkflow;
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
 * Declares the five-minute intake schedule in code, so a deploy reconciles it.
 *
 * <p>Created <strong>paused</strong>, like {@code cve-fix-daily} and unlike log watch, because
 * this one leads to pull requests. Switching the module on starts nothing by itself: an operator
 * dry-runs a few tickets from the console, then resumes the intake there. A reconcile carries the
 * server's current paused flag forward, so a restart never re-pauses an intake someone resumed,
 * or resumes one someone paused.
 */
@Component
@ConditionalOnProperty(name = "factory.ticket-agent.enabled", havingValue = "true")
public class TicketAgentScheduleInitializer implements ApplicationRunner {

  /** Identifier of the schedule, as it appears in the Temporal UI and the console. */
  public static final String SCHEDULE_ID = "ticket-agent-intake";

  static final String WORKFLOW_ID = "ticket-agent-intake";

  /** Labelled work waits at most this long; latency here costs nothing that matters. */
  static final Duration INTERVAL = Duration.ofMinutes(5);

  /** One pass is a Linear read and a few starts; ten minutes is generous. */
  static final Duration EXECUTION_TIMEOUT = Duration.ofMinutes(10);

  private static final Logger log = LoggerFactory.getLogger(TicketAgentScheduleInitializer.class);

  private final ScheduleClient scheduleClient;

  public TicketAgentScheduleInitializer(final ScheduleClient scheduleClient) {
    this.scheduleClient = scheduleClient;
  }

  @Override
  public void run(final ApplicationArguments args) {
    try {
      scheduleClient.createSchedule(
          SCHEDULE_ID, schedule(true), ScheduleOptions.newBuilder().build());
      log.info("Created paused Temporal schedule {}", SCHEDULE_ID);
    } catch (ScheduleAlreadyRunningException alreadyRunning) {
      scheduleClient
          .getHandle(SCHEDULE_ID)
          .update(input -> new ScheduleUpdate(schedule(input.getDescription().getSchedule())));
      log.info("Updated existing Temporal schedule {}", SCHEDULE_ID);
    }
  }

  private Schedule schedule(final Schedule existing) {
    ScheduleState state = existing == null ? null : existing.getState();
    return schedule(state == null || state.isPaused());
  }

  static Schedule schedule(final boolean paused) {
    return Schedule.newBuilder()
        .setAction(
            ScheduleActionStartWorkflow.newBuilder()
                .setWorkflowType(TicketAgentIntakeWorkflow.class)
                .setOptions(
                    WorkflowOptions.newBuilder()
                        .setWorkflowId(WORKFLOW_ID)
                        .setTaskQueue(TicketAgentTaskQueues.TICKET_AGENT)
                        .setWorkflowExecutionTimeout(EXECUTION_TIMEOUT)
                        .build())
                .build())
        .setSpec(
            ScheduleSpec.newBuilder()
                .setIntervals(List.of(new ScheduleIntervalSpec(INTERVAL)))
                .build())
        // Two passes at once would both see a ticket as waiting; the fixed per-ticket workflow id
        // makes that harmless, and skipping makes it not happen.
        .setPolicy(
            SchedulePolicy.newBuilder()
                .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                .build())
        .setState(ScheduleState.newBuilder().setPaused(paused).build())
        .build();
  }
}
