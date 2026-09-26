package com.simonrowe.coparent.calendar;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The one recurrence vocabulary the calendar can render.
 *
 * <p>The browser expands a weekly event by comparing each day against these exact lowercase
 * names, so a series stored as {@code "TUE"} or {@code "Tuesday"} is saved successfully and then
 * produces no occurrences at all: it exists and is never shown. Every writer therefore stores
 * the canonical names, and the domain refuses anything else.
 */
public final class Recurrence {

  public static final List<String> FREQUENCIES = List.of("daily", "weekly");

  public static final List<String> DAYS = List.of(
      "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");

  private Recurrence() {
  }

  /**
   * Maps a weekday spelling to its canonical name. Any prefix of at least two letters is
   * accepted ({@code TU}, {@code tue}, {@code Tues}, {@code Tuesday}); two letters already
   * identify every weekday uniquely, so no prefix is ambiguous.
   */
  public static Optional<String> canonicalDay(final String value) {
    if (value == null) {
      return Optional.empty();
    }
    final String lower = value.trim().toLowerCase(Locale.ROOT);
    if (lower.length() < 2) {
      return Optional.empty();
    }
    return DAYS.stream().filter(day -> day.startsWith(lower)).findFirst();
  }

  /** Maps a frequency spelling to its canonical name, or empty when it is not supported. */
  public static Optional<String> canonicalFrequency(final String value) {
    if (value == null) {
      return Optional.empty();
    }
    final String lower = value.trim().toLowerCase(Locale.ROOT);
    return FREQUENCIES.contains(lower) ? Optional.of(lower) : Optional.empty();
  }
}
