package com.simonrowe.factory.linear.domain;

import java.util.List;

/**
 * One issue an {@link AbsenceSweep} closed.
 *
 * @param fingerprint the fingerprint that stopped being reported
 * @param keyParts the structured parts that fingerprint was computed from, so a reader of the run
 *     record can see <em>what</em> stopped without resolving the digest
 * @param issueIdentifier the human identifier, e.g. {@code SIM-42}
 * @param issueUrl the issue's web URL
 */
public record SweptIssue(
    String fingerprint, List<String> keyParts, String issueIdentifier, String issueUrl) {

  public SweptIssue {
    keyParts = keyParts == null ? List.of() : List.copyOf(keyParts);
  }
}
