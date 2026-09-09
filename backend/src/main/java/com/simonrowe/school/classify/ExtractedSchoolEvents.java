package com.simonrowe.school.classify;

import java.util.List;

/**
 * The dated facts a model found in one piece of school prose.
 *
 * <p>A record rather than free text because these become rows that answer "what is on this
 * week", and a date the assistant cannot parse is a date it cannot answer with.
 *
 * @param events the events found, possibly empty
 */
public record ExtractedSchoolEvents(List<Event> events) {

  /** Normalises a null list so callers never null-check it. */
  public ExtractedSchoolEvents {
    events = events == null ? List.of() : List.copyOf(events);
  }

  /**
   * One dated fact.
   *
   * <p>Every field is a plain String, including the dates. The model returns ISO dates as text
   * and a malformed one must be droppable per-event — binding straight to {@code LocalDate}
   * would fail the whole extraction because one event in twelve had "TBC" in it.
   *
   * @param title what is happening
   * @param startDate ISO {@code yyyy-MM-dd}
   * @param endDate ISO {@code yyyy-MM-dd}, or the same as the start for a single day
   * @param eventType one of TERM_BOUNDARY, HALF_TERM, INSET, CLUB, TRIP, OTHER
   * @param yearGroups the year groups affected, empty for whole-school
   * @param description what the event involves, in a sentence — the part a parent actually
   *     needs beyond the date
   * @param location where it happens, when the text says
   * @param time the time of day, as written
   */
  public record Event(
      String title,
      String startDate,
      String endDate,
      String eventType,
      List<String> yearGroups,
      String description,
      String location,
      String time) {

    /** Normalises a null year-group list, which the model omits for whole-school events. */
    public Event {
      yearGroups = yearGroups == null ? List.of() : List.copyOf(yearGroups);
    }
  }
}
