package com.simonrowe.factory.testsupport;

import com.simonrowe.factory.ticketagent.config.TicketAgentProperties;

/** Ticket agent properties with every default, for tests that only care whether it is on. */
public final class TicketAgentTestProperties {

  private TicketAgentTestProperties() {
  }

  public static TicketAgentProperties of(final boolean enabled) {
    return of(enabled, null, null);
  }

  /** On a named repository, for tests whose fixtures use one. */
  public static TicketAgentProperties of(
      final boolean enabled, final String owner, final String repository) {
    return new TicketAgentProperties(
        enabled, owner, repository, null, null, null, 0, 0, null, null, null, null, null, null,
        null, null, null, null, 0, null, null);
  }
}
