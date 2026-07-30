package com.simonrowe.factory.codereview.domain;

/** Queryable workflow state suitable for a small status API or later UI. */
public record ReviewProgress(
    ReviewPhase phase, String detail, String headSha, ReviewReport report) {

  public static ReviewProgress accepted() {
    return new ReviewProgress(ReviewPhase.ACCEPTED, "Workflow accepted", null, null);
  }
}
