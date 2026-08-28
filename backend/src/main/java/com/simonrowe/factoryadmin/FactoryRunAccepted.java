package com.simonrowe.factoryadmin;

/** Normalized identity returned when a durable factory workflow accepts work. */
public record FactoryRunAccepted(String workflowId, String runId, String detail) {
}
