package com.simonrowe.school.model;

import java.time.LocalDate;
import java.time.Month;

/**
 * Turns a date into the UK academic year it falls in, e.g. {@code 2026/27}.
 *
 * <p>Every extracted fact carries one. Without it a newsletter from last September is
 * indistinguishable from this one, and "when is half term" gets answered with dates that have
 * already passed — stated with complete confidence, because nothing in the retrieved text says
 * which year it belonged to.
 *
 * <p>The boundary is 1 September. A date in September or later belongs to the year starting that
 * September; anything from January to August belongs to the year that started the previous
 * September. INSET days in late August are the awkward case and land in the *previous* year by
 * this rule — deliberate, because they are published as part of the outgoing year's calendar.
 */
public final class AcademicYear {

  private static final int CENTURY = 100;

  private AcademicYear() {
  }

  /**
   * The academic year containing a date.
   *
   * @param date any date
   * @return the academic year label, e.g. {@code 2026/27}
   */
  public static String of(final LocalDate date) {
    final int startYear = date.getMonthValue() >= Month.SEPTEMBER.getValue()
        ? date.getYear()
        : date.getYear() - 1;
    return format(startYear);
  }

  /**
   * Formats an academic year from its starting calendar year.
   *
   * @param startYear the September in which the year starts
   * @return the label, e.g. {@code 2026/27}
   */
  public static String format(final int startYear) {
    return "%d/%02d".formatted(startYear, (startYear + 1) % CENTURY);
  }

  /**
   * The academic year that contains today.
   *
   * @param today the current date, injected rather than read from the clock so tests are not
   *     time-dependent and so a date question asked on 31 August gives a reproducible answer
   * @return the current academic year label
   */
  public static String current(final LocalDate today) {
    return of(today);
  }
}
