package com.simonrowe.school.ingest;

import java.time.LocalDate;
import java.time.Month;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads the date a document gives for itself.
 *
 * <p>Exists because website PDFs were stamped with the time they were fetched. A letter about a
 * Year 1 trip to Leeds Castle in November 2022 was recorded as published today, which is wrong in
 * three separate ways: the admin console shows the wrong date, the ingest cutoff cannot recognise
 * it as historic, and — worst — {@code publishedAt} is the <b>reference date</b>
 * {@code SchoolEventExtractor} resolves relative dates against, so "next Friday" in a 2022 letter
 * became a date in 2026 and a bogus event.
 *
 * <p>Only the opening of a document is scanned. A school letter leads with its own date; a date
 * appearing three pages in belongs to something the letter is talking about, not to the letter,
 * and taking it would be worse than having no date at all.
 */
@Component
public class DocumentDateReader {

  /** How much of the opening to scan. A letterhead date is always well inside this. */
  private static final int LEAD_CHARS = 600;

  /** Nothing older than this is a credible school document date; below it, assume a misparse. */
  private static final int EARLIEST_YEAR = 2005;

  /** How far ahead a document may credibly be dated, guarding against a parsed reference number. */
  private static final int MAX_YEARS_AHEAD = 2;

  /** {@code 3 November 2022}, {@code 03rd November 2022}, {@code Thursday, 3 Nov 2022}. */
  private static final Pattern DAY_MONTH_YEAR = Pattern.compile(
      "(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+"
          + "(january|february|march|april|may|june|july|august|september|october|november"
          + "|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)\\.?"
          + "\\s+(\\d{4})\\b");

  /** {@code November 3, 2022} — less common here, but the CMS emits it on some pages. */
  private static final Pattern MONTH_DAY_YEAR = Pattern.compile(
      "(?i)\\b(january|february|march|april|may|june|july|august|september|october|november"
          + "|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)\\.?\\s+"
          + "(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b");

  /** ISO, which the CMS uses in a few machine-written places. */
  private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");

  /**
   * Numeric British dates. Deliberately last: {@code 03/11/2022} is unambiguous only once the
   * day-first convention is assumed, and a two-digit year is not worth guessing at.
   */
  private static final Pattern NUMERIC_BRITISH = Pattern.compile(
      "\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})\\b");

  /**
   * The date a document states for itself, if it states one.
   *
   * @param text the document's extracted text
   * @param today the current date, for the plausibility bound
   * @return the date, or empty when the opening carries none that is credible
   */
  public Optional<LocalDate> fromText(final String text, final LocalDate today) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    final String lead = text.length() > LEAD_CHARS ? text.substring(0, LEAD_CHARS) : text;

    return firstOf(DAY_MONTH_YEAR, lead, m -> build(
            Integer.parseInt(m.group(3)), monthOf(m.group(2)), Integer.parseInt(m.group(1))), today)
        .or(() -> firstOf(MONTH_DAY_YEAR, lead,
            m -> build(Integer.parseInt(m.group(3)), monthOf(m.group(1)),
                Integer.parseInt(m.group(2))), today))
        .or(() -> firstOf(ISO, lead, m -> build(Integer.parseInt(m.group(1)),
            Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))), today))
        .or(() -> firstOf(NUMERIC_BRITISH, lead, m -> build(Integer.parseInt(m.group(3)),
            Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1))), today));
  }

  private Optional<LocalDate> firstOf(final Pattern pattern, final String lead,
      final java.util.function.Function<Matcher, LocalDate> build, final LocalDate today) {
    final Matcher matcher = pattern.matcher(lead);
    while (matcher.find()) {
      final LocalDate candidate = build.apply(matcher);
      if (isCredible(candidate, today)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  private static boolean isCredible(final LocalDate date, final LocalDate today) {
    return date != null
        && date.getYear() >= EARLIEST_YEAR
        && !date.isAfter(today.plusYears(MAX_YEARS_AHEAD));
  }

  private static LocalDate build(final int year, final int month, final int day) {
    if (month < 1 || month > 12 || day < 1 || day > 31) {
      return null;
    }
    try {
      return LocalDate.of(year, month, day);
    } catch (RuntimeException e) {
      // 31 February and friends. A date that does not exist is a misparse, not a document date.
      return null;
    }
  }

  private static int monthOf(final String name) {
    final String key = name.toLowerCase(Locale.ROOT).replace(".", "");
    for (Month month : Month.values()) {
      final String full = month.name().toLowerCase(Locale.ROOT);
      if (full.equals(key) || full.startsWith(key)) {
        return month.getValue();
      }
    }
    return -1;
  }
}
