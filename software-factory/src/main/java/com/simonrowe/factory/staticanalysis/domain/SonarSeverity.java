package com.simonrowe.factory.staticanalysis.domain;

import java.util.Locale;

/**
 * Sonar's issue severity vocabulary, ordered so a report can lead with the worst.
 *
 * <p>Declaration order IS the ranking, most severe first. Anything Sonar sends that is not one of
 * these ranks below every known level rather than being rejected: an unrecognised severity must
 * push a finding down a list, never fail a scan. Sonar is moving to impact-based severities and
 * still sends this field beside them, so its disappearance would rank everything equally rather
 * than break anything.
 */
public enum SonarSeverity {
  BLOCKER,
  CRITICAL,
  MAJOR,
  MINOR,
  INFO;

  /** The rank given to any severity this enum does not name. Higher than every declared level. */
  private static final int UNRANKED = values().length;

  /**
   * Ranks a Sonar severity string for sorting, lowest number first.
   *
   * @param severity the raw severity, which may be null, blank or unrecognised
   * @return the ordinal of the matching level, or a rank below all of them
   */
  public static int rank(final String severity) {
    if (severity == null || severity.isBlank()) {
      return UNRANKED;
    }
    String normalised = severity.trim().toUpperCase(Locale.ROOT);
    for (SonarSeverity level : values()) {
      if (level.name().equals(normalised)) {
        return level.ordinal();
      }
    }
    return UNRANKED;
  }
}
