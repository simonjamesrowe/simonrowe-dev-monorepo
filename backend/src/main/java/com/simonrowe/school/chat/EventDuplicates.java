package com.simonrowe.school.chat;

import com.simonrowe.school.ingest.EventTypeClassifier;
import com.simonrowe.school.ingest.SchoolIds;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolSourceType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Collapses one event reported by several sources into a single row.
 *
 * <p>{@link SchoolIds#eventId} already merges two sources that give an event the same title. It
 * cannot merge two that word it differently, and the extractor words the same event differently
 * almost every time it reads it. On 2026-10-13 the calendar pane listed the autumn half term six
 * times, once each for "Half Term", "Autumn Half-Term", "Autumn 2026 half-term", "Kilmorie Autumn
 * Half Term 2026", "Kilmorie Primary School Autumn Half Term" and "Kilmorie Primary School Half
 * Term", because the calendar feed, newsletters, website pages and PDFs each stored their own row.
 *
 * <p>This runs when events are read, not when they are written. Merging at write time would make
 * one source's row stand in for another's, and a document edit or re-extraction deletes the rows
 * that document produced: the merged row would go with it while the other source still states the
 * event. Reading every source's row and collapsing them each time keeps the stored data honest and
 * needs no migration for the duplicates already stored.
 *
 * <p>Two events are the same when they are of the same kind, cover the same days, do not name
 * different year groups, and either
 * <ul>
 *   <li>are a half term or an INSET day, of which there is only ever one on a given span; or</li>
 *   <li>one title's significant words are all found in the other's ("Parent's evening week" and
 *       "Kilmorie Primary School Parents Evening October 2026"); or</li>
 *   <li>are trips over several days and share a significant word ("Year 6 residential trip to
 *       PGL" and "Year 6 Trip to PGL Grovesnor Hall", both Monday to Friday).</li>
 * </ul>
 *
 * <p>"The same days" means the same school days for an event on a single day. For an event over
 * several days it means starting in the same week and ending in the same week, because sources
 * disagree at the edges: the October parents' evenings are Monday to Friday in one source and
 * Tuesday to Saturday in another, and the autumn enrichment clubs end on Monday 30 November in one
 * and Friday 4 December in another. Single-day events get no such slack: "New parent tour" runs
 * on three different days of one fortnight, and those are three tours.
 *
 * <p>The trip rule is limited to multi-day trips on purpose. Single-day events share words all the
 * time without being the same ("Jo's Class Assembly" and "Elis' Class Assembly"), and so do
 * term-long clubs ("Chess Club" and "Football Club", both September to November). Two different
 * residentials for the same year over the same days do not happen.
 *
 * <p><b>A club programme the extractor filed as a term boundary is a club.</b> The model reads
 * "Autumn Term Enrichment Clubs" and returns {@code TERM_BOUNDARY}, so the pane badged the clubs
 * "Term dates" three times beside the real term. Such a row counts as a club when its title says
 * so ({@link EventTypeClassifier}), and a term-boundary row whose words all appear in a club's
 * title ("Autumn Term 2026" beside "Kilmorie Enrichment Autumn Term 2026") joins that club. Rows
 * from the calendar feed are exempt from both: their type comes from rules, not a model, and the
 * feed's term dates are the ones that must never be hidden inside a club.
 *
 * <p>An event joins a group only if it is the same as <b>every</b> event already in it, never
 * through a chain. A generic "Class Assembly" matches both "Jo's Class Assembly" and "Elis' Class
 * Assembly" on the same morning; chaining through it would merge two classes' assemblies into one.
 *
 * <p>Precision is preferred over recall: a duplicate left showing costs a reader a second glance,
 * an event merged into another one hides it. So differently worded titles on a single day are
 * kept apart, and so are events whose sources disagree by more than the edges of a week.
 */
final class EventDuplicates {

  /**
   * The school's own name, which a website or PDF extraction prefixes to everything. Also used
   * to rank titles: a reader gains nothing from it either.
   */
  private static final Set<String> SCHOOL_NAME = Set.of("kilmorie", "primary", "school");

  /**
   * Words that say nothing about which event is meant: the school's name, "year" (year groups
   * are compared on their own field), month names (dates are compared on their own) and
   * connecting words.
   */
  private static final Set<String> NOISE = Stream.of(SCHOOL_NAME.stream(), Stream.of(
          "year", "years", "january", "february", "march", "april", "may", "june", "july",
          "august", "september", "october", "november", "december",
          "the", "a", "an", "of", "and", "to", "for", "at", "in", "on", "with"))
      .flatMap(words -> words)
      .collect(Collectors.toUnmodifiableSet());

  private static final int MIN_PLURAL_LENGTH = 4;

  /** Closures: one per span, so the type and the dates identify them whatever the title. */
  private static final Set<SchoolEvent.EventType> ONE_PER_SPAN =
      EnumSet.of(SchoolEvent.EventType.HALF_TERM, SchoolEvent.EventType.INSET);

  private static final Set<DayOfWeek> WEEKEND = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

  private static final int YEAR_LENGTH = 4;

  /**
   * Which row of a set of duplicates is shown: the most authoritative source, then the one that
   * says the most, then the most informative title, then the shortest, then the id, so the
   * choice never changes between two reads of the same data.
   *
   * <p>"Most informative" counts the title's significant words and takes off one for each word
   * of the school's name. Shortest alone picked "Autumn Term 2026" for the enrichment clubs,
   * which reads as a term date; most words alone picked "Kilmorie Primary School Autumn Term
   * Enrichment Clubs start and end". This picks "Autumn Term Enrichment Clubs".
   */
  private static final Comparator<SchoolEvent> PREFERRED = Comparator
      .comparingInt((SchoolEvent e) -> precedence(e.sourceType()))
      .thenComparing(Comparator.comparingInt(EventDuplicates::detail).reversed())
      .thenComparing(Comparator.comparingInt(EventDuplicates::informative).reversed())
      .thenComparingInt(e -> e.title() == null ? Integer.MAX_VALUE : e.title().length())
      .thenComparing(SchoolEvent::id, Comparator.nullsLast(Comparator.naturalOrder()));

  private EventDuplicates() {
  }

  /**
   * Keeps one row per event, in the order the input listed them.
   *
   * <p>Apply after any year-group filtering: collapsing first could keep another year's copy of a
   * whole-school event and then filter it away.
   *
   * @param events events as stored, possibly several per real event
   * @return one preferred row per event, each in the position of its group's first row
   */
  static List<SchoolEvent> collapse(final List<SchoolEvent> events) {
    final List<List<Candidate>> groups = new ArrayList<>();
    for (final SchoolEvent event : events) {
      final Candidate candidate =
          new Candidate(event, significantWords(event.title()), kindOf(event));
      groups.stream()
          .filter(group -> group.stream().allMatch(member -> same(member, candidate)))
          .findFirst()
          .ifPresentOrElse(group -> group.add(candidate),
              () -> groups.add(new ArrayList<>(List.of(candidate))));
    }
    return groups.stream().map(EventDuplicates::representative).toList();
  }

  /**
   * The row shown for a group, carrying the group's kind: a club when any member is one.
   */
  private static SchoolEvent representative(final List<Candidate> group) {
    final SchoolEvent shown = group.stream().map(Candidate::event).min(PREFERRED).orElseThrow();
    final boolean club = group.stream().anyMatch(c -> c.kind() == SchoolEvent.EventType.CLUB);
    return club ? shown.withEventType(SchoolEvent.EventType.CLUB) : shown;
  }

  /**
   * An event with its significant title words and its kind worked out once.
   *
   * @param kind the stored type, except that a model-typed term boundary whose title names a
   *     club is a club
   */
  private record Candidate(SchoolEvent event, Set<String> words, SchoolEvent.EventType kind) {
  }

  private static SchoolEvent.EventType kindOf(final SchoolEvent event) {
    return extractorTermBoundary(event)
        && EventTypeClassifier.classify(event.title()) == SchoolEvent.EventType.CLUB
        ? SchoolEvent.EventType.CLUB
        : event.eventType();
  }

  private static boolean extractorTermBoundary(final SchoolEvent event) {
    return event.eventType() == SchoolEvent.EventType.TERM_BOUNDARY
        && event.sourceType() != SchoolSourceType.CALENDAR_FEED;
  }

  private static boolean same(final Candidate first, final Candidate second) {
    final SchoolEvent a = first.event();
    final SchoolEvent b = second.event();
    final Set<String> aWords = first.words();
    final Set<String> bWords = second.words();
    if (!sameDays(a, b) || !yearGroupsCompatible(a.yearGroups(), b.yearGroups())) {
      return false;
    }
    if (first.kind() != second.kind()) {
      return termBoundaryInsideClub(first, second) || termBoundaryInsideClub(second, first);
    }
    if (ONE_PER_SPAN.contains(first.kind())) {
      return true;
    }
    if (aWords.isEmpty() || bWords.isEmpty()) {
      return false;
    }
    if (aWords.containsAll(bWords) || bWords.containsAll(aWords)) {
      return true;
    }
    return first.kind() == SchoolEvent.EventType.TRIP
        && multiDay(a) && multiDay(b)
        && aWords.stream().anyMatch(bWords::contains);
  }

  /** "Autumn Term 2026", typed a term boundary by the extractor, inside an enrichment club. */
  private static boolean termBoundaryInsideClub(final Candidate term, final Candidate club) {
    return term.kind() == SchoolEvent.EventType.TERM_BOUNDARY
        && extractorTermBoundary(term.event())
        && club.kind() == SchoolEvent.EventType.CLUB
        && !term.words().isEmpty()
        && club.words().containsAll(term.words());
  }

  /**
   * Whether two events cover the same days: the same school days when either is a single day,
   * otherwise starting in the same week and ending in the same week.
   */
  private static boolean sameDays(final SchoolEvent a, final SchoolEvent b) {
    if (!multiDay(a) || !multiDay(b)) {
      return schoolDays(a).equals(schoolDays(b));
    }
    return weekOf(a.startDate()).equals(weekOf(b.startDate()))
        && weekOf(a.endDate()).equals(weekOf(b.endDate()));
  }

  private static boolean multiDay(final SchoolEvent event) {
    return event.endDate().isAfter(event.startDate());
  }

  private static LocalDate weekOf(final LocalDate date) {
    return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  /**
   * The first and last school day an event covers.
   *
   * <p>Sources disagree about weekends: the calendar feed runs the autumn half term to Saturday 31
   * October, a newsletter to Friday 30. Both mean the same week off, so a weekend at either end is
   * trimmed before comparing. An event that is nothing but weekend keeps its own dates.
   */
  private static Span schoolDays(final SchoolEvent event) {
    LocalDate start = event.startDate();
    LocalDate end = event.endDate();
    while (isWeekend(start) && start.isBefore(end)) {
      start = start.plusDays(1);
    }
    while (isWeekend(end) && end.isAfter(start)) {
      end = end.minusDays(1);
    }
    return isWeekend(start)
        ? new Span(event.startDate(), event.endDate())
        : new Span(start, end);
  }

  /** First and last day, inclusive. */
  private record Span(LocalDate start, LocalDate end) {
  }

  private static boolean isWeekend(final LocalDate date) {
    return WEEKEND.contains(date.getDayOfWeek());
  }

  /** Whole-school goes with anything; two named sets must share a year. */
  private static boolean yearGroupsCompatible(final List<String> a, final List<String> b) {
    return a.isEmpty() || b.isEmpty() || a.stream().anyMatch(b::contains);
  }

  /**
   * A title's words with the noise, single letters (the "s" of "Parent's") and four-digit years
   * taken out, and a plural "s" dropped so "Parents" and "Parent's" agree. A year is not
   * significant because one source writes "Autumn Half Term 2026" and another "Autumn
   * Half-Term"; a term number is, because "Autumn term 1" and "Autumn term 2" are different
   * terms.
   */
  static Set<String> significantWords(final String title) {
    return Stream.of(SchoolIds.normaliseTitle(title).split(" "))
        .filter(word -> !word.isEmpty() && !NOISE.contains(word))
        .filter(word -> word.length() > 1 || Character.isDigit(word.charAt(0)))
        .filter(word -> !isCalendarYear(word))
        .map(EventDuplicates::singular)
        .collect(Collectors.toUnmodifiableSet());
  }

  /** "clubs" to "club", "parents" to "parent"; "class" and "bus" are left alone. */
  private static String singular(final String word) {
    return word.length() >= MIN_PLURAL_LENGTH && word.endsWith("s") && !word.endsWith("ss")
        ? word.substring(0, word.length() - 1)
        : word;
  }

  private static int informative(final SchoolEvent event) {
    final String title = SchoolIds.normaliseTitle(event.title());
    final long schoolName = Stream.of(title.split(" ")).filter(SCHOOL_NAME::contains).count();
    return significantWords(event.title()).size() - (int) schoolName;
  }

  private static boolean isCalendarYear(final String word) {
    return word.length() == YEAR_LENGTH && word.chars().allMatch(Character::isDigit);
  }

  private static int precedence(final SchoolSourceType sourceType) {
    return sourceType == null ? Integer.MAX_VALUE : sourceType.precedence();
  }

  private static int detail(final SchoolEvent event) {
    return (int) Stream.of(event.time(), event.location(), event.description(), event.sourceUrl())
        .filter(Objects::nonNull)
        .filter(value -> !value.isBlank())
        .count();
  }
}
