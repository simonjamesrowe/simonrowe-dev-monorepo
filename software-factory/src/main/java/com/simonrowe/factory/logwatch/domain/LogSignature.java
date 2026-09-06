package com.simonrowe.factory.logwatch.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * A group of log lines that are the same problem.
 *
 * <p>{@code sourceKey} is what the Linear fingerprint is computed from — never the generated
 * title, and since 046 no longer the whole normalised line either. {@code Fingerprint}'s javadoc
 * records the first half of that reasoning: the same problem phrased differently on two runs must
 * not file twice. {@code SourceKeyExtractor}'s records the second: the message <em>is</em> a
 * phrasing, so it cannot be the key.
 *
 * <p>{@code variants} is what makes the coarser key safe. The standing objection to grouping by
 * emitting code is that one logger may emit two genuinely different faults; listing the distinct
 * message templates in the ticket answers it, and is also what makes an updated ticket worth
 * reading.
 *
 * @param signature the normalised form of the most frequent variant; the title is built from it
 * @param severity the severity of the group, which is part of its key so cannot vary within it
 * @param container the container the lines came from
 * @param occurrences how many lines collapsed into this group
 * @param firstSeen the earliest occurrence in the window
 * @param lastSeen the latest occurrence in the window
 * @param exampleLine one real line from the most frequent variant, unnormalised, for the body
 * @param sourceKey the discriminated grouping key: {@code logger:<source>} when the emitting code
 *     could be identified, {@code line:<normalisedLine>} when it could not. The prefix is
 *     load-bearing — without it a source key whose text happened to equal a normalised line would
 *     silently merge two unrelated groups
 * @param variants the distinct normalised signatures in the group, most frequent first, capped at
 *     {@link #MAX_VARIANTS}
 * @param distinctVariants how many distinct signatures the group actually held, before capping
 */
public record LogSignature(
    String signature,
    Severity severity,
    String container,
    int occurrences,
    Instant firstSeen,
    Instant lastSeen,
    String exampleLine,
    String sourceKey,
    List<Variant> variants,
    int distinctVariants) {

  /**
   * How many variants a ticket lists. A body is a scanning aid; beyond a handful the list stops
   * being one, and {@link #distinctVariants} still reports the true total.
   */
  public static final int MAX_VARIANTS = 5;

  /**
   * The order the per-run cap applies: severity first, then occurrence count, both descending.
   *
   * <p>A constant rather than an inline lambda so the ordering is testable on its own — it decides
   * which findings are dropped when there are more than the cap allows. The final tie-break is
   * the source key rather than the signature, because the source key is now the group's identity.
   * This is <strong>not</strong> a total order: {@code container} plays no part in it, so two
   * groups with the same severity and source key from different containers still tie. What
   * makes the result deterministic for tied groups is the same thing it always was — the sort's
   * stability over the {@link java.util.LinkedHashMap} insertion order {@code
   * SignatureExtractor.group} builds groups in — not this comparator alone.
   */
  public static final Comparator<LogSignature> MOST_SEVERE_FIRST =
      Comparator.comparing(LogSignature::severity)
          .thenComparing(Comparator.comparingInt(LogSignature::occurrences).reversed())
          .thenComparing(LogSignature::sourceKey);

  public LogSignature {
    variants = variants == null ? List.of() : List.copyOf(variants);
    // A pre-046 activity result replayed from Temporal history carries no sourceKey at all, which
    // deserializes as null. Defaulting it to the same discriminated form SourceKeyExtractor
    // produces for a line it cannot identify keeps the value semantically coherent - a sentinel
    // would not - and avoids the NPE that List.of(container, severity.name(), sourceKey) throws on
    // a null element in LogWatchWorkflowImpl.fileSignature.
    sourceKey = sourceKey == null ? "line:" + signature : sourceKey;
  }

  /**
   * One distinct message template within a group.
   *
   * @param signature the normalised form
   * @param occurrences how many lines had it
   * @param exampleLine one real line with it, unnormalised
   */
  public record Variant(String signature, int occurrences, String exampleLine) {
  }
}
