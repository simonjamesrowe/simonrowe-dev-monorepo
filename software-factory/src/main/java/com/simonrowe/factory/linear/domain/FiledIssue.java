package com.simonrowe.factory.linear.domain;

/**
 * What the sink did.
 *
 * @param decision the decision taken
 * @param issueIdentifier the issue concerned, e.g. {@code SIM-42}; null for a suppressed
 *     occurrence and for a dry run
 * @param issueUrl the issue's web URL, on the same terms
 * @param fingerprint the fingerprint, so a producer can record it alongside its own outcome
 */
public record FiledIssue(
    FilingDecision decision, String issueIdentifier, String issueUrl, String fingerprint) {
}
