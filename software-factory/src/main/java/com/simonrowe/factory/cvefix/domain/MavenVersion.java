package com.simonrowe.factory.cvefix.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A Maven version, ordered the way Maven orders them closely enough to choose a fixed release.
 *
 * <p>A cut-down {@code ComparableVersion}: numbers compare numerically, the usual qualifiers rank
 * {@code alpha < beta < milestone < rc < snapshot < release < sp}, {@code Final}, {@code GA} and
 * {@code RELEASE} equal no qualifier, and a missing part counts as zero. Its only use is picking
 * the lowest version that fixes an advisory, and a mis-ordering there costs a refused or a wasted
 * pull request, never an unreviewed merge, because CI and the reviewer still judge the result.
 */
public final class MavenVersion implements Comparable<MavenVersion> {

  private static final int RELEASE = 5;
  private static final List<String> QUALIFIERS =
      List.of("alpha", "beta", "milestone", "rc", "snapshot", "", "sp");

  private final String text;
  private final List<Object> items;

  private MavenVersion(final String text) {
    this.text = text;
    this.items = parse(text.toLowerCase(Locale.ROOT));
  }

  /**
   * Parses a version.
   *
   * @param text the version, e.g. {@code 4.1.118.Final}
   * @return the version
   */
  public static MavenVersion of(final String text) {
    return new MavenVersion(text == null ? "" : text.strip());
  }

  /** The first number, or -1 when the version does not start with one. */
  public long major() {
    return !items.isEmpty() && items.get(0) instanceof Long number ? number : -1;
  }

  @Override
  public String toString() {
    return text;
  }

  @Override
  public int compareTo(final MavenVersion other) {
    int length = Math.max(items.size(), other.items.size());
    for (int i = 0; i < length; i++) {
      int compared = compareItems(
          i < items.size() ? items.get(i) : null,
          i < other.items.size() ? other.items.get(i) : null);
      if (compared != 0) {
        return compared;
      }
    }
    return 0;
  }

  @Override
  public boolean equals(final Object other) {
    return other instanceof MavenVersion version && compareTo(version) == 0;
  }

  @Override
  public int hashCode() {
    return items.hashCode();
  }

  private static int compareItems(final Object left, final Object right) {
    if (left instanceof Long a && right instanceof Long b) {
      return Long.compare(a, b);
    }
    if (left instanceof Long a) {
      return right == null ? Long.compare(a, 0) : (isRelease(right) ? Long.compare(a, 0) : 1);
    }
    if (right instanceof Long b) {
      return -compareItems(right, left);
    }
    return Integer.compare(rank(left), rank(right)) != 0
        ? Integer.compare(rank(left), rank(right))
        : String.valueOf(left).compareTo(String.valueOf(right));
  }

  private static boolean isRelease(final Object item) {
    return rank(item) == RELEASE;
  }

  /** A qualifier's rank: null (a missing part) is a release; unknown words sort after sp. */
  private static int rank(final Object item) {
    if (item == null) {
      return RELEASE;
    }
    int index = QUALIFIERS.indexOf((String) item);
    return index < 0 ? QUALIFIERS.size() : index;
  }

  private static List<Object> parse(final String version) {
    List<Object> parsed = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    Boolean digits = null;
    for (int i = 0; i < version.length(); i++) {
      char c = version.charAt(i);
      boolean separator = c == '.' || c == '-' || c == '_' || c == '+';
      boolean digit = Character.isDigit(c);
      if (separator || (digits != null && digit != digits)) {
        flush(parsed, current, digits);
        current.setLength(0);
        digits = null;
      }
      if (!separator) {
        current.append(c);
        digits = digit;
      }
    }
    flush(parsed, current, digits);
    // Trailing zeros and release words add nothing: 1.0.0.Final equals 1.
    while (!parsed.isEmpty()) {
      Object last = parsed.get(parsed.size() - 1);
      boolean zero = last instanceof Long number && number == 0;
      if (zero || (last instanceof String && isRelease(last))) {
        parsed.remove(parsed.size() - 1);
      } else {
        break;
      }
    }
    return List.copyOf(parsed);
  }

  private static void flush(
      final List<Object> parsed, final StringBuilder part, final Boolean digits) {
    if (part.isEmpty()) {
      return;
    }
    if (Boolean.TRUE.equals(digits)) {
      String number = part.toString();
      // Long enough for any real version part; a longer one is treated as the largest.
      parsed.add(number.length() > 18 ? Long.MAX_VALUE : Long.parseLong(number));
    } else {
      parsed.add(switch (part.toString()) {
        case "a" -> "alpha";
        case "b" -> "beta";
        case "m" -> "milestone";
        case "cr" -> "rc";
        case "final", "ga", "release" -> "";
        default -> part.toString();
      });
    }
  }
}
