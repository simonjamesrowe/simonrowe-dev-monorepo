package com.simonrowe.coparent.school;

import java.time.LocalTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a start and end time out of a school event's free-text time.
 *
 * <p>The school writes times every way there is: {@code 09.15 to 10.45am}, {@code 2:00pm –
 * 5:00pm}, {@code 3 PM}, {@code 09:30}, {@code All Day}. A calendar event needs {@code HH:mm}, so
 * this is the fallback when the model is off or returned a time that does not parse, and the
 * check on what it returned.
 *
 * <p>A bare number with neither minutes nor am/pm is never a time ("Year 3 only"). A time with no
 * am/pm takes the range's ({@code 3:30 - 4:30pm} starts at 3:30pm), and on its own an hour from 1
 * to 7 is afternoon, because nothing at a primary school starts at 3am.
 */
final class SchoolTimes {

  private static final int MAX_SCANNED = 64;
  private static final int NOON = 12;
  private static final int LAST_AFTERNOON_HOUR = 7;

  /** One time; possessive throughout. */
  private static final String TIME =
      "(\\d{1,2}+)(?:[:.](\\d{2}+))?+\\s*+(?:([ap])\\.?+\\s*+m\\.?+)?+";
  private static final Pattern RANGE = Pattern.compile(
      TIME + "(?:\\s*+(?:-|–|—|to|until|till)\\s*+" + TIME + ")?+",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern HH_MM = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

  private SchoolTimes() {
  }

  /** A start time and, when the source gave one, an end time. */
  record Range(LocalTime start, LocalTime end) {
  }

  /**
   * Reads the first time or time range in the text.
   *
   * @param text the source's time text, possibly null
   * @return the range, or empty when the text names no time
   */
  static Optional<Range> parse(final String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    final String scanned = text.length() > MAX_SCANNED ? text.substring(0, MAX_SCANNED) : text;
    final Matcher matcher = RANGE.matcher(scanned);
    while (matcher.find()) {
      final boolean startReadable = matcher.group(2) != null || matcher.group(3) != null;
      final boolean hasEnd = matcher.group(4) != null;
      final boolean endReadable = hasEnd && (matcher.group(5) != null || matcher.group(6) != null);
      if (!startReadable && !endReadable) {
        continue;
      }
      final String endMeridiem = hasEnd ? matcher.group(6) : null;
      final LocalTime end = endReadable
          ? toTime(matcher.group(4), matcher.group(5), endMeridiem, null) : null;
      final LocalTime start = toTime(matcher.group(1), matcher.group(2), matcher.group(3),
          end == null ? null : endMeridiem);
      if (start == null) {
        continue;
      }
      if (end != null && end.isAfter(start)) {
        return Optional.of(new Range(start, end));
      }
      if (end != null && matcher.group(3) == null && endMeridiem != null) {
        // "11:30 - 1pm": the start is the morning one.
        final LocalTime morning = toTime(matcher.group(1), matcher.group(2), "a", null);
        if (morning != null && end.isAfter(morning)) {
          return Optional.of(new Range(morning, end));
        }
      }
      return Optional.of(new Range(start, null));
    }
    return Optional.empty();
  }

  /**
   * Whether a value is a 24-hour {@code HH:mm} time, the only form a calendar event stores.
   *
   * @param value a candidate, possibly null
   * @return true when it is one
   */
  static boolean isClockTime(final String value) {
    return value != null && HH_MM.matcher(value).matches();
  }

  /** Formats a time as the calendar stores it. */
  static String format(final LocalTime time) {
    return time == null ? null : "%02d:%02d".formatted(time.getHour(), time.getMinute());
  }

  private static LocalTime toTime(final String hourText, final String minuteText,
      final String meridiem, final String inherited) {
    int hour = Integer.parseInt(hourText);
    final int minute = minuteText == null ? 0 : Integer.parseInt(minuteText);
    if (minute > 59) {
      return null;
    }
    final String marker = meridiem != null ? meridiem : inherited;
    if (marker != null) {
      if (hour < 1 || hour > NOON) {
        return null;
      }
      final boolean pm = marker.equalsIgnoreCase("p");
      hour = hour % NOON + (pm ? NOON : 0);
    } else if (hour >= 1 && hour <= LAST_AFTERNOON_HOUR) {
      hour += NOON;
    } else if (hour > 23) {
      return null;
    }
    return LocalTime.of(hour, minute);
  }
}
