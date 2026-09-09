package com.simonrowe.school.ingest;

import com.simonrowe.school.model.SchoolSourceType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Stable identifiers for school content.
 *
 * <p>Both ids are content-derived rather than random, which is what makes re-ingestion idempotent
 * and makes the same event arriving from two sources collapse to one row instead of being
 * retrieved and cited twice.
 */
public final class SchoolIds {

  private SchoolIds() {
  }

  /**
   * The id for an ingested document.
   *
   * @param sourceType where it came from
   * @param sourceRef the URL or message id
   * @return a stable hex id
   */
  public static String documentId(final SchoolSourceType sourceType, final String sourceRef) {
    return sha256(sourceType.name() + ':' + sourceRef);
  }

  /**
   * The id for a dated fact.
   *
   * <p>Keyed on academic year, start date and a normalised title — not on the source. Two sources
   * describing the same INSET day must produce the same id so that precedence can pick a winner;
   * including the source would give two rows and report the day twice.
   *
   * @param academicYear the academic year label
   * @param startDate the first day
   * @param title the event name
   * @return a stable hex id
   */
  public static String eventId(
      final String academicYear, final LocalDate startDate, final String title) {
    return sha256(academicYear + '|' + startDate + '|' + normaliseTitle(title));
  }

  /**
   * Reduces a title to its comparable form: lower case, punctuation dropped, runs of whitespace
   * collapsed. "INSET Day" and "Inset day." must not be two events.
   *
   * @param title the raw title
   * @return the normalised form
   */
  public static String normaliseTitle(final String title) {
    if (title == null) {
      return "";
    }
    return title.toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9 ]", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private static String sha256(final String input) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
