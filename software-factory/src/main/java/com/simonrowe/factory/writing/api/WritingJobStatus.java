package com.simonrowe.factory.writing.api;

/**
 * The answer to "where is my job".
 *
 * @param jobId the caller's job id
 * @param status the job's state
 * @param text the written text, present only when {@code status} is {@code COMPLETED}
 */
public record WritingJobStatus(String jobId, WritingJobState status, String text) {

  static WritingJobStatus of(final String jobId, final WritingJobState status) {
    return new WritingJobStatus(jobId, status, null);
  }
}
