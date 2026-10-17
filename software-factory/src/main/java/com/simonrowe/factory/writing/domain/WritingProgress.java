package com.simonrowe.factory.writing.domain;

/**
 * Queryable progress snapshot, in every factory module's {@code {phase, detail, <one field>}}
 * shape, so {@code GET /api/factory/runs/{id}} can follow a writing job like any other run.
 *
 * @param phase where the job has got to
 * @param detail free-text diagnostics
 * @param characters the length of the written text once there is one; null before
 */
public record WritingProgress(WritingPhase phase, String detail, Integer characters) {

  /** The state a job reports before its activity has been scheduled. */
  public static WritingProgress accepted() {
    return new WritingProgress(WritingPhase.ACCEPTED, "Accepted", null);
  }
}
