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
  FILED_REGRESSION
}
