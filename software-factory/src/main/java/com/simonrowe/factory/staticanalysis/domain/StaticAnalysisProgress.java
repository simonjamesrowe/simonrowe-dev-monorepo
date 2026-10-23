package com.simonrowe.factory.staticanalysis.domain;

/**
 * Queryable progress snapshot.
 *
 * <p>Exactly three fields, matching every other factory module's {@code progress} query shape
 * {@code {phase, detail, <one module-specific field>}}, which is what lets the console follow any
 * module's run through one endpoint.
 *
 * @param phase where the run has got to
 * @param detail free-text diagnostics
 * @param groupsFound how many finding groups the analysis produced; may be null
 */
public record StaticAnalysisProgress(
    StaticAnalysisPhase phase, String detail, Integer groupsFound) {

  /** The state a run reports before its first activity completes. */
  public static StaticAnalysisProgress accepted() {
    return new StaticAnalysisProgress(StaticAnalysisPhase.ACCEPTED, "Accepted", null);
  }
}
