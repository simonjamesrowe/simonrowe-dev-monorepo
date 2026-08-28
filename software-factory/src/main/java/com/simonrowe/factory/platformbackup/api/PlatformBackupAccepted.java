package com.simonrowe.factory.platformbackup.api;

/** Response after a manual platform capture is durably accepted. */
public record PlatformBackupAccepted(String workflowId, String runId, String detail) {
}
