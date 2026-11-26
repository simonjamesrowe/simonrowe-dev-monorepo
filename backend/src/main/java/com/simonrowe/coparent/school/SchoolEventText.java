package com.simonrowe.coparent.school;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads which classes and year groups a school event's title names.
 *
 * <p>The school names classes after their teachers, and writes them as a possessive: "Elis's
 * class trip", "Elis' Class Assembly", "Wills' Class Trip" (and, for the same class elsewhere,
 * "Will's class visit"). Reception classes are animals ("Bears Class"), written without one. A
 * possessive is required to call a word a class, so "Dance class" or "Art class" never read as a
 * class the child is not in, which would hide the event from them. A non-possessive name only
 * counts when it is the child's own class ({@link #namesClass}).
 *
 * <p>Titles only, never descriptions: a description that mentions another class in passing
 * ("Elis's class went last week") would otherwise hide this class's event.
 *
 * <p>Every pattern is possessive or bounded and the input is capped, so nothing backtracks.
 */
final class SchoolEventText {

  /** Long enough for any title the school or the extractor writes. */
  static final int MAX_SCANNED = 300;

  private static final Pattern POSSESSIVE_CLASS = Pattern.compile(
      "(?<![\\p{L}])(\\p{L}[\\p{L}-]{0,29}+)(?:'s|’s|'|’)\\s++class",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  private static final String YEAR_NUMBER = "(?<![\\p{L}])years?+\\s*+([1-6])(?![0-9])";
  private static final String JOINER = "(?:,|and|&|-|–|to)";

  /** "Year 3", "Years 5 and 6", "Years 3-6", "Year 3, 4 & 5". */
  private static final Pattern YEARS = Pattern.compile(
      YEAR_NUMBER + "((?:\\s*+" + JOINER + "\\s*+(?:year\\s*+)?[1-6](?![0-9])){0,5}+)",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern YEAR_TAIL = Pattern.compile(
      "(,|and|&|-|–|to)\\s*+(?:year\\s*+)?([1-6])", Pattern.CASE_INSENSITIVE);

  private static final Pattern RECEPTION = Pattern.compile(
      "(?<![\\p{L}])reception(?![\\p{L}])", Pattern.CASE_INSENSITIVE);

  /** Possessives that are not a class's name. */
  private static final Set<String> NOT_A_CLASS = Stream.of(
      "child", "children", "everyone", "everybody", "today", "tomorrow", "week", "year",
      "parent", "parents", "pupil", "pupils", "teacher", "teachers", "school", "kilmorie",
      "your", "my", "our", "their", "his", "her", "each", "every", "one", "other", "next", "last",
      "term", "month", "day", "reception", "nursery")
      .map(SchoolEventText::normalise).collect(Collectors.toUnmodifiableSet());

  private SchoolEventText() {
  }

  /**
   * The classes a title names as a possessive, normalised for comparison.
   *
   * @param title an event title, possibly null
   * @return normalised class names, in the order they appear; empty when none is named
   */
  static Set<String> classesNamed(final String title) {
    final Set<String> names = new LinkedHashSet<>();
    if (title == null) {
      return names;
    }
    final Matcher matcher = POSSESSIVE_CLASS.matcher(capped(title));
    while (matcher.find()) {
      final String name = normalise(matcher.group(1));
      if (!name.isEmpty() && !NOT_A_CLASS.contains(name)) {
        names.add(name);
      }
    }
    return names;
  }

  /**
   * Whether a title names a given class, possessive or not ("Elis's class", "Bears Class").
   *
   * @param title an event title, possibly null
   * @param className the child's class as the parent entered it
   * @return true when the title names that class
   */
  static boolean namesClass(final String title, final String className) {
    if (title == null || className == null || className.isBlank()) {
      return false;
    }
    final String wanted = normalise(className);
    if (classesNamed(title).contains(wanted)) {
      return true;
    }
    // Pattern.quote, so a class name a parent typed is matched literally.
    final Pattern plain = Pattern.compile(
        "(?<![\\p{L}])" + Pattern.quote(className.strip()) + "\\s++class",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    return plain.matcher(capped(title)).find();
  }

  /**
   * The year groups a title names, for an event the source left untagged.
   *
   * @param title an event title, possibly null
   * @return year groups such as {@code Year 3} or {@code Reception}, empty when none is named
   */
  static Set<String> yearGroupsNamed(final String title) {
    final Set<String> years = new LinkedHashSet<>();
    if (title == null) {
      return years;
    }
    final String scanned = capped(title);
    if (RECEPTION.matcher(scanned).find()) {
      years.add("Reception");
    }
    final Matcher matcher = YEARS.matcher(scanned);
    while (matcher.find()) {
      int previous = Integer.parseInt(matcher.group(1));
      years.add("Year " + previous);
      final Matcher tail = YEAR_TAIL.matcher(matcher.group(2));
      while (tail.find()) {
        final int next = Integer.parseInt(tail.group(2));
        final String joiner = tail.group(1).toLowerCase(Locale.ROOT);
        final boolean range = "-".equals(joiner) || "–".equals(joiner) || "to".equals(joiner);
        if (range && next > previous) {
          for (int year = previous + 1; year <= next; year++) {
            years.add("Year " + year);
          }
        } else {
          years.add("Year " + next);
        }
        previous = next;
      }
    }
    return years;
  }

  /**
   * A class name in the form two spellings of it share: lower case, and one trailing "s"
   * dropped, so "Wills'" and "Will's" (both written for one class) compare equal, as do "Elis"
   * and "Elis'".
   */
  static String normalise(final String name) {
    final String lower = name.strip().toLowerCase(Locale.ROOT);
    return lower.length() > 2 && lower.endsWith("s") ? lower.substring(0, lower.length() - 1)
        : lower;
  }

  private static String capped(final String text) {
    return text.length() > MAX_SCANNED ? text.substring(0, MAX_SCANNED) : text;
  }
}
