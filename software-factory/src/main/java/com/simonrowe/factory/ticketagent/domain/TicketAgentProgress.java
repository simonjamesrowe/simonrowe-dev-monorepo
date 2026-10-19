package com.simonrowe.factory.ticketagent.domain;

/**
 * The {@code progress} query every factory workflow answers: phase and detail, plus this module's
 * own fields.
 *
 * @param phase where the run is
 * @param detail one line for the run banner and the drawer
 * @param attempt the agent run in progress or last finished, 0 before the first
 * @param pullRequestUrl the pull request once one exists, otherwise null
 */
public record TicketAgentProgress(
    TicketAgentPhase phase, String detail, int attempt, String pullRequestUrl) {

  public static TicketAgentProgress accepted() {
    return new TicketAgentProgress(TicketAgentPhase.ACCEPTED, "Accepted", 0, null);
  }
}
