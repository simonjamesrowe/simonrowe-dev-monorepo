package com.simonrowe.school.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolEventRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import com.simonrowe.school.retrieval.SchoolAudience;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The calendar pane's window: which day is today, where "this week" ends, and the order a day's
 * events are listed in.
 */
class SchoolUpcomingQueryTest {

  private final SchoolEventRepository events = mock(SchoolEventRepository.class);

  private SchoolQueryService at(final String instant) {
    // UTC, as the production container is: the query must not take today from the clock's zone.
    return new SchoolQueryService(events, mock(SchoolDocumentRepository.class),
        Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
  }

  private static SchoolEvent event(final String id, final LocalDate date, final String time,
      final boolean allDay, final List<String> years) {
    return new SchoolEvent(id, id, date, date, allDay, SchoolEvent.EventType.OTHER, years,
        "2026/27", SchoolSourceType.CALENDAR_FEED, "doc", Visibility.PUBLIC, null, null, time,
        null);
  }

  @Test
  @DisplayName("on a weekday, this week ends on Sunday and the window two Sundays later")
  void weekdayWindow() {
    when(events.findOverlapping(any(), any(), anyList())).thenReturn(List.of());

    final UpcomingEvents upcoming = at("2026-10-13T08:00:00Z").upcoming(List.of(),
        SchoolAudience.anonymous());

    assertThat(upcoming.today()).isEqualTo(LocalDate.of(2026, 10, 13));
    assertThat(upcoming.weekEnd()).isEqualTo(LocalDate.of(2026, 10, 18));
    assertThat(upcoming.until()).isEqualTo(LocalDate.of(2026, 11, 1));
    verify(events).findOverlapping(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 11, 1),
        List.of(Visibility.PUBLIC));
  }

  @Test
  @DisplayName("at the weekend, this week means the week ahead")
  void weekendMeansTheWeekAhead() {
    assertThat(SchoolQueryService.endOfWeek(LocalDate.of(2026, 10, 17)))
        .isEqualTo(LocalDate.of(2026, 10, 25));
    assertThat(SchoolQueryService.endOfWeek(LocalDate.of(2026, 10, 18)))
        .isEqualTo(LocalDate.of(2026, 10, 25));
    assertThat(SchoolQueryService.endOfWeek(LocalDate.of(2026, 10, 16)))
        .isEqualTo(LocalDate.of(2026, 10, 18));
  }

  @Test
  @DisplayName("today is London's date, not the container's: 00:30 BST is still yesterday in UTC")
  void todayIsLondonsDate() {
    when(events.findOverlapping(any(), any(), anyList())).thenReturn(List.of());

    final UpcomingEvents upcoming = at("2026-10-13T23:30:00Z").upcoming(List.of(),
        SchoolAudience.anonymous());

    assertThat(upcoming.today()).isEqualTo(LocalDate.of(2026, 10, 14));
  }

  @Test
  @DisplayName("a day's events are listed all-day first, then by start time, unreadable last")
  void orderedByStartTime() {
    final LocalDate day = LocalDate.of(2026, 10, 15);
    when(events.findOverlapping(any(), any(), anyList())).thenReturn(List.of(
        event("cake sale", day, "3:30pm", false, List.of()),
        event("vague", day, "after lunch", false, List.of()),
        event("assembly", day, "9:00am", false, List.of()),
        event("odd socks", day, "All Day", true, List.of()),
        event("tomorrow", day.plusDays(1), "8am", false, List.of())));

    final UpcomingEvents upcoming = at("2026-10-13T08:00:00Z").upcoming(List.of(),
        SchoolAudience.anonymous());

    assertThat(upcoming.events()).extracting(SchoolEvent::id)
        .containsExactly("odd socks", "assembly", "cake sale", "vague", "tomorrow");
  }

  @Test
  @DisplayName("the year filter keeps whole-school events and drops other years' events")
  void yearFilter() {
    final LocalDate day = LocalDate.of(2026, 10, 15);
    when(events.findOverlapping(any(), any(), eq(List.of(Visibility.PUBLIC)))).thenReturn(List.of(
        event("whole school", day, "9am", false, List.of()),
        event("year 3 trip", day, "10am", false, List.of("Year 3")),
        event("year 6 open evening", day, "6pm", false, List.of("Year 6"))));

    final UpcomingEvents upcoming = at("2026-10-13T08:00:00Z").upcoming(List.of("Year 3"),
        SchoolAudience.anonymous());

    assertThat(upcoming.events()).extracting(SchoolEvent::id)
        .containsExactly("whole school", "year 3 trip");
  }

  @Test
  @DisplayName("one event from two sources is listed once, and the year filter runs first")
  void duplicatesCollapsedAfterTheYearFilter() {
    final LocalDate day = LocalDate.of(2026, 10, 15);
    // The feed copy is more authoritative but names Year 6 only; collapsing before filtering
    // would keep it and then drop it, leaving a Year 3 parent with no harvest festival at all.
    when(events.findOverlapping(any(), any(), anyList())).thenReturn(List.of(
        new SchoolEvent("feed", "Harvest Festival", day, day, true, SchoolEvent.EventType.OTHER,
            List.of("Year 6"), "2026/27", SchoolSourceType.CALENDAR_FEED, "doc",
            Visibility.PUBLIC, null, null, null, null),
        new SchoolEvent("email", "Kilmorie Harvest Festival", day, day, true,
            SchoolEvent.EventType.OTHER, List.of(), "2026/27", SchoolSourceType.EMAIL, "doc",
            Visibility.PUBLIC, null, null, null, null)));

    assertThat(at("2026-10-13T08:00:00Z").upcoming(List.of("Year 3"),
        SchoolAudience.anonymous()).events()).extracting(SchoolEvent::id)
        .containsExactly("email");
    assertThat(at("2026-10-13T08:00:00Z").upcoming(List.of(),
        SchoolAudience.anonymous()).events()).extracting(SchoolEvent::id)
        .containsExactly("feed");
  }
}
