package com.simonrowe.school.ingest;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.model.AcademicYear;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolEventRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes dated facts, applying source precedence when two sources describe the same event.
 *
 * <p>This is where the stale-website problem is actually solved. The school's term-dates page
 * still shows the previous academic year while its calendar feed carries the current one, and both
 * are ingested. Because {@link SchoolIds#eventId} keys on year, date and title rather than on the
 * source, the two arrive as writes to the same row — and this class decides which one survives.
 *
 * <p>It is also the single choke point for {@code school.ingest-from-date}. The cutoff was
 * originally applied only in {@link GmailIngestService#buildQuery()}, which left the calendar
 * feed's own six-month lookback free to backfill events from the previous academic year — so
 * capping email did not cap what the admin console and the assistant actually showed. Enforcing
 * it here covers every producer, including facts an in-window email happens to state about a past
 * date.
 */
@Component
public class SchoolEventWriter {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolEventWriter.class);

  private final SchoolEventRepository repository;
  private final SchoolProperties properties;

  public SchoolEventWriter(
      final SchoolEventRepository repository, final SchoolProperties properties) {
    this.repository = repository;
    this.properties = properties;
  }

  /**
   * Reports whether an event falls before the configured ingest cutoff.
   *
   * <p>An event that spans the cutoff is kept: a half term running into the first in-window week
   * is current information, and dropping it would leave a hole rather than trim history.
   *
   * @param event the event to test
   * @return true when the whole event predates the cutoff
   */
  public boolean isBeforeCutoff(final SchoolEvent event) {
    final LocalDate cutoff = properties.ingestFromDate();
    if (cutoff == null) {
      return false;
    }
    final LocalDate latest = event.endDate() == null ? event.startDate() : event.endDate();
    return latest != null && latest.isBefore(cutoff);
  }

  /**
   * Stores an event, keeping whichever version comes from the more authoritative source.
   *
   * <p>Ties go to the incoming event: a re-ingest from the same source is a refresh, and keeping
   * the stored copy would make corrections to a newsletter permanently invisible.
   *
   * @param incoming the event to store
   * @return the event now stored, which may be the one that was already there — or, for an event
   *     dropped by the ingest cutoff, the unsaved incoming event
   */
  public SchoolEvent write(final SchoolEvent incoming) {
    if (isBeforeCutoff(incoming)) {
      LOG.debug("Skipping {} on {} — before the ingest cutoff",
          incoming.title(), incoming.startDate());
      return incoming;
    }
    final Optional<SchoolEvent> existing = repository.findById(incoming.id());
    if (existing.isPresent()) {
      final SchoolEvent stored = existing.get();
      final SchoolSourceType winner =
          SchoolSourceType.moreAuthoritative(stored.sourceType(), incoming.sourceType());
      if (winner == stored.sourceType() && stored.sourceType() != incoming.sourceType()) {
        LOG.debug("Keeping {} from {} over incoming {}",
            stored.title(), stored.sourceType(), incoming.sourceType());
        return stored;
      }
    }
    return repository.save(incoming);
  }

  /**
   * Converts a calendar feed row into a stored event.
   *
   * <p>Calendar events are {@link Visibility#PUBLIC} without approval, and that is not a hole in
   * the approval rule — it is the rule working. The approval gate exists because email content is
   * private until a human says otherwise; this feed is already published, unauthenticated, on the
   * school's own public website. Requiring approval for it would mean hand-approving the term
   * dates before the assistant could repeat what the school already tells the world.
   *
   * @param event the feed row
   * @param sourceDocumentId the document this came from, for citation
   * @return the stored event
   */
  public SchoolEvent writeFromCalendar(
      final CalendarFeedEvent event, final String sourceDocumentId) {
    final String academicYear = AcademicYear.of(event.startDate());
    final SchoolEvent schoolEvent = new SchoolEvent(
        SchoolIds.eventId(academicYear, event.startDate(), event.title()),
        event.title(),
        event.startDate(),
        event.endDate(),
        event.allDay(),
        EventTypeClassifier.classify(event.title()),
        event.yearGroups(),
        academicYear,
        SchoolSourceType.CALENDAR_FEED,
        sourceDocumentId,
        Visibility.PUBLIC,
        event.description(),
        null,
        event.time(),
        event.url());
    return write(schoolEvent);
  }
}
