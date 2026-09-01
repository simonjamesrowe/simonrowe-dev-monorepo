package com.simonrowe.factory.logwatch.api;

/**
 * Acknowledgement that a scan was started.
 *
 * <p>Field names match {@code CveScanAccepted} and {@code PlatformBackupAccepted}
 * deliberately: the backend proxies all three through one {@code RunAcceptedWire}
 * record, so a differently-named field here would deserialise as null and the console
 * would show no acknowledgement at all.
 *
 * @param workflowId the Temporal workflow id
 * @param runId the Temporal run id, which is also the run record's id
 * @param detail a human-readable acknowledgement
 */
public record LogWatchScanAccepted(String workflowId, String runId, String detail) {
}
