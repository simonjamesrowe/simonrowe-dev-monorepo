package com.simonrowe.factory.logwatch.domain;

import java.time.Instant;
import java.util.Comparator;

/**
 * A group of log lines that are the same problem.
 *
 * <p>{@code signature} is what the Linear fingerprint is computed from — never the generated
 * title. {@code Fingerprint}'s javadoc records why: the same problem phrased differently on two
 * runs would file twice.
 *
 * @param signature the normalised form shared by every line in the group; the dedup key
 * @param severity the highest severity seen across the group
 * @param container the container the lines came from
 * @param occurrences how many lines collapsed into this signature
 * @param firstSeen the earliest occurrence in the window
 * @param lastSeen the latest occurrence in the window
 * @param exampleLine one real line, unnormalised, for the ticket body
 */
public record LogSignature(
    String signature,
    Severity severity,
    String container,
    int occurrences,
    Instant firstSeen,
    Instant lastSeen,
    String exampleLine) {

  /**
   * The order the per-run cap applies: severity first, then occurrence count, both descending.
   *
   * <p>A constant rather than an inline lambda so the ordering is testable on its own — it decides
   * which findings are dropped when there are more than the cap allows.
   */
  public static final Comparator<LogSignature> MOST_SEVERE_FIRST =
      Comparator.comparing(LogSignature::severity)
          .thenComparing(Comparator.comparingInt(LogSignature::occurrences).reversed())
          .thenComparing(LogSignature::signature);
}
