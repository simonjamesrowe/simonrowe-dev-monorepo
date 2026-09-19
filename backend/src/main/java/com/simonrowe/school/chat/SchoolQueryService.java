package com.simonrowe.school.chat;

import com.simonrowe.school.model.AcademicYear;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolEventRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.YearGroups;
import com.simonrowe.school.retrieval.SchoolAudience;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Answers dated questions by query rather than by similarity search.
 *
 * <p>This is the half of the assistant that similarity search cannot do. "What is on this week"
 * has no useful embedding — the phrase is nearly identical whichever week you mean — and the
 * corpus holds several years' worth of the same event. Everything date-shaped is answered here,
 * and retrieval is left to handle prose.
 *
 * <p>That applies to <b>documents as well as events</b>, which it did not originally. "Was there
 * a newsletter last week" and "what was in it" are date questions about documents, and were
 * being answered by similarity search over a corpus of a dozen weekly newsletters that all sit
 * in nearly the same place in vector space. See {@link #communicationsBetween}.
 */
@Service
public class SchoolQueryService {

  /**
   * What counts as a communication for {@link #communicationsBetween}.
   *
   * <p>{@link SchoolSourceType#CALENDAR_FEED} is excluded and that exclusion is load-bearing, not
   * tidiness. The feed is represented by a single container document whose body is the sentence
   * "The school's published calendar feed." and whose {@code publishedAt} is set to
   * {@code Instant.now()} by {@code SchoolIngestService.ingestCalendar} on every pass — every
   * thirty minutes in production. Included, it would be the newest communication in every window
   * for ever, so "what did the school send this week" would always lead with a stub describing a
   * data source.
   *
   * <p>{@link SchoolSourceType#PASTED_NOTE} and {@link SchoolSourceType#EXTERNAL_PAGE} are
   * excluded for a different reason, and it is the reason {@code EXTERNAL_PAGE} exists as a
   * separate type at all: neither is something <b>the school</b> published. A note pasted into
   * the admin console is a parents'-group message somebody transcribed, and an external page is
   * another school's own website. Both are legitimate answers to "when is the open evening";
   * neither is an answer to "what did the school send last week", and reporting them as such
   * would attribute another school's announcement to this one.
   *
   * <p>An allowlist rather than a denylist, so a source type added later is silently absent
   * from this answer until somebody decides it belongs — which is the safe direction.
   */
  private static final List<SchoolSourceType> COMMUNICATION_SOURCES = List.of(
      SchoolSourceType.EMAIL, SchoolSourceType.WEBSITE_PAGE, SchoolSourceType.PDF);

  /**
   * The widest window this will answer for.
   *
   * <p>The dates come from a model reading a question, so "recently" can arrive as anything. Two
   * months covers half a term, which is the longest span a parent asks about in one go, and caps
   * what one tool call can pull out of the corpus.
   */
  private static final int MAX_WINDOW_DAYS = 62;

  private final SchoolEventRepository events;
  private final SchoolDocumentRepository documents;
  private final Clock clock;

  public SchoolQueryService(final SchoolEventRepository events,
      final SchoolDocumentRepository documents, final Clock clock) {
    this.events = events;
    this.documents = documents;
    this.clock = clock;
  }

  /**
   * Everything the school published in a date window, newest first.
   *
   * <p>The document-shaped counterpart to {@link #eventsBetween}, and it exists for the same
   * reason: a date is a fact to be queried, not a phrase to be embedded. "Was there a newsletter
   * last week" has a definite yes or no, and similarity search cannot give one — it returns the
   * eight chunks nearest the word "newsletter", which in a corpus of a dozen near-identical
   * weekly newsletters is close to arbitrary. In production this answered a question about the
   * previous week from a newsletter dated 10 July and said the 10 July one was the latest.
   *
   * <p>The window is clamped rather than rejected when it is too wide: the caller is a model
   * turning a phrase into two dates, and refusing "this term" outright teaches it nothing, while
   * answering for the most recent two months of it is nearly always what was wanted. It is
   * clamped from the <b>end</b> for that reason — the recent half of an over-wide window is the
   * half being asked about.
   *
   * @param from window start, inclusive
   * @param to window end, inclusive
   * @param audience which tiers may be read
   * @return matching documents, most recently published first
   */
  public List<SchoolDocument> communicationsBetween(
      final LocalDate from, final LocalDate to, final SchoolAudience audience) {
    if (to.isBefore(from)) {
      return List.of();
    }
    final LocalDate start = from.isBefore(to.minusDays(MAX_WINDOW_DAYS))
        ? to.minusDays(MAX_WINDOW_DAYS)
        : from;
    final ZoneId zone = clock.getZone();
    return documents.findPublishedBetween(
        audience.visibilities(),
        COMMUNICATION_SOURCES,
        start.atStartOfDay(zone).toInstant(),
        // The end of the last day, not its start: a newsletter sent at 15:03 on the closing day
        // of the window is in the window. Getting this wrong loses exactly the most recent item,
        // which is the one nearly every question about a window is really about.
        to.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1));
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
