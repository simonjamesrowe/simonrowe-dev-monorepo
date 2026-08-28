package com.simonrowe.factory.linear.domain;

/**
 * What the sink did.
 *
 * <p>For a {@code SUPPRESSED} occurrence the issue fields are <strong>always</strong> null, even
 * when Mongo still records the ticket this fingerprint last filed. That is deliberate and is
 * enforced in {@code IssueFiler}: a producer that reported the stored URL for a suppressed
 * occurrence would attribute a ticket to a run that filed nothing, and point a reader at a
 * declined issue containing none of this occurrence's detail.
 *
 * @param decision the decision taken
 * @param issueId the Linear UUID used for follow-up attachments; null for a suppressed
 *     occurrence and for a dry run
 * @param issueIdentifier the issue concerned, e.g. {@code SIM-42}; null for a suppressed
 *     occurrence and for a dry run
 * @param issueUrl the issue's web URL, on the same terms
 * @param fingerprint the fingerprint, so a producer can record it alongside its own outcome
 */
public record FiledIssue(
    FilingDecision decision, String issueId, String issueIdentifier, String issueUrl,
    String fingerprint) {

  /** Compatibility constructor for producers that do not need follow-up attachments. */
  public FiledIssue(
      final FilingDecision decision,
      final String issueIdentifier,
      final String issueUrl,
      final String fingerprint) {
    this(decision, null, issueIdentifier, issueUrl, fingerprint);
  }
}
