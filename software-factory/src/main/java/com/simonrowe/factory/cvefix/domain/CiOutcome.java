package com.simonrowe.factory.cvefix.domain;

import java.util.List;

/**
 * Aggregated CI state for one commit, as read from GitHub's check-runs API.
 *
 * @param state whether CI is still running, has passed, or has failed
 * @param failedCheckNames the names of the non-advisory check runs that failed, empty unless
 *     {@code state} is {@link CiState#RED}
 * @param detail a short human-readable explanation, suitable for a pull request comment
 */
public record CiOutcome(CiState state, List<String> failedCheckNames, String detail) {

  /**
   * Defends against a null list from a caller that built this record by hand, matching the
   * defensive-copy pattern used elsewhere in this module (see {@code ComponentFindings}).
   *
   * @param state whether CI is still running, has passed, or has failed
   * @param failedCheckNames the names of the non-advisory check runs that failed
   * @param detail a short human-readable explanation
   */
  public CiOutcome {
    failedCheckNames = failedCheckNames == null ? List.of() : List.copyOf(failedCheckNames);
  }

  /** Whether CI has finished for a commit, and if so whether it passed. */
  public enum CiState {
    /** Checks are still running, or none have registered yet. */
    PENDING,
    /** Every non-advisory check completed successfully, was neutral, or was skipped. */
    GREEN,
    /** At least one non-advisory check failed, timed out, was cancelled, or needs action. */
    RED
  }
}
