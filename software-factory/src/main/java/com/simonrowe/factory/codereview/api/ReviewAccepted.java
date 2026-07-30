package com.simonrowe.factory.codereview.api;

/** Asynchronous trigger response. */
public record ReviewAccepted(String workflowId, boolean started) {
}
