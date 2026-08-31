package com.simonrowe.factory.logwatch.api;

/**
 * Acknowledgement that a scan was started.
 *
 * @param workflowId the Temporal workflow id
 * @param runId the Temporal run id, which is also the run record's id
 * @param message a human-readable acknowledgement
 */
public record LogWatchScanAccepted(String workflowId, String runId, String message) {
}
