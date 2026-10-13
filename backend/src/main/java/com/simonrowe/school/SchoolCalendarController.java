package com.simonrowe.school;

import com.simonrowe.school.chat.SchoolQueryService;
import com.simonrowe.school.chat.UpcomingEvents;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.retrieval.SchoolAudience;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The calendar pane beside the Term Time chat: what is on today and over the next three weeks.
 *
 * <p><b>Always the anonymous audience, whoever calls.</b> The page never sends a token, and this
 * endpoint does not read one, so there is no way for a restricted event to reach the pane. The
 * chat controller resolves an audience from the JWT because a signed-in administrator may ask it
 * about the mailbox; a calendar shown to every visitor has no such case.
 *
 * <p>Not rate limited, like {@code /api/school/config}: the page fetches it on load, and it is one
 * indexed Mongo read with no model call behind it.
 */
@RestController
@RequestMapping("/api/school")
public class SchoolCalendarController {

  private final SchoolQueryService queries;
  private final SchoolProperties properties;

  public SchoolCalendarController(
      final SchoolQueryService queries, final SchoolProperties properties) {
    this.queries = queries;
    this.properties = properties;
  }

  /**
   * Public events from today to the end of the week after next.
   *
   * @param yearGroups the visitor's selected year groups; unrecognised values are dropped, and an
   *     empty selection means every year
   * @return the window and its events, or 503 when Term Time is switched off
   */
  @GetMapping("/calendar")
  public ResponseEntity<SchoolCalendarResponse> calendar(
      @RequestParam(name = "yearGroups", required = false) final List<String> yearGroups) {
    if (!properties.enabled()) {
      return ResponseEntity.status(503).build();
    }
    final UpcomingEvents upcoming = queries.upcoming(yearGroups, SchoolAudience.anonymous());
    return ResponseEntity.ok(new SchoolCalendarResponse(
        upcoming.today(), upcoming.weekEnd(), upcoming.until(),
        upcoming.events().stream().map(CalendarEvent::from).toList()));
  }

  /**
   * The pane's data.
   *
   * @param today the school's today, so the browser never has to decide which day it is
   * @param weekEnd the last day of "this week"
   * @param until the last day of the window
   * @param events events overlapping the window, by start date then start time
   */
  public record SchoolCalendarResponse(
      LocalDate today, LocalDate weekEnd, LocalDate until, List<CalendarEvent> events) {
  }

  /**
   * One event, as the pane needs it.
   *
   * <p>A projection rather than the stored {@link SchoolEvent}: the source document id, tier and
   * source type are bookkeeping, and the description and source link are left to the chat, which
   * is where clicking an event takes a visitor.
   *
   * @param id the event's id, stable across re-ingest
   * @param title the event name
   * @param startDate first day, inclusive
   * @param endDate last day, inclusive
   * @param allDay whether the event is all day
   * @param time the source's own time text, e.g. {@code 6:00pm}, or null
   * @param type the kind of event, which decides whether it means no school
   * @param yearGroups which years it is for; empty means the whole school
   * @param location where it happens, or null
   */
  public record CalendarEvent(
      String id, String title, LocalDate startDate, LocalDate endDate, boolean allDay,
      String time, SchoolEvent.EventType type, List<String> yearGroups, String location) {

    static CalendarEvent from(final SchoolEvent event) {
      return new CalendarEvent(event.id(), event.title(), event.startDate(), event.endDate(),
          event.allDay(), event.time(), event.eventType(), event.yearGroups(), event.location());
    }
  }
}
