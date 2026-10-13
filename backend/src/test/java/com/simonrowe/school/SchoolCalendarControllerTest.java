package com.simonrowe.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simonrowe.school.chat.SchoolQueryService;
import com.simonrowe.school.chat.UpcomingEvents;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import com.simonrowe.school.retrieval.SchoolAudience;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SchoolCalendarControllerTest {

  private static final SchoolProperties ENABLED = new SchoolProperties(
      true, null, List.of("kilmorie.lewisham.sch.uk"), List.of(),
      null, null, null, 0, null, null, null, 100, null, null);

  private static final SchoolProperties DISABLED = new SchoolProperties(
      false, null, null, null, null, null, null, 0, null, null, null, 0, null, null);

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

  @Test
  @DisplayName("reads with the anonymous audience, so a restricted event can never reach the pane")
  void alwaysAnonymous() {
    final SchoolQueryService queries = mock(SchoolQueryService.class);
    when(queries.upcoming(any(), any())).thenReturn(
        new UpcomingEvents(TODAY, TODAY.plusDays(5), TODAY.plusDays(19), List.of()));

    new SchoolCalendarController(queries, ENABLED).calendar(List.of("Year 3"));

    final ArgumentCaptor<SchoolAudience> audience = ArgumentCaptor.forClass(SchoolAudience.class);
    verify(queries).upcoming(org.mockito.ArgumentMatchers.eq(List.of("Year 3")),
        audience.capture());
    assertThat(audience.getValue().visibilities()).containsExactly(Visibility.PUBLIC);
  }

  @Test
  @DisplayName("projects each event to what the pane shows, leaving out tier and source")
  void projectsEvents() {
    final SchoolQueryService queries = mock(SchoolQueryService.class);
    final SchoolEvent trip = new SchoolEvent("id-1", "Horniman Museum trip",
        TODAY.plusDays(2), TODAY.plusDays(2), false, SchoolEvent.EventType.TRIP,
        List.of("Year 3"), "2026/27", SchoolSourceType.EMAIL, "doc-1", Visibility.PUBLIC,
        "Packed lunch", "Forest Hill", "9:15am", "https://example.org/trip");
    when(queries.upcoming(any(), any())).thenReturn(
        new UpcomingEvents(TODAY, TODAY.plusDays(5), TODAY.plusDays(19), List.of(trip)));

    final var body = new SchoolCalendarController(queries, ENABLED).calendar(null).getBody();

    assertThat(body).isNotNull();
    assertThat(body.today()).isEqualTo(TODAY);
    assertThat(body.weekEnd()).isEqualTo(TODAY.plusDays(5));
    assertThat(body.until()).isEqualTo(TODAY.plusDays(19));
    assertThat(body.events()).containsExactly(new SchoolCalendarController.CalendarEvent(
        "id-1", "Horniman Museum trip", TODAY.plusDays(2), TODAY.plusDays(2), false, "9:15am",
        SchoolEvent.EventType.TRIP, List.of("Year 3"), "Forest Hill"));
  }

  @Test
  @DisplayName("reports 503 and reads nothing when Term Time is switched off")
  void disabled() {
    final SchoolQueryService queries = mock(SchoolQueryService.class);

    final var response = new SchoolCalendarController(queries, DISABLED).calendar(null);

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    verifyNoInteractions(queries);
  }
}
