package com.simonrowe.school.model;

/**
 * Where a piece of school content came from.
 *
 * <p><b>Declaration order is load-bearing.</b> {@link #precedence()} derives from the ordinal, so
 * reordering these members silently changes which source wins when two disagree about a date. That
 * is not theoretical: the school's term-dates web page still shows the previous academic year while
 * its calendar feed carries the current one, so a wrong ordering here answers "when is half term"
 * with last year's dates and sounds completely certain doing it.
 */
public enum SchoolSourceType {

  /** The school's calendar JSON/iCal feed. Authoritative for term structure and INSET days. */
  CALENDAR_FEED,

  /** A newsletter or letter from the school mailbox. The only current source for weekly detail. */
  EMAIL,

  /** A page on the school website. Frequently a year or more out of date. */
  WEBSITE_PAGE,

  /** A PDF linked from the website. Often the oldest copy of a fact that exists. */
  PDF;

  /**
   * Returns this source's authority, lower being more authoritative.
   *
   * @return the precedence rank, derived from declaration order
   */
  public int precedence() {
    return ordinal();
  }

  /**
   * Returns whichever of two sources should win when they disagree.
   *
   * @param a one source
   * @param b the other source
   * @return the more authoritative of the two
   */
  public static SchoolSourceType moreAuthoritative(
      final SchoolSourceType a, final SchoolSourceType b) {
    return a.precedence() <= b.precedence() ? a : b;
  }
}
