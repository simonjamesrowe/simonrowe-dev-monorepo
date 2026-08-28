package com.simonrowe.factory.cvefix.api;

/** Identity of an accepted manual vulnerability scan. */
public record CveScanAccepted(String workflowId, String runId, String detail) {
}
