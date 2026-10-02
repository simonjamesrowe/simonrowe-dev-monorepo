package com.simonrowe.school.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The school's year groups, and the mapping to the calendar feed's numeric sub-calendar ids.
 *
 * <p>The ids are not sequential and do not follow year order — Reception is 13, sitting after
 * Year 6's 9, because it was evidently added to the school's calendar later. Deriving them
 * arithmetically would look tidier and be wrong.
 */
public final class YearGroups {

  /** Ordered as a parent thinks of them, which is also the order the selector renders. */
  public static final List<String> ALL = List.of(
      "Reception", "Year 1", "Year 2", "Year 3", "Year 4", "Year 5", "Year 6");

  private static final Map<Integer, String> BY_CALENDAR_ID = buildCalendarIdMap();

  /** The whole-school calendar. Events here apply to every year group. */
  public static final int ALL_SCHOOL_CALENDAR_ID = 1;

  /**
   * The year a page is about, read from the first segment of its path.
   *
   * <p>The school's CMS names every year-group page and its children after the year, in both
   * spellings it has accumulated: {@code /year-three}, {@code /year-three-home-learning},
   * {@code /year-4}, {@code /year3-stonehenge}. Anchored at the start of the segment, so
   * {@code /school-news/year-5-football} (first segment {@code school-news}) and
   * {@code /year-group-pages} (the hub) are not attributed to anyone. The segment is bounded by
   * the URL and every quantifier is a fixed-width alternation, so there is nothing to backtrack.
   */
  private static final Pattern YEAR_SEGMENT = Pattern.compile(
      """
      ^(?:year-?(one|two|three|four|five|six)(?![a-z])|year-?([1-6])(?![0-9])\
      |(reception)(?![a-z]))""");

  private static final Map<String, String> YEAR_WORDS = Map.of(
      "one", "Year 1", "two", "Year 2", "three", "Year 3",
      "four", "Year 4", "five", "Year 5", "six", "Year 6");

  private YearGroups() {
  }

  /**
   * The year group a school web page belongs to, from its address.
   *
   * <p>Deterministic on purpose. Which year a page belongs to is published by the school in the
   * page's address, so there is no reason to ask a model and every reason not to: a spelling list
   * is exactly the document where a guess reads as fact. It was not recorded at all before, and
   * every year's "Home Learning" PDF reached the assistant under the same title with nothing to
   * say whose it was — so it attributed a Year 6 list to Year 3 because Year 3 was what the
   * parent asked about.
   *
   * @param url an absolute page URL, possibly null
   * @return the one year group the page names, or empty for a whole-school page
   */
  public static List<String> fromPageUrl(final String url) {
    if (url == null || url.isBlank()) {
      return List.of();
    }
    final String path;
    try {
      path = java.net.URI.create(url.trim()).getPath();
    } catch (IllegalArgumentException e) {
      return List.of();
    }
    if (path == null) {
      return List.of();
    }
    final String trimmed = path.startsWith("/") ? path.substring(1) : path;
    final int slash = trimmed.indexOf('/');
    final String segment = (slash < 0 ? trimmed : trimmed.substring(0, slash))
        .toLowerCase(java.util.Locale.ROOT);
    final Matcher matcher = YEAR_SEGMENT.matcher(segment);
    if (!matcher.find()) {
      return List.of();
    }
    if (matcher.group(1) != null) {
      return List.of(YEAR_WORDS.get(matcher.group(1)));
    }
    if (matcher.group(2) != null) {
      return List.of("Year " + matcher.group(2));
    }
    return List.of("Reception");
  }

  private static Map<Integer, String> buildCalendarIdMap() {
    final Map<Integer, String> map = new LinkedHashMap<>();
    map.put(13, "Reception");
    map.put(4, "Year 1");
    map.put(5, "Year 2");
    map.put(6, "Year 3");
    map.put(7, "Year 4");
    map.put(8, "Year 5");
    map.put(9, "Year 6");
    return Map.copyOf(map);
  }

  /**
   * Maps a calendar sub-calendar id to a year group.
   *
   * @param calendarId the numeric {@code calid} from the school feed
   * @return the year group, or empty for whole-school, PTA, nursery or an unknown id
   */
  public static Optional<String> fromCalendarId(final int calendarId) {
    return Optional.ofNullable(BY_CALENDAR_ID.get(calendarId));
  }

  /**
   * Every sub-calendar id worth requesting, whole-school included.
   *
   * @return the calendar ids to pass as {@code calid}
   */
  public static List<Integer> allCalendarIds() {
    return List.of(1, 2, 3, 13, 4, 5, 6, 7, 8, 9);
  }

  /**
   * Whether a string names a real year group.
   *
   * @param candidate a user-supplied year group
   * @return true when it is one of {@link #ALL}
   */
  public static boolean isValid(final String candidate) {
    return candidate != null && ALL.contains(candidate);
  }

  /**
   * Keeps only real year groups from a client-supplied list.
   *
   * <p>Filtered rather than rejected: a selection containing one unrecognised value should still
   * answer for the recognised ones, and this is the only place a client's list reaches a query.
   *
   * @param candidates whatever the client sent, possibly null
   * @return the valid entries, de-duplicated and in the canonical order
   */
  public static List<String> sanitise(final List<String> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    return ALL.stream().filter(candidates::contains).toList();
  }
}
