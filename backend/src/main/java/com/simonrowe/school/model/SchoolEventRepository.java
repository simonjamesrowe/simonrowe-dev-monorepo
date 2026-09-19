package com.simonrowe.school.model;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/** Persistence for {@link SchoolEvent}. */
public interface SchoolEventRepository extends MongoRepository<SchoolEvent, String> {

  /**
   * Events overlapping a date range and visible to the caller's tier.
   *
   * <p>Expressed as an explicit {@code @Query} rather than a derived method name because the
   * overlap condition is {@code start <= to AND end >= from}, which no derived-name grammar
   * expresses without reading as its opposite.
   *
   * @param from range start, inclusive
   * @param to range end, inclusive
   * @param visibilities which tiers the caller may see
   * @return overlapping events, earliest first
   */
  @Query("{ 'startDate': { $lte: ?1 }, 'endDate': { $gte: ?0 }, 'visibility': { $in: ?2 } }")
  List<SchoolEvent> findOverlapping(LocalDate from, LocalDate to, List<Visibility> visibilities);

  /**
   * Every event extracted from any of several documents.
   *
   * <p>Reads a note's own events together with those from the pages its links led to, in one
   * query rather than one per document — a note carrying six links is seven lookups otherwise.
   *
   * @param sourceDocumentIds the documents to read events for
   * @return their events, in no particular order
   */
  List<SchoolEvent> findBySourceDocumentIdIn(List<String> sourceDocumentIds);

  /**
   * Events of a given type within one academic year, used for "when are the INSET days".
   *
   * @param academicYear e.g. {@code 2026/27}
   * @param eventType the kind of event
   * @param visibilities which tiers the caller may see
   * @return matching events, earliest first
   */
  List<SchoolEvent> findByAcademicYearAndEventTypeAndVisibilityInOrderByStartDateAsc(
      String academicYear, SchoolEvent.EventType eventType, List<Visibility> visibilities);
}
