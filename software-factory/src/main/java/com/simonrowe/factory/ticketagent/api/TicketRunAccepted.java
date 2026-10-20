package com.simonrowe.factory.ticketagent.api;

/**
 * Acknowledgement that a run started.
 *
 * <p>Field names match the other modules' acknowledgements, because the backend proxies them all
 * through one {@code RunAcceptedWire}: a differently-named field would deserialise as null.
 *
 * @param workflowId the Temporal workflow id the console follows
 * @param runId unused here, always null; the workflow id is the run's identity
 * @param detail a human-readable acknowledgement
 */
public record TicketRunAccepted(String workflowId, String runId, String detail) {
}
