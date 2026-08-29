package com.simonrowe.shortlink;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a content title into a short, speakable share address.
 *
 * <p>Pure: no Mongo, no Spring, no state beyond a {@link SecureRandom}. Collision handling
 * lives in {@link ShortLinkService}, which owns the only thing that can actually prove a
 * slug is free — the insert.
 */
public final class ShortLinkSlugger {

  /**
   * The ceiling, in characters. Short enough to read aloud, long enough to say what the
   * content is. It holds through collision suffixes too — see {@link #withSuffix}.
   */
  public static final int MAX_LENGTH = 20;

  /** Length of the fallback code used when a title yields nothing usable. */
  public static final int RANDOM_CODE_LENGTH = 6;

  /**
   * Below this, backing off to a word boundary throws away more than it saves, so
   * {@link #withSuffix} hard-cuts instead. {@code "an-10"} tells a reader nothing;
   * {@code "an-extraordinaril-10"} at least still names the subject.
   */
  private static final int MIN_BOUNDARY_KEEP = 3;

  private static final String CODE_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

  private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
  /**
   * A run of hyphens at the start, or a run at the end. The non-capturing groups are for
   * the reader, not the matcher: bare {@code ^-+|-+$} alternates across the whole
   * expression exactly as intended, but it reads as though the anchors bind to the
   * alternation rather than to each branch.
   */
  private static final Pattern EDGE_SEPARATORS = Pattern.compile("(?:^-+)|(?:-+$)");

  private static final SecureRandom RANDOM = new SecureRandom();

  private ShortLinkSlugger() {
  }

  /**
   * Derives a slug from a title.
   *
   * <p>Lowercases, strips accents to their plain letters, collapses runs of
   * non-alphanumerics into single hyphens, then takes <em>whole words</em> up to
   * {@link #MAX_LENGTH}. A title whose very first word already exceeds the ceiling is
   * hard-cut rather than discarded: a truncation still tells the reader what the link is
   * about, where a random code tells them nothing.
   *
   * @param title the content title; may be null or blank
   * @return a slug of 1–{@link #MAX_LENGTH} characters, or a {@link #randomCode()} when
   *     nothing usable survives normalisation (an emoji-only or wholly non-Latin title)
   */
  public static String slugify(final String title) {
    String normalised = normalise(title);
    if (normalised.isEmpty()) {
      return randomCode();
    }

    StringBuilder slug = new StringBuilder();
    for (String word : normalised.split("-")) {
      if (word.isEmpty()) {
        continue;
      }
      int lengthWithWord = slug.isEmpty() ? word.length() : slug.length() + 1 + word.length();
      if (lengthWithWord > MAX_LENGTH) {
        break;
      }
      if (!slug.isEmpty()) {
        slug.append('-');
      }
      slug.append(word);
    }

    if (slug.isEmpty()) {
      // The first word alone is longer than the ceiling.
      return normalised.substring(0, MAX_LENGTH);
    }
    return slug.toString();
  }

  /**
   * Produces a distinct slug for a collision, keeping the ceiling.
   *
   * <p>The design's literal instruction — "cut to 17 characters plus {@code -2},
   * {@code -3}" — holds only while the suffix is two characters wide, and breaks at
   * {@code -100}. This reserves {@code ("-" + attempt).length()} instead, so the ceiling
   * holds at every attempt number. Where the cut lands mid-word it backs off to the
   * previous word boundary, unless doing so would leave almost nothing.
   *
   * @param base the slug that was taken
   * @param attempt the attempt number, 2 upwards
   * @return a slug of at most {@link #MAX_LENGTH} characters ending in {@code -attempt}
   */
  public static String withSuffix(final String base, final int attempt) {
    String suffix = "-" + attempt;
    int allowed = MAX_LENGTH - suffix.length();

    String trimmed = base;
    if (trimmed.length() > allowed) {
      String hardCut = trimmed.substring(0, allowed);
      int boundary = hardCut.lastIndexOf('-');
      trimmed = boundary >= MIN_BOUNDARY_KEEP ? hardCut.substring(0, boundary) : hardCut;
    }
    // A base that ends on the separator — or a cut that landed on one — would otherwise
    // produce a double hyphen.
    trimmed = EDGE_SEPARATORS.matcher(trimmed).replaceAll("");

    return trimmed + suffix;
  }

  /**
   * A random fallback address.
   *
   * <p>{@value #RANDOM_CODE_LENGTH} characters over a 36-symbol alphabet is roughly 2.2
   * billion values, so a collision is a formality — but {@link ShortLinkService} still
   * retries on one, because only the insert proves a slug is free.
   *
   * @return six characters of {@code [a-z0-9]}
   */
  public static String randomCode() {
    StringBuilder code = new StringBuilder(RANDOM_CODE_LENGTH);
    for (int i = 0; i < RANDOM_CODE_LENGTH; i++) {
      code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
    }
    return code.toString();
  }

  /**
   * Lowercases, strips accents, and reduces everything else to single hyphens.
   *
   * <p>NFD decomposition splits a precomposed character such as {@code é} into {@code e}
   * plus a combining acute, so removing the combining marks leaves the plain letter. This
   * also makes the two Unicode spellings of the same visible title normalise alike, which
   * matters because otherwise the same post pasted from two sources would mint two links.
   */
  private static String normalise(final String title) {
    if (title == null) {
      return "";
    }
    String decomposed = Normalizer.normalize(title, Normalizer.Form.NFD);
    String stripped = COMBINING_MARKS.matcher(decomposed).replaceAll("");
    String lowered = stripped.toLowerCase(Locale.ROOT);
    String hyphenated = NON_ALPHANUMERIC.matcher(lowered).replaceAll("-");
    return EDGE_SEPARATORS.matcher(hyphenated).replaceAll("");
  }
}
