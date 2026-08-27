package com.simonrowe.factory.codereview.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * A stable identity for one finding, used to recognise it across independent review runs.
 *
 * <p>Findings used to have no identity at all, which is why deleting every inline comment and
 * reposting the survivors was the only available strategy. Deletion is not resolution: a standing
 * finding read as brand new on every push, GitHub's "N resolved" counter stayed permanently zero,
 * and a thread root was destroyed even when a human had replied to it — taking the reply with it.
 *
 * <p>The hash covers the file and the normalised title, and <em>deliberately nothing else</em>:
 *
 * <ul>
 *   <li><b>Not the line.</b> Lines move on every rebase, so including one would orphan every
 *       finding on the pull request the first time the branch was updated.
 *   <li><b>Not the severity.</b> The model re-grades between runs, so the same finding would
 *       change identity whenever it was promoted or demoted.
 *   <li><b>Not the explanation or recommendation.</b> Prose varies far more than titles do,
 *       making identity strictly less stable for no gain.
 * </ul>
 *
 * <p><b>Accepted limitation:</b> identity is only as stable as the model's phrasing of the title,
 * so a re-worded title for the same underlying issue reads as "one resolved, one new". That is why
 * the reply left on a resolved thread says <em>"No longer reported as of {@code <sha>}"</em> rather
 * than "Fixed" — truthful under both a genuine fix and a re-wording. Engine-supplied identifiers
 * were considered and rejected: an LLM cannot emit ids that are stable across independent runs.
 */
public final class FindingFingerprint {

  /**
   * Separates the two hashed components.
   *
   * <p>NUL, because it cannot occur in a path or a title, so {@code ("ab", "c")} and {@code ("a",
   * "bc")} cannot collide.
   */
  private static final String SEPARATOR = "\0";

  private FindingFingerprint() {
  }

  /** The fingerprint of a finding, as lowercase hex SHA-256. */
  public static String of(final ReviewFinding finding) {
    return of(finding.file(), finding.title());
  }

  /** The fingerprint of a {@code (file, title)} pair, as lowercase hex SHA-256. */
  public static String of(final String file, final String title) {
    String payload = safe(file) + SEPARATOR + normalise(title);
    return hex(sha256(payload));
  }

  /**
   * Reduces a title to the part of it that is worth comparing.
   *
   * <p>Lowercases, replaces every character that is neither a letter nor a digit with a space, then
   * collapses whitespace runs to a single space. So {@code "Missing null check!"}, {@code "missing
   * null check"} and {@code "Missing  null-check"} are one finding, not three.
   *
   * <p>Punctuation becomes a <em>space</em> rather than being deleted, which is not a detail:
   * deleting it glues {@code "null-check"} into {@code "nullcheck"} while {@code "null check"}
   * stays two words, so the commonest re-wording of all — hyphenating a compound — would produce
   * two different findings from one.
   *
   * <p>A title that normalises to nothing at all — punctuation only — falls back to the trimmed raw
   * title, so it still gets <em>an</em> identity rather than colliding with every other
   * empty-title finding.
   */
  static String normalise(final String title) {
    if (title == null) {
      return "";
    }
    String normalised =
        title
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    return normalised.isEmpty() ? title.trim() : normalised;
  }

  private static String safe(final String value) {
    return value == null ? "" : value;
  }

  private static byte[] sha256(final String payload) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      // SHA-256 is mandated by the JDK specification; its absence is not a recoverable condition.
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String hex(final byte[] digest) {
    StringBuilder hex = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return hex.toString();
  }
}
