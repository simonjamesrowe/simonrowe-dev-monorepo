package com.simonrowe.school.ingest;

import com.simonrowe.school.model.YearGroups;
import java.time.LocalDate;
import java.util.List;

/**
 * One row from the school's calendar JSON feed, before it becomes a
 * {@link com.simonrowe.school.model.SchoolEvent}.
 *
 * @param feedId the feed's own numeric event id
 * @param title the event name
 * @param startDate first day
 * @param endDate last day, normalised to the start for single-day events
 * @param allDay whether the feed gave a time
 * @param time the feed's own time string, e.g. "All Day" or "6:00pm"
 * @param description free text, often empty
 * @param url the feed's deep link to this event
 * @param calendarIds which sub-calendars this event belongs to
 */
public record CalendarFeedEvent(
    String feedId,
    String title,
    LocalDate startDate,
    LocalDate endDate,
    boolean allDay,
    String time,
    String description,
    String url,
    List<Integer> calendarIds
) {

  /**
   * The year groups this event applies to.
   *
   * <p>An empty list means whole-school, and that is the correct reading for the all-school
   * calendar, the PTA calendar and anything whose sub-calendars this application does not
   * recognise. Falling back to "no year groups match" instead would hide every whole-school event
   * from every year-filtered query — which is most of the calendar.
   *
   * @return the year groups, or empty for whole-school
   */
  public List<String> yearGroups() {
    return calendarIds.stream()
        .map(YearGroups::fromCalendarId)
        .flatMap(java.util.Optional::stream)
        .distinct()
        .toList();
  }
}
