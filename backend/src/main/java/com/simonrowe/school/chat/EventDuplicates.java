package com.simonrowe.school.chat;

import com.simonrowe.school.ingest.SchoolIds;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolSourceType;
import java.time.DayOfWeek;
import java.time.LocalDate;
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
 * <p>Two events are the same when they have the same type, cover the same school days, do not
 * name different year groups, and either
 * <ul>
 *   <li>are a half term or an INSET day, of which there is only ever one on a given span; or</li>
 *   <li>one title's significant words are all found in the other's ("Autumn Term Enrichment
 *       Clubs" in "Kilmorie Primary School Autumn Term Enrichment Clubs start and end"); or</li>
 *   <li>are trips over several days and share a significant word ("Year 6 residential trip to
 *       PGL" and "Year 6 Trip to PGL Grovesnor Hall", both Monday to Friday).</li>
 * </ul>
 *
 * <p>The last rule is limited to multi-day trips on purpose. Single-day events share words all the
 * time without being the same ("Jo's Class Assembly" and "Elis' Class Assembly"), and so do
 * term-long clubs ("Chess Club" and "Football Club", both September to November). Two different
 * residentials for the same year over exactly the same days do not happen.
 *
 * <p>An event joins a group only if it is the same as <b>every</b> event already in it, never
 * through a chain. A generic "Class Assembly" matches both "Jo's Class Assembly" and "Elis' Class
 * Assembly" on the same morning; chaining through it would merge two classes' assemblies into one.
 *
 * <p>Precision is preferred over recall: a duplicate left showing costs a reader a second glance,
 * an event merged into another one hides it. So dates must match, and differently worded titles
 * on a single day are kept apart.
 */
final class EventDuplicates {

  /**
   * Words that say nothing about which event is meant: the school's own name, which a website or
   * PDF extraction prefixes to everything, "year" (year groups are compared on their own field),
   * and connecting words.
   */
  private static final Set<String> NOISE = Set.of(
      "kilmorie", "primary", "school", "year", "years",
      "the", "a", "an", "of", "and", "to", "for", "at", "in", "on", "with");

  /** Closures: one per span, so the type and the dates identify them whatever the title. */
  private static final Set<SchoolEvent.EventType> ONE_PER_SPAN =
      EnumSet.of(SchoolEvent.EventType.HALF_TERM, SchoolEvent.EventType.INSET);

  private static final int YEAR_LENGTH = 4;

  /**
   * Which row of a set of duplicates is shown: the most authoritative source, then the one that
   * says the most, then the shortest title (the school-name prefix is noise to a reader too),
   * then the id, so the choice never changes between two reads of the same data.
   */
  private static final Comparator<SchoolEvent> PREFERRED = Comparator
      .comparingInt((SchoolEvent e) -> precedence(e.sourceType()))
      .thenComparing(Comparator.comparingInt(EventDuplicates::detail).reversed())
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
      final Candidate candidate = new Candidate(event, significantWords(event.title()));
      groups.stream()
          .filter(group -> group.stream().allMatch(member -> same(member, candidate)))
          .findFirst()
          .ifPresentOrElse(group -> group.add(candidate),
              () -> groups.add(new ArrayList<>(List.of(candidate))));
    }
    return groups.stream()
        .map(group -> group.stream().map(Candidate::event).min(PREFERRED).orElseThrow())
        .toList();
  }

  /** An event with its significant title words worked out once. */
  private record Candidate(SchoolEvent event, Set<String> words) {
  }

  private static boolean same(final Candidate first, final Candidate second) {
    final SchoolEvent a = first.event();
    final SchoolEvent b = second.event();
    final Set<String> aWords = first.words();
    final Set<String> bWords = second.words();
    if (a.eventType() != b.eventType()
        || !schoolDays(a).equals(schoolDays(b))
        || !yearGroupsCompatible(a.yearGroups(), b.yearGroups())) {
      return false;
    }
    if (ONE_PER_SPAN.contains(a.eventType())) {
      return true;
    }
    if (aWords.isEmpty() || bWords.isEmpty()) {
      return false;
    }
    if (aWords.containsAll(bWords) || bWords.containsAll(aWords)) {
      return true;
    }
    return a.eventType() == SchoolEvent.EventType.TRIP
        && a.endDate().isAfter(a.startDate())
        && aWords.stream().anyMatch(bWords::contains);
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
    final DayOfWeek day = date.getDayOfWeek();
    return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
  }

  /** Whole-school goes with anything; two named sets must share a year. */
  private static boolean yearGroupsCompatible(final List<String> a, final List<String> b) {
    return a.isEmpty() || b.isEmpty() || a.stream().anyMatch(b::contains);
  }

  /**
   * A title's words with the noise, single letters (the "s" of "Parent's") and four-digit years
   * taken out. A year is not significant because one source writes "Autumn Half Term 2026" and
   * another "Autumn Half-Term"; a term number is, because "Autumn term 1" and "Autumn term 2" are
   * different terms.
   */
  static Set<String> significantWords(final String title) {
    return Stream.of(SchoolIds.normaliseTitle(title).split(" "))
        .filter(word -> !word.isEmpty() && !NOISE.contains(word))
        .filter(word -> word.length() > 1 || Character.isDigit(word.charAt(0)))
        .filter(word -> !isCalendarYear(word))
        .collect(Collectors.toUnmodifiableSet());
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
