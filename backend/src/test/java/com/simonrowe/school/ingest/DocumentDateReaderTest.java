package com.simonrowe.school.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the date a document states for itself.
 *
 * <p>The case that produced this class is {@link #readsTheLetterheadDateOfaRealSchoolLetter()} —
 * a 2022 letter that the console displayed as published today, because the PDF path stamped every
 * file with the time of the crawl.
 */
class DocumentDateReaderTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 9);

  private final DocumentDateReader reader = new DocumentDateReader();

  @Test
  @DisplayName("reads the letterhead date of a real school letter")
  void readsTheLetterheadDateOfaRealSchoolLetter() {
    final String letter = """
        Thursday, 03 November 2022
        Re: Year 1 School trip to Leeds Castle – 21st November 2022
        Dear Parents and Carers,
        To support year 1 with our topic this term 'Monsters, Dragons and Castles' we have
        organised an educational visit to Leeds Castle in Kent on Monday 21st November 2022.
        """;

    assertThat(reader.fromText(letter, TODAY)).contains(LocalDate.of(2022, 11, 3));
  }

  @Test
  @DisplayName("takes the first credible date, which is the letterhead, not one it mentions")
  void prefersTheLetterheadOveraDateInTheBody() {
    // The trip date is 21 November; the letter is dated the 3rd. Recording the trip date as the
    // publication date would make the letter look like it arrived after the trip.
    final String letter = "Thursday, 03 November 2022\nRe: trip on 21st November 2022";

    assertThat(reader.fromText(letter, TODAY)).contains(LocalDate.of(2022, 11, 3));
  }

  @Test
  @DisplayName("reads numeric dates day-first, because these are British letters")
  void readsNumericDatesDayFirst() {
    assertThat(reader.fromText("Dated 03/11/2022, dear parents", TODAY))
        .contains(LocalDate.of(2022, 11, 3));
  }

  @Test
  @DisplayName("reads ISO and month-first forms the CMS also emits")
  void readsIsoAndMonthFirst() {
    assertThat(reader.fromText("Published 2026-07-15 by the office", TODAY))
        .contains(LocalDate.of(2026, 7, 15));
    assertThat(reader.fromText("November 3, 2022 — newsletter", TODAY))
        .contains(LocalDate.of(2022, 11, 3));
  }

  @Test
  @DisplayName("abbreviated month names are read")
  void readsAbbreviatedMonths() {
    assertThat(reader.fromText("12 Sept 2026", TODAY)).contains(LocalDate.of(2026, 9, 12));
    assertThat(reader.fromText("1 Dec. 2025", TODAY)).contains(LocalDate.of(2025, 12, 1));
  }

  @Test
  @DisplayName("a document with no date in its opening yields nothing, rather than a guess")
  void undatedDocumentYieldsNothing() {
    // A policy or a lunch menu carries no letterhead. Returning a guess here is what would drop
    // a current document as historic, so empty must mean empty.
    assertThat(reader.fromText("Wrap Around Care Policy\n\nAims and principles", TODAY)).isEmpty();
    assertThat(reader.fromText("", TODAY)).isEmpty();
    assertThat(reader.fromText(null, TODAY)).isEmpty();
  }

  @Test
  @DisplayName("only the opening is scanned, so a date on page three is not the document's date")
  void ignoresDatesBeyondTheOpening() {
    final String text = "Lunch menu\n" + "x".repeat(900) + "\n3 November 2022";

    assertThat(reader.fromText(text, TODAY)).isEmpty();
  }

  @Test
  @DisplayName("implausible dates are rejected rather than recorded")
  void rejectsImplausibleDates() {
    // A reference number or a phone number can match a numeric pattern; a date that does not
    // exist, or one far outside a school's document history, is a misparse.
    assertThat(reader.fromText("Invoice 31/02/2022 enclosed", TODAY)).isEmpty();
    assertThat(reader.fromText("Ref 01/01/1970", TODAY)).isEmpty();
    assertThat(reader.fromText("Planned for 1 January 2099", TODAY)).isEmpty();
  }
}
