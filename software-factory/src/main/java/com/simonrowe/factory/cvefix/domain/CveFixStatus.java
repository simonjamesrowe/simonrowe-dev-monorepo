package com.simonrowe.factory.cvefix.domain;

/** Terminal outcome of one CVE-fix run. */
public enum CveFixStatus {
  /** CI went green; the pull request is waiting for a human to merge. */
  COMPLETED,
  /**
   * A dry run stopped before opening the pull request. Distinct from {@link #COMPLETED} because a
   * dry run is <em>not</em> side-effect-free: it pushed the CVE-fix branch and recorded this run's
   * unfixable components as suppressions, so an operator who reads {@code COMPLETED} and moves on
   * has mutated suppression state without ever seeing a pull request. Mirrors
   * {@code DistillationStatus.DRY_RUN} in the feedback module.
   */
  DRY_RUN,
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
