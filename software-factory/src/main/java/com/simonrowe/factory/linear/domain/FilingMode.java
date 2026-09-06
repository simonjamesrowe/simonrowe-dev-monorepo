package com.simonrowe.factory.linear.domain;

/**
 * How a producer wants its occurrences handled once an issue for the problem already exists.
 *
 * <p>One axis rather than three independent booleans, deliberately: {@code commentOnly} plus a
 * hypothetical {@code refreshBody} plus a hypothetical {@code rolling} admits combinations that
 * contradict each other, and there is no sensible behaviour for "comment only, and also rewrite
 * the body, and also reopen".
 *
 * <p>The mode is a property of the <em>producer</em>, not of the tracker's state. That is why
 * {@code FilingDecider} knows nothing about it and stays pure: it answers what Linear currently
 * says about a fingerprint, and the mode decides what to do about the answer.
 */
public enum FilingMode {

  /**
   * A new occurrence of a problem. Comments on an open issue, and files a linked replacement when
   * the only issue found was completed. This is what shipped in 039, and is what {@code deploy}
   * and {@code review-feedback} still want: a deploy that fails twice really is two events.
   */
  OCCURRENCE(true, false, false),

  /**
   * A restatement of a problem's current state. Rewrites the open issue's description and posts
   * no comment, so a nightly scan on a persistent problem stops adding a comment a night.
   *
   * <p>Used by {@code logwatch}. The occurrence history is not lost — it is in
   * {@code LinearIssueRecord.decisions} — but it is no longer surfaced in Linear, which is the
   * accepted cost: a problem recurring after its ticket has been moved out of Triage now produces
   * no notification.
   */
  REFRESH(true, true, false),

  /**
   * A long-lived rolling report whose title describes a standing question rather than an event,
   * such as {@code Current vulnerabilities in <repo>}. Behaves as {@link #REFRESH}, and
   * additionally reopens a completed issue into Triage rather than filing a replacement.
   *
   * <p>Used by {@code cvefix}. Without it, closing the report once caused the next scan to file
   * SIM-10 beside the completed SIM-9 — two tickets asking the same standing question.
   */
  ROLLING(true, true, true),

  /**
   * A status update about a known problem, not an occurrence of one. Comments on an open issue
   * using the occurrence detail verbatim, and otherwise does nothing at all.
   *
   * <p><strong>Never creates an issue.</strong> Used by {@code cvefix} to say a repository has
   * become clean; filing a ticket whose content is the <em>absence</em> of a problem would be
   * worse than silence. This is the {@code commentOnly} flag from 040, unchanged in behaviour.
   */
  STATUS_UPDATE(false, false, false);

  private final boolean mayCreate;
  private final boolean rewritesBody;
  private final boolean reopensCompleted;

  FilingMode(
      final boolean mayCreate, final boolean rewritesBody, final boolean reopensCompleted) {
    this.mayCreate = mayCreate;
    this.rewritesBody = rewritesBody;
    this.reopensCompleted = reopensCompleted;
  }

  /**
   * Whether this mode is allowed to create an issue that does not yet exist.
   *
   * @return false only for {@link #STATUS_UPDATE}
   */
  public boolean mayCreate() {
    return mayCreate;
  }

  /**
   * Whether a recurrence rewrites the existing issue's description instead of commenting on it.
   *
   * @return true for {@link #REFRESH} and {@link #ROLLING}
   */
  public boolean rewritesBody() {
    return rewritesBody;
  }

  /**
   * Whether a completed issue is reopened rather than replaced by a linked new one.
   *
   * @return true only for {@link #ROLLING}
   */
  public boolean reopensCompleted() {
    return reopensCompleted;
  }
}
