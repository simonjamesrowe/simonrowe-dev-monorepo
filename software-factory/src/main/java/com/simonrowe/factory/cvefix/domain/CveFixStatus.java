package com.simonrowe.factory.cvefix.domain;

/** Terminal outcome of one CVE-fix run. */
public enum CveFixStatus {
  /** CI went green; the pull request is waiting for a human to merge. */
  COMPLETED,
  /** Dependency-Track reported nothing actionable. */
  NO_FINDINGS,
  /** A CVE pull request is already open, so this run did nothing. */
  SKIPPED_PR_OPEN,
  /** Findings existed but the agent could not produce a single bump. */
  NOTHING_FIXABLE,
  /**
   * CI never went green. Either the repair budget ran out, or {@code ci.maxWait} elapsed first;
   * the two cases are distinguished by {@link CveFixResult#detail()}, not by the status. The pull
   * request is left open either way, which makes every later run hit {@link #SKIPPED_PR_OPEN}.
   */
  CI_UNRESOLVED,
  /** The run failed for an operational reason: Dependency-Track down, git error, agent error. */
  FAILED
}
