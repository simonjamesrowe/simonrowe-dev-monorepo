package com.simonrowe.factory.ticketagent.api;

/**
 * A run request's answer.
 *
 * @param workflowId the run's workflow id, which the console follows
 * @param started false when a run for the ticket was already live
 */
public record TicketAgentAccepted(String workflowId, boolean started) {
}
