package com.simonrowe.factory.ticketagent.config;

/** The ticket agent's Temporal task queue. */
public final class TicketAgentTaskQueues {

  /** Polled by {@code software-factory} only; see {@code application.yml}'s workflow packages. */
  public static final String TICKET_AGENT = "ticket-agent";

  private TicketAgentTaskQueues() {
  }
}
