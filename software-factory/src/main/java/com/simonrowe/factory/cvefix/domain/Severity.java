package com.simonrowe.factory.cvefix.domain;

import java.util.Locale;

/**
 * Dependency-Track's severity vocabulary, ordered so a report can lead with the worst.
 *
 * <p>Declaration order IS the ranking, most severe first. Anything Dependency-Track sends that is
 * not one of these — including its own {@code UNASSIGNED}, which carries no severity information
 * — ranks below every known level rather than being rejected: an unrecognised severity must push
 * a finding to the bottom of a list, never fail a scan.
 */
public enum Severity {
  CRITICAL,
  HIGH,
  MEDIUM,
  LOW,
  INFO;

  /** The rank given to any severity this enum does not name. Higher than every declared level. */
  private static final int UNRANKED = values().length;

  /**
   * Ranks a Dependency-Track severity string for sorting, lowest number first.
   *
   * @param severity the raw severity, which may be null, blank or unrecognised
   * @return the ordinal of the matching level, or a rank below all of them
   */
  public static int rank(final String severity) {
    if (severity == null || severity.isBlank()) {
      return UNRANKED;
    }
    String normalised = severity.trim().toUpperCase(Locale.ROOT);
    for (Severity level : values()) {
      if (level.name().equals(normalised)) {
        return level.ordinal();
      }
    }
    return UNRANKED;
  }
}
