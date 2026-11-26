package com.simonrowe.coparent.school;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.coparent.model.Child;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

/**
 * Shaped on the school's real calendar for late November 2026, with the class names changed:
 * class events are mostly filed whole-school, and one event is often several rows.
 */
class SchoolEventMatcherTest {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 27);

  // Year 3 in 2026/27, in Harriet's class; the other Year 3 class is Omar's.
  private final Child younger = child("Robin Example", LocalDate.of(2019, 3, 10), "Harriet");
  // Year 6 in 2026/27, in Priya's class.
  private final Child older = child("Sam Example", LocalDate.of(2016, 2, 1), "Priya");

  @Test
  void classEventFiledWholeSchoolGoesOnlyToThatClass() {
    final SchoolEvent ours = event("Harriet's class trip", List.of());
    final SchoolEvent theirs = event("Omar's class trip", List.of());

    assertThat(SchoolEventMatcher.childrenFor(ours, List.of(younger, older)))
        .containsExactly(younger);
    assertThat(SchoolEventMatcher.childrenFor(theirs, List.of(younger, older))).isEmpty();
  }

  @Test
  void yearEventGoesToThatYearOnly() {
    assertThat(SchoolEventMatcher.childrenFor(
        event("Year 5 and Year 6 Winter Sing Along", List.of("Year 5", "Year 6")),
        List.of(younger, older))).containsExactly(older);
    assertThat(SchoolEventMatcher.childrenFor(
        event("Year 3 Local Area Walk – Harriet's class", List.of("Year 3")),
        List.of(younger, older))).containsExactly(younger);
    assertThat(SchoolEventMatcher.childrenFor(
        event("Year 3 Local Area Walk – Omar's class", List.of("Year 3")),
        List.of(younger, older))).isEmpty();
  }

  @Test
  void untaggedEventNamingYearIsForThatYear() {
    assertThat(SchoolEventMatcher.childrenFor(event("Year 3 swimming starts", List.of()),
        List.of(younger, older))).containsExactly(younger);
  }

  @Test
  void wholeSchoolEventGoesToEveryone() {
    assertThat(SchoolEventMatcher.childrenFor(event("Winter Fair", List.of()),
        List.of(younger, older))).containsExactly(younger, older);
  }

  @Test
  void anotherYearsOrNurseryEventGoesToNobody() {
    assertThat(SchoolEventMatcher.childrenFor(event("Christmas Fair", List.of("Year 1")),
        List.of(younger, older))).isEmpty();
    assertThat(SchoolEventMatcher.childrenFor(event("Winter Performance", List.of("Nursery")),
        List.of(younger, older))).isEmpty();
  }

  @Test
  void withNoClassKnownClassEventForTheirYearIsOffered() {
    final Child unknownClass = child("Robin Example", LocalDate.of(2019, 3, 10), null);
    assertThat(SchoolEventMatcher.isFor(
        event("Year 3 Local Area Walk – Omar's class", List.of("Year 3")), unknownClass))
        .isTrue();
    // With no year to go on, another class's event is not.
    assertThat(SchoolEventMatcher.isFor(event("Omar's class trip", List.of()), unknownClass))
        .isFalse();
  }

  @Test
  void childOfNoPrimaryYearGetsNothing() {
    final Child toddler = child("Ash Example", LocalDate.of(2024, 1, 1), null);
    assertThat(SchoolEventMatcher.isFor(event("Winter Fair", List.of()), toddler)).isFalse();
  }

  static SchoolEvent event(final String title, final List<String> years) {
    return new SchoolEvent(title, title, DAY, DAY, true, SchoolEvent.EventType.OTHER, years,
        "2026/27", SchoolSourceType.CALENDAR_FEED, "doc", Visibility.PUBLIC, null, null, null,
        null);
  }

  static Child child(final String name, final LocalDate born, final String className) {
    return new Child(new ObjectId(), new ObjectId(), name, born, null, null, null, null,
        Instant.now(), Instant.now(), new Child.SchoolCalendarLink(className, null, "2026/27"));
  }
}
