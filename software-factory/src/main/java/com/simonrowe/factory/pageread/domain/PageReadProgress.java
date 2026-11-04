package com.simonrowe.factory.pageread.domain;

/**
 * Queryable progress snapshot, in every factory module's {@code {phase, detail, <one field>}}
 * shape, so {@code GET /api/factory/runs/{id}} can follow a read like any other run.
 *
 * @param phase where the read has got to
 * @param detail free-text diagnostics
 * @param outcome how the read ended, once it has
 */
public record PageReadProgress(PageReadPhase phase, String detail, PageReadOutcome outcome) {

  /** The state a read reports before its activity has been scheduled. */
  public static PageReadProgress accepted() {
    return new PageReadProgress(PageReadPhase.ACCEPTED, "Accepted", null);
  }
}
