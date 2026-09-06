package com.simonrowe.factory.logwatch.workflow;

import com.simonrowe.factory.logwatch.domain.LogSignature;
import com.simonrowe.factory.logwatch.domain.SourceHealth;
import java.time.Duration;
import java.time.Instant;

/**
 * Deterministic write-ups for filed issues.
 *
 * <p>FR-009 allows a language model to turn grouped signatures into readable prose, but requires a
 * deterministic fallback: <strong>a ticket must never be lost because a write-up could not be
 * generated.</strong> Everything here is pure string formatting over already-decided facts, so it
 * cannot fail.
 *
 * <p>Note what is <em>not</em> rendered by a model: the source-health write-up. When the module
 * cannot trust its own inputs, it should not be asking a model to describe them.
 */
public final class LogWatchReportRenderer {

  private LogWatchReportRenderer() {
  }

  /**
   * Title for one signature's issue.
   *
   * @param signature the grouped problem
   * @return a one-line title naming the container, the severity and the emitting source
   */
  public static String title(final LogSignature signature) {
    String source = shortSource(signature);
    String where =
        source == null ? signature.container() : signature.container() + " (" + source + ")";
    return signature.severity() + " in " + where + ": " + summarise(signature);
  }

  /**
   * The readable form of the group's source, for a title.
   *
   * @param signature the grouped problem
   * @return the last dot-separated segment of the emitting source, or null when the group is
   *     keyed on its normalised line because no source could be identified
   */
  public static String shortSource(final LogSignature signature) {
    String key = signature.sourceKey();
    if (key == null || !key.startsWith("logger:")) {
      return null;
    }
    String source = key.substring("logger:".length());
    int lastDot = source.lastIndexOf('.');
    // A Temporal msg is a sentence and often ends in a full stop, so abbreviating at the last dot
    // would leave nothing. Only abbreviate something that looks like a dotted identifier.
    if (lastDot < 0 || lastDot == source.length() - 1 || source.contains(" ")) {
      return source;
    }
    return source.substring(lastDot + 1);
  }

  /**
   * Body for one signature's issue.
   *
   * @param signature the grouped problem
   * @param windowStart the scanned window's start
   * @param windowEnd the scanned window's end
   * @return a Markdown description
   */
  public static String body(
      final LogSignature signature, final Instant windowStart, final Instant windowEnd) {
    return """
        **%s** in `%s`, seen **%d time(s)** between %s and %s.

        Window scanned: %s to %s.

        Emitted by (this is what deduplicates the issue):

        ```
        %s
        ```

        Example line:

        ```
        %s
        ```

        %s"""
        .formatted(
            signature.severity(),
            signature.container(),
            signature.occurrences(),
            signature.firstSeen(),
            signature.lastSeen(),
            windowStart,
            windowEnd,
            signature.sourceKey(),
            signature.exampleLine(),
            variantsSection(signature));
  }

  /**
   * The distinct message templates in the group.
   *
   * <p>This is what keeps grouping by emitting code honest. One logger can emit two genuinely
   * different faults, and without this the coarser key would hide the second inside the first.
   *
   * @param signature the grouped problem
   * @return a Markdown section, always non-empty
   */
  private static String variantsSection(final LogSignature signature) {
    StringBuilder section = new StringBuilder("Distinct messages seen (");
    section.append(signature.distinctVariants()).append(" in total):\n\n");
    for (LogSignature.Variant variant : signature.variants()) {
      section
          .append("* **")
          .append(variant.occurrences())
          .append("x** `")
          .append(variant.signature())
          .append("`\n");
    }
    int withheld = signature.distinctVariants() - signature.variants().size();
    if (withheld > 0) {
      section.append("\nand ").append(withheld).append(" further variant(s) not listed.\n");
    }
    return section.toString();
  }

  /**
   * Title for a source-health failure.
   *
   * @param health the verdict
   * @return a one-line title
   */
  public static String sourceHealthTitle(final SourceHealth health) {
    return "Log watch cannot see production logs (" + health.status() + ")";
  }

  /**
   * Body for a source-health failure.
   *
   * <p>States plainly that this is not an all-clear, because the whole point of the finding is
   * that the absence of other findings means nothing.
   *
   * @param health the verdict
   * @param windowStart the scanned window's start
   * @param windowEnd the scanned window's end
   * @return a Markdown description
   */
  public static String sourceHealthBody(
      final SourceHealth health, final Instant windowStart, final Instant windowEnd) {
    return """
        Log watch could not establish that it can see production logs, so **this scan's result is
        not an all-clear**. Any absence of findings in this run means nothing.

        - **Verdict:** `%s`
        - **How it was reached:** `%s`
        - **Evidence:** %s
        - **Window:** %s to %s

        Until this is resolved, log watch reports nothing about production health. See
        `docs/runbooks/log-shipping.md` — the most likely cause is the Grafana Cloud free-tier
        monthly logs allowance being spent, which sets the tenant's ingestion rate to
        `0 bytes/sec` and makes Alloy drop every batch while its container stays healthy.
        """
        .formatted(
            health.status(), health.tier(), health.evidence(), windowStart, windowEnd);
  }

  /**
   * One line naming this occurrence, used when commenting on an issue that already exists.
   *
   * @param signature the grouped problem
   * @param runId the Temporal run id
   * @return a single line
   */
  public static String occurrenceDetail(final LogSignature signature, final String runId) {
    return "scan "
        + runId
        + " saw this "
        + signature.occurrences()
        + " time(s) between "
        + signature.firstSeen()
        + " and "
        + signature.lastSeen();
  }

  /**
   * A short, human-readable summary of the signature, for use in a title.
   *
   * <p>Truncated on a word boundary where possible: a title is a scanning aid, and a Linear list
   * of eighty-character fragments is harder to read than one of short ones.
   *
   * @param signature the grouped problem
   * @return at most roughly eighty characters of the normalised signature
   */
  private static String summarise(final LogSignature signature) {
    String text = signature.signature();
    if (text.length() <= 80) {
      return text;
    }
    int lastSpace = text.lastIndexOf(' ', 80);
    return text.substring(0, lastSpace > 40 ? lastSpace : 80) + "...";
  }

  /**
   * Renders the window as a human-readable duration, for run progress.
   *
   * @param windowStart the window's start
   * @param windowEnd the window's end
   * @return e.g. {@code 24h} or {@code 5m}
   */
  public static String describeWindow(final Instant windowStart, final Instant windowEnd) {
    Duration duration = Duration.between(windowStart, windowEnd);
    return duration.toHours() > 0 ? duration.toHours() + "h" : duration.toMinutes() + "m";
  }
}
