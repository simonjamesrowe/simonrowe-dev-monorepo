package com.simonrowe.factory.pageread.domain;

/**
 * How a read ended, as the page-reader's {@code ReadOutcome}. Keep the two in step: a value
 * the page-reader returns that is missing here fails the workflow task, not just the read.
 */
public enum PageReadOutcome {
  /** The page's article text. */
  READ,
  /** A bot challenge was still showing when the wait ran out. */
  BLOCKED,
  /** The publisher answered with an error status, or the egress proxy refused the address. */
  REFUSED,
  /** The page loaded but had no readable text. */
  EMPTY
}
