package com.simonrowe.factory.writing.api;

/**
 * Body of {@code POST /api/writing/jobs}. Validated by the controller, not by bean validation,
 * because the prompt cap is configuration.
 *
 * @param jobId the caller's idempotency key
 * @param prompt the complete instruction
 */
public record WritingJobRequest(String jobId, String prompt) {
}
