package com.simonrowe.factory.cvefix.domain;

import java.util.List;

/**
 * A component the agent declined to bump, with the reason. Recorded so the same advisories do
 * not cost an agent run every night.
 *
 * <p>Carries no fingerprint on purpose: the suppression key is computed in Java from the matching
 * {@code ComponentFindings}. A model-emitted key would be compared against a Java-computed one and
 * would silently disable suppression on any deviation.
 */
public record UnfixableComponent(String purl, List<String> vulnerabilityIds, String reason) {

  public UnfixableComponent {
    vulnerabilityIds = vulnerabilityIds == null ? List.of() : List.copyOf(vulnerabilityIds);
  }
}
