package com.simonrowe.factory.ticketagent.domain;

/**
 * Hand one ticket to the agent.
 *
 * @param identifier the Linear identifier, e.g. {@code SIM-107}
 * @param dryRun run the attempt and post the would-be diff to the ticket, pushing nothing and
 *     changing no ticket state
 * @param trigger who asked: {@code intake} (the schedule) or {@code console}
 */
public record TicketAgentRequest(String identifier, boolean dryRun, String trigger) {

  public static final String TRIGGER_INTAKE = "intake";
  public static final String TRIGGER_CONSOLE = "console";
}
