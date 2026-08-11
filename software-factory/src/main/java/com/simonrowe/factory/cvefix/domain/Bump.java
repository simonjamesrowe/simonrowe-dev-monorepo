package com.simonrowe.factory.cvefix.domain;

import java.util.List;

/** One dependency version change the agent made, and the advisories it clears. */
public record Bump(
    String purl, String file, String fromVersion, String toVersion, List<String> clears) {

  public Bump {
    clears = clears == null ? List.of() : List.copyOf(clears);
  }

  /** Human-readable one-liner for the pull request body and the run record. */
  public String describe() {
    return componentOf(purl) + " " + fromVersion + " -> " + toVersion
        + " (" + String.join(", ", clears) + ")";
  }

  private static String componentOf(final String value) {
    int at = value.lastIndexOf('@');
    return at > 0 ? value.substring(0, at) : value;
  }
}
