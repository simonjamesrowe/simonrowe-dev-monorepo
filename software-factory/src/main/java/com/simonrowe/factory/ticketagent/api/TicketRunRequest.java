package com.simonrowe.factory.ticketagent.api;

/**
 * A console request to hand a ticket to the agent.
 *
 * @param ticket {@code SIM-123}, or the ticket's Linear URL
 * @param dryRun post the would-be diff to the ticket and push nothing
 */
public record TicketRunRequest(String ticket, Boolean dryRun) {

  public boolean isDryRun() {
    return Boolean.TRUE.equals(dryRun);
  }
}
