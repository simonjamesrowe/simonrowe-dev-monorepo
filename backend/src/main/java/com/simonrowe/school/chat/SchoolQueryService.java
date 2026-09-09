package com.simonrowe.school.chat;

import com.simonrowe.school.model.AcademicYear;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolEventRepository;
import com.simonrowe.school.model.YearGroups;
import com.simonrowe.school.retrieval.SchoolAudience;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Answers dated questions from stored events rather than from similarity search.
 *
 * <p>This is the half of the assistant that similarity search cannot do. "What is on this week"
 * has no useful embedding — the phrase is nearly identical whichever week you mean — and the
 * corpus holds several years' worth of the same event. Everything date-shaped is answered here,
 * by query, and retrieval is left to handle prose.
 */
@Service
public class SchoolQueryService {

  private final SchoolEventRepository events;
  private final Clock clock;

  public SchoolQueryService(final SchoolEventRepository events, final Clock clock) {
    this.events = events;
    this.clock = clock;
  }

  /**
   * Events overlapping a date range, optionally narrowed to a year group.
   *
   * <p>Unlike prose retrieval, the year filter here IS applied strictly: the calendar feed
   * publishes year attribution structurally, so it can be trusted. Whole-school events have no
   * year groups and always match, which is what stops a Year 3 filter hiding the term dates.
   *
   * @param from range start, inclusive
   * @param to range end, inclusive
   * @param yearGroups the year groups to narrow to, empty for everything
   * @param audience which tiers may be read
   * @return matching events, earliest first
   */
  public List<SchoolEvent> eventsBetween(
      final LocalDate from, final LocalDate to, final List<String> yearGroups,
      final SchoolAudience audience) {
    final List<String> selected = YearGroups.sanitise(yearGroups);
    final List<SchoolEvent> found =
        events.findOverlapping(from, to, audience.visibilities());
    return found.stream()
        .filter(e -> e.appliesToAny(selected))
        .sorted(java.util.Comparator.comparing(SchoolEvent::startDate))
        .toList();
  }

  /**
   * Events in the current week, Monday to Sunday.
   *
   * @param yearGroups the year groups to narrow to, empty for all
   * @param audience which tiers may be read
   * @return this week's events
   */
  public List<SchoolEvent> thisWeek(
      final List<String> yearGroups, final SchoolAudience audience) {
    final LocalDate today = LocalDate.now(clock);
    final LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    return eventsBetween(monday, monday.plusDays(6), yearGroups, audience);
  }

  /**
   * Events of one type in an academic year — INSET days, half terms, term boundaries.
   *
   * @param eventType which kind
   * @param academicYear the year label, or null for the current one
   * @param audience which tiers may be read
   * @return matching events, earliest first
   */
  public List<SchoolEvent> ofType(
      final SchoolEvent.EventType eventType, final String academicYear,
      final SchoolAudience audience) {
    final String year = academicYear == null || academicYear.isBlank()
        ? AcademicYear.current(LocalDate.now(clock))
        : academicYear;
    return events.findByAcademicYearAndEventTypeAndVisibilityInOrderByStartDateAsc(
        year, eventType, audience.visibilities());
  }

  /**
   * The academic year the assistant should treat as "now".
   *
   * @return the current academic year label
   */
  public String currentAcademicYear() {
    return AcademicYear.current(LocalDate.now(clock));
  }

  /**
   * Today, as the assistant should understand it.
   *
   * @return today's date
   */
  public LocalDate today() {
    return LocalDate.now(clock);
  }
}
