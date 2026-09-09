package com.simonrowe.school.model;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A dated fact about the school: a term boundary, an INSET day, a club start, a trip.
 *
 * <p>This collection exists because similarity search cannot answer "what is on this week".
 * Embeddings have no notion of today's date, and the corpus contains the same event described for
 * several different academic years. Date questions are answered by querying this collection over a
 * range; retrieval is reserved for prose.
 *
 * <p>{@code academicYear} is mandatory for the same reason. Without it, a newsletter from the
 * previous September is indistinguishable from this one, and the assistant reports last year's
 * half term as current.
 *
 * @param id {@code sha256(academicYear + startDate + normalisedTitle)}, so the same event arriving
 *     from two sources collapses to one row rather than being reported twice
 * @param title the event name
 * @param startDate first day, inclusive
 * @param endDate last day, inclusive; equal to {@code startDate} for a single-day event
 * @param allDay whether the source gave a time at all
 * @param eventType coarse classification, so "when are the INSET days" need not be a text search
 * @param yearGroups which years this applies to; empty means whole-school. Unlike the year hint on
 *     a document this IS a hard filter, because the calendar feed publishes it structurally
 * @param academicYear e.g. {@code 2026/27}
 * @param sourceType decides who wins when two sources disagree about the date
 * @param sourceDocumentId the document this was extracted from, for citation
 * @param visibility inherited from the source document
 * @param description free text from the source — what the event actually involves. The calendar
 *     feed supplies this and it was previously discarded, which is why answers could give a date
 *     and nothing else
 * @param location where it happens, when the source says
 * @param time the time of day as the source expressed it, e.g. {@code 6:00pm} or {@code All Day}
 * @param sourceUrl a link to the event, so a reader can book or read more. The calendar feed
 *     provides a per-event deep link
 */
@Document(collection = "school_events")
public record SchoolEvent(
    @Id String id,
    String title,
    LocalDate startDate,
    LocalDate endDate,
    boolean allDay,
    EventType eventType,
    List<String> yearGroups,
    String academicYear,
    SchoolSourceType sourceType,
    String sourceDocumentId,
    Visibility visibility,
    String description,
    String location,
    String time,
    String sourceUrl
) {

  /** What kind of dated fact this is. */
  public enum EventType {
    /** Start or end of a term. */
    TERM_BOUNDARY,
    /** A half-term break. */
    HALF_TERM,
    /** A staff training day with no pupils in school. */
    INSET,
    /** An enrichment or extracurricular club. */
    CLUB,
    /** An outing or residential. */
    TRIP,
    /** Anything else dated. */
    OTHER
  }

  /**
   * Applies the same fail-closed and null-safety rules as {@link SchoolDocument}, and normalises a
   * missing {@code endDate} to the start date so every range query can treat events uniformly.
   */
  public SchoolEvent {
    visibility = visibility == null ? Visibility.RESTRICTED : visibility;
    yearGroups = yearGroups == null ? List.of() : List.copyOf(yearGroups);
    endDate = endDate == null ? startDate : endDate;
    eventType = eventType == null ? EventType.OTHER : eventType;
  }

  /**
   * Whether this event applies to a given year group.
   *
   * @param yearGroup the year group to test, e.g. {@code Year 3}
   * @return true when this is a whole-school event or explicitly covers that year
   */
  public boolean appliesTo(final String yearGroup) {
    return yearGroups.isEmpty() || yearGroups.contains(yearGroup);
  }

  /**
   * Whether this event applies to any of several year groups.
   *
   * <p>A parent with children in different years wants both. An empty selection means "no
   * filter" rather than "nothing matches" — the selector's "not sure" option produces exactly
   * that, and treating it as an impossible filter would return an empty calendar.
   *
   * @param selected the year groups selected, empty for no filter
   * @return true when this event is whole-school, or covers any selected year
   */
  public boolean appliesToAny(final List<String> selected) {
    if (selected == null || selected.isEmpty() || yearGroups.isEmpty()) {
      return true;
    }
    return selected.stream().anyMatch(yearGroups::contains);
  }

  /**
   * Whether this event overlaps a date range, inclusive at both ends.
   *
   * @param from range start
   * @param to range end
   * @return true when any day of the event falls in the range
   */
  public boolean overlaps(final LocalDate from, final LocalDate to) {
    return !startDate.isAfter(to) && !endDate.isBefore(from);
  }
}
