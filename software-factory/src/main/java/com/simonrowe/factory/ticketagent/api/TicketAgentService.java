package com.simonrowe.factory.ticketagent.api;

import com.simonrowe.factory.ticketagent.config.TicketAgentProperties;
import com.simonrowe.factory.ticketagent.config.TicketAgentTaskQueues;
import com.simonrowe.factory.ticketagent.domain.ChecksSignal;
import com.simonrowe.factory.ticketagent.domain.TicketAgentRequest;
import com.simonrowe.factory.ticketagent.schedule.TicketAgentScheduleInitializer;
import com.simonrowe.factory.ticketagent.workflow.TicketAgentWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.ScheduleClient;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Starts, signals and stops ticket runs, keeping callers Temporal-agnostic.
 *
 * <p>One workflow id per ticket, {@code ticket-agent-SIM-107}, so a ticket can never have two live
 * runs however many doors ask (the intake, the console, a redelivered webhook). Dry runs use their
 * own id so a preview never blocks, or is blocked by, the real thing.
 */
@Service
public class TicketAgentService {

  private static final Logger LOGGER = LoggerFactory.getLogger(TicketAgentService.class);
  private static final String PREFIX = "ticket-agent-";
  private static final String DRY_RUN_PREFIX = "ticket-agent-dry-run-";

  /** Longer than any Linear team key plus number; anything longer is not an identifier. */
  private static final int MAX_IDENTIFIER_LENGTH = 32;

  private final WorkflowClient workflowClient;
  private final ScheduleClient scheduleClient;
  private final TicketAgentProperties properties;

  public TicketAgentService(
      final WorkflowClient workflowClient,
      final ScheduleClient scheduleClient,
      final TicketAgentProperties properties) {
    this.workflowClient = workflowClient;
    this.scheduleClient = scheduleClient;
    this.properties = properties;
  }

  /**
   * Starts a run unless one is already live for the ticket.
   *
   * @return the workflow id and whether this call started it
   */
  public TicketAgentAccepted start(final TicketAgentRequest request) {
    String workflowId = workflowId(request.identifier(), request.dryRun());
    TicketAgentWorkflow workflow =
        workflowClient.newWorkflowStub(
            TicketAgentWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TicketAgentTaskQueues.TICKET_AGENT)
                .setWorkflowId(workflowId)
                // A finished run does not block the next: a ticket handed back and labelled again
                // is a new request. A live one does, which is the whole point of the fixed id.
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .build());
    try {
      WorkflowClient.start(workflow::run, request);
      return new TicketAgentAccepted(workflowId, true);
    } catch (WorkflowExecutionAlreadyStarted exception) {
      return new TicketAgentAccepted(workflowId, false);
    }
  }

  /** Signals CI's result to the run that owns a branch. A branch with no live run is ignored. */
  public void signalChecks(final String branch, final ChecksSignal signal) {
    identifierFromBranch(branch)
        .ifPresent(identifier -> signal(identifier, stub -> stub.checksCompleted(signal)));
  }

  /** Signals that the agent's pull request closed. */
  public void signalClosed(final String branch, final boolean merged) {
    identifierFromBranch(branch)
        .ifPresent(identifier -> signal(identifier, stub -> stub.pullRequestClosed(merged)));
  }

  /**
   * Asks a live run to stop and hand the ticket back.
   *
   * @return false when the ticket has no live run
   */
  public boolean stop(final String identifier, final String reason) {
    return signal(identifier, stub -> stub.stop(reason));
  }

  /** Pauses or resumes the intake schedule. Live runs are unaffected either way. */
  public void setIntakePaused(final boolean paused) {
    var handle = scheduleClient.getHandle(TicketAgentScheduleInitializer.SCHEDULE_ID);
    if (paused) {
      handle.pause("Paused from the software factory console");
    } else {
      handle.unpause("Resumed from the software factory console");
    }
  }

  /** The agent's branch for a ticket: {@code agent/sim-107}. */
  public String branchFor(final String identifier) {
    return properties.branchPrefix() + identifier.toLowerCase(Locale.ROOT);
  }

  /**
   * The ticket a branch belongs to, or empty when it is not an agent branch.
   *
   * @param branch a head branch name from a webhook
   * @return {@code SIM-107} for {@code agent/sim-107}
   */
  public Optional<String> identifierFromBranch(final String branch) {
    if (branch == null || !branch.startsWith(properties.branchPrefix())) {
      return Optional.empty();
    }
    String identifier =
        branch.substring(properties.branchPrefix().length()).toUpperCase(Locale.ROOT);
    return isIdentifier(identifier) ? Optional.of(identifier) : Optional.empty();
  }

  /**
   * Whether a string is a Linear identifier: letters, a dash, digits.
   *
   * <p>Checked character by character rather than with a pattern, and length-capped first: it is
   * request input, and it becomes part of a workflow id.
   */
  public static boolean isIdentifier(final String value) {
    if (value == null || value.isEmpty() || value.length() > MAX_IDENTIFIER_LENGTH) {
      return false;
    }
    int dash = value.indexOf('-');
    if (dash <= 0 || dash == value.length() - 1 || value.indexOf('-', dash + 1) >= 0) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      boolean letter = character >= 'A' && character <= 'Z';
      boolean digit = character >= '0' && character <= '9';
      boolean valid = index < dash ? letter || (index > 0 && digit) : index == dash || digit;
      if (!valid) {
        return false;
      }
    }
    return true;
  }

  static String workflowId(final String identifier, final boolean dryRun) {
    return (dryRun ? DRY_RUN_PREFIX : PREFIX) + identifier;
  }

  private boolean signal(
      final String identifier, final Consumer<TicketAgentWorkflow> action) {
    try {
      action.accept(
          workflowClient.newWorkflowStub(TicketAgentWorkflow.class, workflowId(identifier, false)));
      return true;
    } catch (WorkflowNotFoundException exception) {
      LOGGER.info("No live ticket agent run for {}; signal dropped", identifier);
      return false;
    }
  }
}
