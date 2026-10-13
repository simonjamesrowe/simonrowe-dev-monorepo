package com.simonrowe.school.chat;

import com.simonrowe.school.model.SchoolEvent;
import java.time.LocalDate;
import java.util.List;

/**
 * The calendar pane's window and what falls in it.
 *
 * @param today the school's today
 * @param weekEnd the last day of "this week"
 * @param until the last day of the window
 * @param events every event overlapping {@code today..until}, by start date then start time
 */
public record UpcomingEvents(
    LocalDate today, LocalDate weekEnd, LocalDate until, List<SchoolEvent> events) {

  /** Copies the list, so the record cannot be changed after it is built. */
  public UpcomingEvents {
    events = events == null ? List.of() : List.copyOf(events);
  }
}
