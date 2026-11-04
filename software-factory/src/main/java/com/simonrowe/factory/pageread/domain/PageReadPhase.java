package com.simonrowe.factory.pageread.domain;

/** Where a page read has got to, as reported by its {@code progress} query. */
public enum PageReadPhase {
  ACCEPTED,
  READING,
  COMPLETED,
  FAILED
}
