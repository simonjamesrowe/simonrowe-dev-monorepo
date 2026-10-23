package com.simonrowe.factory.staticanalysis.api;

/**
 * Acknowledgement that a scan was started.
 *
 * <p>Field names match {@code LogWatchScanAccepted} and {@code CveScanAccepted} deliberately: the
 * backend proxies every module's acknowledgement through one {@code RunAcceptedWire} record, so a
 * differently-named field here would deserialise as null.
 *
 * @param workflowId the Temporal workflow id
 * @param runId the Temporal run id, which is also the run record's id
 * @param detail a human-readable acknowledgement
 */
public record StaticAnalysisScanAccepted(String workflowId, String runId, String detail) {
}
