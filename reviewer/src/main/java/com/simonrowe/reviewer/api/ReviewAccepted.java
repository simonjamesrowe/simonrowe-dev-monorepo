package com.simonrowe.reviewer.api;

/** Asynchronous trigger response. */
public record ReviewAccepted(String workflowId, boolean started) {
}
