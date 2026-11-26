package com.simonrowe.coparent.school;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SuggestionDetailsTest {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 27);

  @Test
  void takesTheMostAuthoritativeRowAndTheFirstReadableTime() {
    final SchoolEvent feed = row("feed", "Harriet's class trip", SchoolSourceType.CALENDAR_FEED,
        null, null);
    final SchoolEvent letter = row("letter", "Kilmorie Primary School Year 3 Local Area Walk",
        SchoolSourceType.EMAIL, "09.15 to 10.45am", "Ladywell Fields");

    final SchoolEventSuggestion.Details details =
        SuggestionDetails.from(List.of(letter, feed), null);

    assertThat(details.title()).isEqualTo("Harriet's class trip");
    assertThat(details.startTime()).isEqualTo("09:15");
    assertThat(details.endTime()).isEqualTo("10:45");
    assertThat(details.location()).isEqualTo("Ladywell Fields");
    assertThat(details.notes()).contains("Suggested from Term Time.");
  }

  @Test
  void dropsTheSchoolsNameFromTheFrontOfTitle() {
    assertThat(SuggestionDetails.tidyTitle("Kilmorie Primary School - New parent tour"))
        .isEqualTo("New parent tour");
    assertThat(SuggestionDetails.tidyTitle("Kilmorie Winter Fair")).isEqualTo("Winter Fair");
    assertThat(SuggestionDetails.tidyTitle("School trip")).isEqualTo("School trip");
  }

  @Test
  void timeTheSchoolWroteButNobodyCanReadGoesInTheNotes() {
    final SchoolEventSuggestion.Details details = SuggestionDetails.from(List.of(
        row("a", "Sports day", SchoolSourceType.EMAIL, "after lunch", null)), null);
    assertThat(details.allDay()).isTrue();
    assertThat(details.notes()).contains("Time: after lunch");
  }

  @Test
  void theFingerprintIgnoresDescriptionsButNotTimes() {
    final SchoolEvent before = row("a", "Trip", SchoolSourceType.EMAIL, "9am", null);
    final SchoolEvent reworded = new SchoolEvent("a", "Trip", DAY, DAY, false,
        SchoolEvent.EventType.TRIP, List.of(), "2026/27", SchoolSourceType.EMAIL, "doc",
        Visibility.PUBLIC, "Worded differently this time", null, "9am", null);
    final SchoolEvent moved = row("a", "Trip", SchoolSourceType.EMAIL, "10am", null);

    assertThat(SuggestionDetails.fingerprint(List.of(reworded)))
        .isEqualTo(SuggestionDetails.fingerprint(List.of(before)));
    assertThat(SuggestionDetails.fingerprint(List.of(moved)))
        .isNotEqualTo(SuggestionDetails.fingerprint(List.of(before)));
    assertThat(SuggestionDetails.fingerprint(List.of())).isEqualTo(SuggestionDetails.GONE);
  }

  private static SchoolEvent row(final String id, final String title,
      final SchoolSourceType source, final String time, final String location) {
    return new SchoolEvent(id, title, DAY, DAY, time == null, SchoolEvent.EventType.TRIP,
        List.of(), "2026/27", source, "doc", Visibility.PUBLIC, "About it", location, time,
        null);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "Click https://example.com to book", "Visit www.example.com", "Email head@example.com",
      "<b>Trip</b>", "Trip\u0007", ""})
  void refusesModelTitleThatIsNotJustName(final String title) {
    assertThat(SuggestionDetails.plausibleTitle(title)).isFalse();
    assertThat(SuggestionDetails.from(List.of(
        row("a", "Kilmorie Winter Fair", SchoolSourceType.CALENDAR_FEED, null, null)), title)
        .title()).isEqualTo("Winter Fair");
  }

  @Test
  void keepsModelTitleMadeOfTheSchoolsOwnWords() {
    assertThat(SuggestionDetails.from(List.of(
        row("a", "Harriet's class trip", SchoolSourceType.CALENDAR_FEED, null, null),
        row("b", "Year 3 Local Area Walk – Harriet's class", SchoolSourceType.EMAIL, null,
            null)),
        "Local Area Walk – Harriet's class").title())
        .isEqualTo("Local Area Walk – Harriet's class");
  }

  @Test
  void refusesModelTitleThatAddsWordsTheSchoolDidNotUse() {
    // From production: the model took the winner's name from the description.
    final List<SchoolEvent> rows = List.of(row("a",
        "Kilmorie Primary School UK Parliament Week Art Competition winners announcement",
        SchoolSourceType.CALENDAR_FEED, null, null));

    assertThat(SuggestionDetails.groundedIn(
        "Robin Example Art Competition Winners Announcement", rows)).isFalse();
    assertThat(SuggestionDetails.from(rows,
        "Robin Example Art Competition Winners Announcement").title())
        .isEqualTo("UK Parliament Week Art Competition winners announcement");
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "Enrichment Clubs Start|Enrichment clubs start",
      "INSET Day|Inset Day",
      "Harriet's Class Trip|Harriet's class trip",
      "Year 5 & 6 Winter Sing Along|Kilmorie Primary School Year 5 and Year 6 Winter Sing Along",
      "New Year's Day|New Year’s Day",
  })
  void acceptsReorderedOrShortenedSchoolWords(final String title, final String source) {
    assertThat(SuggestionDetails.groundedIn(title,
        List.of(row("a", source, SchoolSourceType.CALENDAR_FEED, null, null)))).isTrue();
  }
}
