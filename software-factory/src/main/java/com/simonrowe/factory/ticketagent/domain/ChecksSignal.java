package com.simonrowe.factory.ticketagent.domain;

/**
 * GitHub says the CI workflow finished on the agent's branch.
 *
 * @param headSha the commit it ran on; a signal for any other commit is ignored
 * @param conclusion GitHub's conclusion: {@code success}, {@code failure}, {@code cancelled} and
 *     so on
 * @param runId the workflow run, whose jobs say what failed
 */
public record ChecksSignal(String headSha, String conclusion, long runId) {

  public boolean passed() {
    return "success".equals(conclusion);
  }
}
