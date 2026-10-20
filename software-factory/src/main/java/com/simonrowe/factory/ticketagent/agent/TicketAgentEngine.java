package com.simonrowe.factory.ticketagent.agent;

import java.nio.file.Path;
import java.util.function.Consumer;

/** Edits a checkout to resolve a ticket. The only part of the module that runs a model. */
public interface TicketAgentEngine {

  /**
   * Runs one attempt.
   *
   * @param checkout the repository checkout to edit in place
   * @param brief the prompt
   * @param heartbeat progress for Temporal
   * @return the agent's account of what it did
   */
  AgentEdit attempt(Path checkout, String brief, Consumer<String> heartbeat);
}
