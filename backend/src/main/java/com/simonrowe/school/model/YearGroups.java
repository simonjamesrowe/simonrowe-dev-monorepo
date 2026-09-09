package com.simonrowe.school.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

  private YearGroups() {
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
