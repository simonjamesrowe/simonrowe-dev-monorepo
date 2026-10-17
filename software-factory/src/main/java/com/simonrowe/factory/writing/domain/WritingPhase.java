package com.simonrowe.factory.writing.domain;

/** Where a writing job has got to, as reported by its {@code progress} query. */
public enum WritingPhase {
  ACCEPTED,
  WRITING,
  COMPLETED,
  FAILED
}
