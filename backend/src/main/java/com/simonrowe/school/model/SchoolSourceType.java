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

  /**
   * A page fetched from a host that is <b>not</b> the school's — a secondary school's own
   * admissions page, say.
   *
   * <p>Distinct from {@link #WEBSITE_PAGE} because
   * {@code SchoolQueryService.communicationsBetween} answers "what did the school send or
   * publish", from an allowlist of source types. A third-party page filed as
   * {@code WEBSITE_PAGE} joins that answer, so "what did the school publish last week" would
   * list another school's open-evening page as though Kilmorie had sent it. That was harmless
   * while every fetched third-party page was {@link Visibility#RESTRICTED}; notes pasted into
   * the admin console are public, and so are the pages fetched from them.
   *
   * <p>Only new fetches are labelled this way. Existing rows keep whatever type they were
   * stored under — the document id is derived from the source type, so relabelling in place is
   * not possible without orphaning them, and they are all restricted anyway.
   */
  EXTERNAL_PAGE,

  /** A PDF linked from the website. Often the oldest copy of a fact that exists. */
  PDF,

  /**
   * Text an administrator pasted into the console — a WhatsApp message from a parents' group,
   * a letter that never reached the mailbox.
   *
   * <p><b>Last, and so least authoritative of all.</b> It is a transcription of something
   * somebody else said, with no publisher behind it, so anything a school publishes itself
   * beats it. That matters in exactly one case and it is the common one: a note says
   * "Harris Boys — 17 Sept" and the school's own page, fetched from the link in that note,
   * says the same evening with a time and a booking link. Both land on the same event id, and
   * this ordering is what makes the page win.
   */
  PASTED_NOTE;

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
