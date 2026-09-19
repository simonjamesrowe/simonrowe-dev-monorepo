package com.simonrowe.school.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Narrowing an event to the year group its source was about.
 *
 * <p>Without this, a note listing four secondary schools' open evenings produces four
 * whole-school events, and every Reception parent asking what is on this week is shown all of
 * them. Nothing errors; the calendar is simply wrong for six year groups out of seven.
 */
class SchoolEventScopeTest {

  @Test
  @DisplayName("an event with no year group of its own takes the scope")
  void takesTheScope() {
    assertThat(event(List.of()).withYearGroupScope(List.of("Year 6")).yearGroups())
        .containsExactly("Year 6");
  }

  @Test
  @DisplayName("an event that named its own year groups keeps them")
  void keepsItsOwn() {
    assertThat(event(List.of("Year 5")).withYearGroupScope(List.of("Year 6")).yearGroups())
        .containsExactly("Year 5");
  }

  @Test
  @DisplayName("an empty scope leaves the event exactly as it was")
  void emptyScopeIsInert() {
    final SchoolEvent original = event(List.of());
    assertThat(original.withYearGroupScope(List.of())).isSameAs(original);
    assertThat(original.withYearGroupScope(null)).isSameAs(original);
  }

  @Test
  @DisplayName("narrowing does not move the row")
  void idIsUnchanged() {
    // yearGroups is not part of the event id, and must not become part of it: the same open
    // evening arriving later from the school's own page, which states its years, has to
    // collapse onto this row rather than sit beside it as a second copy.
    final SchoolEvent original = event(List.of());
    assertThat(original.withYearGroupScope(List.of("Year 6")).id()).isEqualTo(original.id());
  }

  private SchoolEvent event(final List<String> yearGroups) {
    return new SchoolEvent("id-1", "Kingsdale Foundation School open day",
        LocalDate.of(2026, 10, 1), null, true, SchoolEvent.EventType.OTHER, yearGroups,
        "2026/27", SchoolSourceType.PASTED_NOTE, "note-1", Visibility.PUBLIC,
        null, null, null, null);
  }
}
