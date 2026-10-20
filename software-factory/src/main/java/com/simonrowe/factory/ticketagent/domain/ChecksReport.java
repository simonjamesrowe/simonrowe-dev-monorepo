package com.simonrowe.factory.ticketagent.domain;

/**
 * Both verdicts on one commit, read from GitHub: CI's and the reviewer's.
 *
 * @param headSha the commit
 * @param ciComplete whether the CI workflow has finished on it
 * @param ciPassed whether it finished green
 * @param ciRunId the CI workflow run, 0 when none has been seen
 * @param reviewComplete whether the {@code Code Review} check run has completed
 * @param reviewPassed whether it completed green
 */
public record ChecksReport(
    String headSha,
    boolean ciComplete,
    boolean ciPassed,
    long ciRunId,
    boolean reviewComplete,
    boolean reviewPassed) {

  public boolean complete() {
    return ciComplete && reviewComplete;
  }

  public boolean passed() {
    return ciPassed && reviewPassed;
  }
}
