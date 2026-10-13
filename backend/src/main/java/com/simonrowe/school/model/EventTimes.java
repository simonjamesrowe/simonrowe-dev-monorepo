package com.simonrowe.school.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the time of day out of an event's free-text {@code time} field, for ordering only.
 *
 * <p>The field is whatever the source wrote: the calendar feed sends {@code 6:00pm} or
 * {@code All Day}, and events extracted from letters carry things like {@code 9.15am} or
 * {@code 3:30 - 4:30pm}. Nothing displays the parsed value; the source's own wording is what a
 * reader sees. This only decides which of a day's events is listed first.
 */
public final class EventTimes {

  /** Sorts before every timed event: an all-day item frames the day. */
  public static final int ALL_DAY = -1;

  /** Sorts after every timed event: a time nobody could read is the least useful line. */
  public static final int UNKNOWN = Integer.MAX_VALUE;

  /**
   * Long enough for any real time string; the cap bounds the scan of a field that comes from a
   * model reading a letter, not from a schema.
   */
  private static final int MAX_SCANNED = 64;

  private static final int MINUTES_PER_HOUR = 60;
  private static final int NOON = 12;
  private static final int HOURS_PER_DAY = 24;

  /**
   * An hour, optional minutes after a colon or dot, then an optional am/pm. Possessive
   * throughout, so there is nothing for the engine to backtrack into.
   */
  private static final Pattern TIME = Pattern.compile(
      "(\\d{1,2}+)(?:[:.](\\d{2}+))?+\\s*+(?:([ap])\\.?+m)?+", Pattern.CASE_INSENSITIVE);

  private EventTimes() {
  }

  /**
   * Minutes after midnight that an event starts, for sorting a day's events.
   *
   * <p>A bare number with neither minutes nor am/pm is not taken as a time, so a stray "3" in
   * "Year 3 only" cannot put an event at three in the morning.
   *
   * @param time the source's time text, possibly null
   * @param allDay whether the event is all day
   * @return minutes after midnight, {@link #ALL_DAY} or {@link #UNKNOWN}
   */
  public static int minutesOf(final String time, final boolean allDay) {
    if (time != null) {
      final String scanned = time.length() > MAX_SCANNED ? time.substring(0, MAX_SCANNED) : time;
      final Matcher matcher = TIME.matcher(scanned);
      while (matcher.find()) {
        final int minutes = toMinutes(matcher.group(1), matcher.group(2), matcher.group(3));
        if (minutes >= 0) {
          return matcher.group(3) == null
              ? withRangeMeridiem(matcher, minutes)
              : minutes;
        }
      }
    }
    return allDay ? ALL_DAY : UNKNOWN;
  }

  /**
   * A start time written without am/pm takes it from the end of the range: {@code 3:30 - 4:30pm}
   * starts at 3:30pm, not 3:30am. If that would put the start after the end, as with
   * {@code 11:30 - 1pm}, the start is the morning one.
   *
   * @param matcher positioned on the start time
   * @param asWritten the start read as a 24-hour time
   * @return the start in minutes after midnight
   */
  private static int withRangeMeridiem(final Matcher matcher, final int asWritten) {
    final int hour = Integer.parseInt(matcher.group(1));
    if (hour < 1 || hour > NOON) {
      return asWritten;
    }
    while (matcher.find()) {
      final String meridiem = matcher.group(3);
      if (meridiem == null) {
        continue;
      }
      final int end = toMinutes(matcher.group(1), matcher.group(2), meridiem);
      if (end < 0) {
        return asWritten;
      }
      final int minute = asWritten % MINUTES_PER_HOUR;
      final int start = toMinutes(Integer.toString(hour), Integer.toString(minute), meridiem);
      final int halfDay = NOON * MINUTES_PER_HOUR;
      return start > end ? start - halfDay : start;
    }
    return asWritten;
  }

  private static int toMinutes(final String hourText, final String minuteText,
      final String meridiem) {
    if (minuteText == null && meridiem == null) {
      return -1;
    }
    final int minute = minuteText == null ? 0 : Integer.parseInt(minuteText);
    int hour = Integer.parseInt(hourText);
    if (minute >= MINUTES_PER_HOUR) {
      return -1;
    }
    if (meridiem == null) {
      return hour < HOURS_PER_DAY ? hour * MINUTES_PER_HOUR + minute : -1;
    }
    if (hour < 1 || hour > NOON) {
      return -1;
    }
    final boolean pm = Character.toLowerCase(meridiem.charAt(0)) == 'p';
    hour = hour % NOON + (pm ? NOON : 0);
    return hour * MINUTES_PER_HOUR + minute;
  }
}
