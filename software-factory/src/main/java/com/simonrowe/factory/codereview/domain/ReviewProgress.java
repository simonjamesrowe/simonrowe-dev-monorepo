package com.simonrowe.factory.codereview.domain;

/**
 * Queryable workflow state suitable for a small status API or later UI.
 *
 * @param autoMerge what happened to auto-merge; null until decided, and always null for a run
 *     started before auto-merge existed
 */
public record ReviewProgress(
    ReviewPhase phase,
    String detail,
    String headSha,
    ReviewReport report,
    MergeDecision autoMerge) {

  public static ReviewProgress accepted() {
    return new ReviewProgress(ReviewPhase.ACCEPTED, "Workflow accepted", null, null, null);
  }
}
