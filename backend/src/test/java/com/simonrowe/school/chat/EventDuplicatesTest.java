package com.simonrowe.school.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolEvent.EventType;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** One event reported by several sources under different titles is shown once. */
class EventDuplicatesTest {

  private static final SchoolSourceType FEED = SchoolSourceType.CALENDAR_FEED;
  private static final SchoolSourceType EMAIL = SchoolSourceType.EMAIL;
  private static final SchoolSourceType PAGE = SchoolSourceType.WEBSITE_PAGE;
  private static final SchoolSourceType PDF = SchoolSourceType.PDF;

  private static SchoolEvent event(final String title, final String start, final String end,
      final EventType type, final List<String> years, final SchoolSourceType source,
      final String time) {
    return new SchoolEvent(title, title, LocalDate.parse(start), LocalDate.parse(end),
        true, type, years, "2026/27", source, "doc:" + title, Visibility.PUBLIC, null, null,
        time, null);
  }

  private static SchoolEvent event(final String title, final String start, final String end,
      final EventType type, final SchoolSourceType source) {
    return event(title, start, end, type, List.of(), source, null);
  }

  private static List<String> titles(final List<SchoolEvent> events) {
    return events.stream().map(SchoolEvent::title).toList();
  }

  @Test
  @DisplayName("the calendar pane of 2026-10-13: 29 rows were 19 events")
  void productionCalendar() {
    final List<SchoolEvent> stored = List.of(
        event("Autumn term 1", "2026-09-03", "2026-10-23", EventType.TERM_BOUNDARY, FEED),
        event("Autumn Term Enrichment Clubs", "2026-09-21", "2026-11-30",
            EventType.TERM_BOUNDARY, PAGE),
        event("Kilmorie Primary School Autumn Term Enrichment Clubs start and end",
            "2026-09-21", "2026-11-30", EventType.TERM_BOUNDARY, PAGE),
        event("Autumn Term 2026", "2026-09-21", "2026-12-04", EventType.TERM_BOUNDARY, PAGE),
        event("Kilmorie Enrichment Autumn Term 2026", "2026-09-21", "2026-12-04",
            EventType.TERM_BOUNDARY, PAGE),
        event("Kilmorie Primary School Parents Evening October 2026", "2026-10-12",
            "2026-10-16", EventType.OTHER, PAGE),
        event("Parent's evening week", "2026-10-13", "2026-10-17", EventType.OTHER, EMAIL),
        event("New parent tour", "2026-10-14", "2026-10-14", EventType.OTHER, List.of(), FEED,
            "09:30"),
        event("Eleni's Class Trip", "2026-10-14", "2026-10-14", EventType.TRIP,
            List.of("Year 4"), FEED, "All Day"),
        event("Sixth Form Open Evening", "2026-10-15", "2026-10-15", EventType.OTHER,
            List.of("Year 6"), SchoolSourceType.PASTED_NOTE, null),
        event("Jo's Class Assembly", "2026-10-15", "2026-10-15", EventType.OTHER, List.of(),
            FEED, "9:20am"),
        event("Closing date for medical and social applications", "2026-10-16", "2026-10-16",
            EventType.OTHER, List.of("Year 7"), SchoolSourceType.EXTERNAL_PAGE, null),
        event("New parent tour", "2026-10-16", "2026-10-16", EventType.OTHER, List.of(), FEED,
            "13:30"),
        event("Kilmorie Primary School Year 6 residential trip to PGL", "2026-10-19",
            "2026-10-23", EventType.TRIP, List.of("Year 6"), PAGE, null),
        event("Year 6 Trip to PGL Grovesnor Hall", "2026-10-19", "2026-10-23", EventType.TRIP,
            List.of("Year 6"), EMAIL, null),
        event("New parent tour", "2026-10-20", "2026-10-20", EventType.OTHER, List.of(), FEED,
            "09:30"),
        event("Kilmorie Primary School Peaceful Pod clothing drop-off", "2026-10-22",
            "2026-10-22", EventType.OTHER, PAGE),
        event("FOK Costume Sale", "2026-10-22", "2026-10-22", EventType.OTHER, List.of(), FEED,
            "3:30pm – 9:00pm"),
        event("Elis' Class Assembly", "2026-10-22", "2026-10-22", EventType.OTHER,
            List.of("Year 3"), FEED, "All Day"),
        event("The Little Recyclers lost property collection", "2026-10-23", "2026-10-23",
            EventType.OTHER, EMAIL),
        event("Last day of Michaelmas Term 1", "2026-10-23", "2026-10-23",
            EventType.TERM_BOUNDARY, List.of("Year 6"), EMAIL, null),
        event("Autumn 2026 half-term", "2026-10-26", "2026-10-30", EventType.HALF_TERM, EMAIL),
        event("Autumn Half-Term", "2026-10-26", "2026-10-30", EventType.HALF_TERM, PDF),
        event("Kilmorie Autumn Half Term 2026", "2026-10-26", "2026-10-30",
            EventType.HALF_TERM, PAGE),
        event("Kilmorie Primary School Autumn Half Term", "2026-10-26", "2026-10-30",
            EventType.HALF_TERM, PAGE),
        event("Kilmorie Primary School Half Term", "2026-10-26", "2026-10-30",
            EventType.HALF_TERM, PDF),
        event("Half Term", "2026-10-26", "2026-10-31", EventType.HALF_TERM, List.of(), FEED,
            "All Day"),
        event("Secondary School Application Deadline", "2026-10-31", "2026-10-31",
            EventType.OTHER, List.of("Year 6"), SchoolSourceType.PASTED_NOTE, null),
        event("Closing date for secondary transfer applications", "2026-10-31", "2026-10-31",
            EventType.OTHER, List.of("Year 7"), SchoolSourceType.EXTERNAL_PAGE, null));

    final List<SchoolEvent> shown = EventDuplicates.collapse(stored);

    assertThat(titles(shown)).containsExactly(
        "Autumn term 1",
        "Autumn Term Enrichment Clubs",
        "Parent's evening week",
        "New parent tour",
        "Eleni's Class Trip",
        "Sixth Form Open Evening",
        "Jo's Class Assembly",
        "Closing date for medical and social applications",
        "New parent tour",
        "Year 6 Trip to PGL Grovesnor Hall",
        "New parent tour",
        "Kilmorie Primary School Peaceful Pod clothing drop-off",
        "FOK Costume Sale",
        "Elis' Class Assembly",
        "The Little Recyclers lost property collection",
        "Last day of Michaelmas Term 1",
        "Half Term",
        "Secondary School Application Deadline",
        "Closing date for secondary transfer applications");
    assertThat(shown.get(0).eventType()).isEqualTo(EventType.TERM_BOUNDARY);
    assertThat(shown.get(1).eventType()).isEqualTo(EventType.CLUB);
  }

  @Test
  @DisplayName("multi-day events whose sources disagree within a week are one event")
  void multiDaySlack() {
    final List<SchoolEvent> shown = EventDuplicates.collapse(List.of(
        event("Kilmorie Primary School Parents Evening October 2026", "2026-10-12",
            "2026-10-16", EventType.OTHER, PAGE),
        event("Parent's evening week", "2026-10-13", "2026-10-17", EventType.OTHER, EMAIL),
        event("Parents' evenings", "2026-10-19", "2026-10-23", EventType.OTHER, EMAIL)));

    assertThat(titles(shown)).containsExactly("Parent's evening week", "Parents' evenings");
  }

  @Test
  @DisplayName("a club programme typed as a term boundary is shown as one club")
  void clubTypedAsTermBoundary() {
    final List<SchoolEvent> shown = EventDuplicates.collapse(List.of(
        event("Autumn Term 2026", "2026-09-21", "2026-12-04", EventType.TERM_BOUNDARY, PDF),
        event("Kilmorie Enrichment Autumn Term 2026", "2026-09-21", "2026-12-04",
            EventType.TERM_BOUNDARY, PDF),
        event("Autumn Term Enrichment Clubs", "2026-09-21", "2026-11-30",
            EventType.TERM_BOUNDARY, PDF)));

    assertThat(shown).singleElement().satisfies(event -> {
      assertThat(event.title()).isEqualTo("Autumn Term Enrichment Clubs");
      assertThat(event.eventType()).isEqualTo(EventType.CLUB);
    });
  }

  @Test
  @DisplayName("the calendar feed's term dates are never folded into a club")
  void feedTermIsNeverFolded() {
    final List<SchoolEvent> shown = EventDuplicates.collapse(List.of(
        event("Autumn term", "2026-09-07", "2026-12-18", EventType.TERM_BOUNDARY, FEED),
        event("Autumn term clubs", "2026-09-07", "2026-12-18", EventType.CLUB, PAGE)));

    assertThat(shown).extracting(SchoolEvent::eventType)
        .containsExactly(EventType.TERM_BOUNDARY, EventType.CLUB);
  }

  @Test
  @DisplayName("the most authoritative source is the row shown, whatever its title")
  void authoritativeSourceWins() {
    final List<SchoolEvent> shown = EventDuplicates.collapse(List.of(
        event("Half term", "2026-10-26", "2026-10-30", EventType.HALF_TERM, PDF),
        event("Kilmorie Primary School Autumn Half Term 2026", "2026-10-26", "2026-10-30",
            EventType.HALF_TERM, FEED)));

    assertThat(shown).extracting(SchoolEvent::sourceType).containsExactly(FEED);
  }

  @Test
  @DisplayName("among equally authoritative rows, the one carrying a time is shown")
  void moreDetailWins() {
    final List<SchoolEvent> shown = EventDuplicates.collapse(List.of(
        event("Harvest Festival", "2026-10-02", "2026-10-02", EventType.OTHER, EMAIL),
        event("Kilmorie Harvest Festival", "2026-10-02", "2026-10-02", EventType.OTHER,
            List.of(), EMAIL, "2:30pm")));

    assertThat(shown).extracting(SchoolEvent::time).containsExactly("2:30pm");
  }

  @Test
  @DisplayName("a generic title does not chain two classes' assemblies into one")
  void noChaining() {
    final List<SchoolEvent> shown = EventDuplicates.collapse(List.of(
        event("Class Assembly", "2026-10-15", "2026-10-15", EventType.OTHER, EMAIL),
        event("Jo's Class Assembly", "2026-10-15", "2026-10-15", EventType.OTHER, FEED),
        event("Elis' Class Assembly", "2026-10-15", "2026-10-15", EventType.OTHER, FEED)));

    assertThat(titles(shown)).containsExactly("Jo's Class Assembly", "Elis' Class Assembly");
  }

  @Test
  @DisplayName("term-long clubs sharing a word and a span stay separate")
  void clubsStaySeparate() {
    final List<SchoolEvent> shown = EventDuplicates.collapse(List.of(
        event("Chess Club", "2026-09-21", "2026-11-30", EventType.CLUB, PDF),
        event("Football Club", "2026-09-21", "2026-11-30", EventType.CLUB, PDF)));

    assertThat(shown).hasSize(2);
  }

  @Test
  @DisplayName("the same trip for two different year groups stays two trips")
  void differentYearGroups() {
    final List<SchoolEvent> shown = EventDuplicates.collapse(List.of(
        event("Residential trip", "2026-10-19", "2026-10-23", EventType.TRIP,
            List.of("Year 5"), EMAIL, null),
        event("Residential trip", "2026-10-19", "2026-10-23", EventType.TRIP,
            List.of("Year 6"), PAGE, null)));

    assertThat(shown).hasSize(2);
  }

  @Test
  @DisplayName("events on different days, or of different types, are never merged")
  void datesAndTypeMustMatch() {
    final List<SchoolEvent> shown = EventDuplicates.collapse(List.of(
        event("INSET day", "2026-11-02", "2026-11-02", EventType.INSET, FEED),
        event("INSET day", "2026-11-03", "2026-11-03", EventType.INSET, FEED),
        event("Book Week", "2026-11-02", "2026-11-06", EventType.OTHER, EMAIL),
        event("Anti-bullying Week", "2026-11-02", "2026-11-06", EventType.OTHER, EMAIL),
        event("Book Week", "2026-11-02", "2026-11-06", EventType.CLUB, EMAIL)));

    assertThat(shown).hasSize(5);
  }

  @Test
  @DisplayName("a term's number is significant, a calendar year and the school's name are not")
  void significantWords() {
    assertThat(EventDuplicates.significantWords("Autumn term 1"))
        .containsExactlyInAnyOrder("autumn", "term", "1");
    assertThat(EventDuplicates.significantWords("Kilmorie Primary School Autumn Half-Term 2026"))
        .containsExactlyInAnyOrder("autumn", "half", "term");
    assertThat(EventDuplicates.significantWords("Parents' Evenings in October"))
        .containsExactlyInAnyOrder("parent", "evening");
    assertThat(EventDuplicates.significantWords("Class bus"))
        .containsExactlyInAnyOrder("class", "bus");
    assertThat(EventDuplicates.significantWords(null)).isEmpty();
  }
}
