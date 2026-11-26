package com.simonrowe.factory.codereview.domain;

/**
 * A finding this reviewer raised on an earlier push, as the next review needs to know it.
 *
 * <p>Two states only, because only two change what the next review may do:
 *
 * <ul>
 *   <li>{@link State#OPEN}: the thread is still open. The model is shown it so that, if the issue
 *       is still there, it reports it under the same identity instead of a reworded title, which
 *       would resolve one thread as "no longer reported" and open a new one for the same problem.
 *   <li>{@link State#DECLINED}: a person with write access replied in the thread and resolved
 *       it. That is the documented way to decline a finding, so the finding is never posted again
 *       on this pull request and no longer counts toward the {@code Code Review} check.
 * </ul>
 *
 * <p>A thread this reviewer resolved itself ("no longer reported") is neither: if the finding
 * comes back it is a regression and gets a fresh thread.
 *
 * @param fingerprint the finding's identity, {@link FindingFingerprint#of(ReviewFinding)}
 * @param finding the finding as it was rendered in the thread's root comment
 * @param state open or declined
 * @param raisedAt the commit the thread was opened against, or {@code null} when unknown
 * @param resolvedBy who declined it, for {@link State#DECLINED}; otherwise {@code null}
 */
public record PriorFinding(
    String fingerprint, ReviewFinding finding, State state, String raisedAt, String resolvedBy) {

  /** Length of the id the model is shown and asked to echo back. */
  public static final int SHORT_ID_LENGTH = 12;

  /** What a person, or this reviewer, last did with the finding's thread. */
  public enum State {
    OPEN,
    DECLINED
  }

  /**
   * A prefix of the fingerprint, short enough for the model to copy reliably.
   *
   * <p>Twelve hex digits is 48 bits, far more than one pull request's findings can collide on.
   */
  public String shortId() {
    return fingerprint.length() <= SHORT_ID_LENGTH
        ? fingerprint
        : fingerprint.substring(0, SHORT_ID_LENGTH);
  }
}
