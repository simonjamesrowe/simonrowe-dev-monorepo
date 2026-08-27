package com.simonrowe.factory.deploy.domain;

/**
 * What one phase did.
 *
 * <p>{@code detail} is the trimmed tail of the phase's output, bounded to {@link #MAX_DETAIL}
 * characters. The full output goes to the container log and, on a failure, into the triage
 * evidence directory — this record answers "which phase failed", it is not a log store.
 *
 * @param phase the phase that ran
 * @param succeeded whether it succeeded
 * @param exitCode the script's exit code; {@code 2} means it declined without side effects
 * @param detail the trimmed tail of the phase output
 * @param durationMillis how long it took
 */
public record PhaseOutcome(
    DeployPhase phase, boolean succeeded, int exitCode, String detail, long durationMillis) {

  /** Characters of phase output kept in the run record. */
  public static final int MAX_DETAIL = 4000;

  /**
   * Exit code the script uses for "declined, with no side effects".
   *
   * <p>Named for the exit code rather than the state, so it cannot be confused with
   * {@link #declined()} — the state that reading it produces.
   */
  public static final int DECLINED_EXIT_CODE = 2;

  public PhaseOutcome {
    detail = trim(detail);
  }

  /** Whether the phase declined rather than failing. */
  public boolean declined() {
    return exitCode == DECLINED_EXIT_CODE;
  }

  private static String trim(final String detail) {
    if (detail == null) {
      return "";
    }
    if (detail.length() <= MAX_DETAIL) {
      return detail;
    }
    // The tail, not the head: a failing command's error is at the end of its output.
    return detail.substring(detail.length() - MAX_DETAIL);
  }
}
