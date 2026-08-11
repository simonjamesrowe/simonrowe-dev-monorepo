package com.simonrowe.factory.cvefix.domain;

/** Coarse progress phase, surfaced by the workflow's query method. */
public enum CveFixPhase {
  ACCEPTED, CHECKING_PR, FETCHING, PREPARING, PROPOSING, PUSHING, AWAITING_CI, REPAIRING,
  COMPLETED, SKIPPED, FAILED
}
