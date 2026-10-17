package com.simonrowe.factory.writing.domain;

/**
 * One piece of text to write.
 *
 * <p>Deliberately generic: the caller owns the whole prompt, and the factory knows nothing about
 * release notes or digests. That is what lets a second job use the queue with no factory change.
 *
 * @param jobId the caller's idempotency key, also the tail of the workflow id
 * @param prompt the complete instruction, which may contain untrusted text
 */
public record WritingRequest(String jobId, String prompt) {
}
