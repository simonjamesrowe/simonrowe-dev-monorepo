package com.simonrowe.factory.cvefix.domain;

/** Queryable progress snapshot. {@code count} is phase-dependent and may be null. */
public record CveFixProgress(CveFixPhase phase, String detail, Integer count) {

  /** The state a run reports before its first activity completes. */
  public static CveFixProgress accepted() {
    return new CveFixProgress(CveFixPhase.ACCEPTED, "Accepted", null);
  }
}
