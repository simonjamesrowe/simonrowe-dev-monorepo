package com.simonrowe.factory.linear.domain;

/** What the sink did about one occurrence. */
public enum FilingDecision {
  /** No issue carried the fingerprint; a new one was created in Triage. */
  FILED_NEW,
  /** An open issue already carried it; the occurrence was added as a comment. */
  COMMENTED_EXISTING,
  /** A human declined it. Nothing was done, and nothing will be until they reopen it. */
  SUPPRESSED,
  /** It was marked fixed and came back; a new issue was filed, linked to the old one. */
  FILED_REGRESSION,
  /**
   * A {@link FilingMode#STATUS_UPDATE} filing found no open issue to comment on. Nothing was
   * created, because that producer sends status updates about known problems and must never file.
   */
  SKIPPED_NO_ISSUE,
  /**
   * An open issue already carried the fingerprint, and the producer asked for its description to
   * be rewritten to current state rather than for a comment. Distinct from
   * {@link #COMMENTED_EXISTING} because "we rewrote the ticket" and "we added a comment" are
   * different acts, and the audit trail in {@code linear_issues} must be able to say which.
   */
  UPDATED_EXISTING,
  /**
   * A completed issue carried the fingerprint and the producer files a rolling report, so it was
   * reopened into Triage and rewritten rather than replaced by a linked new issue. Distinct from
   * {@link #FILED_REGRESSION} for the same reason.
   */
  REOPENED_EXISTING
}
