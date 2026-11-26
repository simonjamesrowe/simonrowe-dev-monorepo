package com.simonrowe.coparent.school;

import com.simonrowe.school.model.SchoolEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Builds what goes on the calendar from the school rows a suggestion stands for, by rule. Used
 * when no model is asked, and for anything the model returned that does not check out.
 */
final class SuggestionDetails {

  static final int MAX_TITLE = 120;
  private static final int MAX_NOTES = 1500;
  /** The fingerprint of a suggestion whose source rows are all gone. */
  static final String GONE = "gone";

  /** The school's name, which every website and PDF extraction prefixes its titles with. */
  private static final Pattern SCHOOL_PREFIX = Pattern.compile(
      "^kilmorie\\s++(?:primary\\s++school\\s*+)?+[-–:]?+\\s*+",
      Pattern.CASE_INSENSITIVE);

  /**
   * The row whose details are used: the most authoritative source first (declaration order of
   * {@code SchoolSourceType}), then one that gives a time, then one that gives a place.
   */
  static final Comparator<SchoolEvent> PREFERRED = Comparator
      .comparing((SchoolEvent e) -> e.sourceType() == null ? Integer.MAX_VALUE
          : e.sourceType().ordinal())
      .thenComparing(e -> SchoolTimes.parse(e.time()).isEmpty())
      .thenComparing(e -> e.location() == null || e.location().isBlank())
      .thenComparing(SchoolEvent::id, Comparator.nullsLast(Comparator.naturalOrder()));

  private SuggestionDetails() {
  }

  /**
   * The details for a group of rows describing one event.
   *
   * @param rows the rows, at least one
   * @param title a title to use instead of the preferred row's, or null
   * @return the details
   */
  static SchoolEventSuggestion.Details from(final List<SchoolEvent> rows, final String title) {
    final List<SchoolEvent> ordered = rows.stream().sorted(PREFERRED).toList();
    final SchoolEvent first = ordered.getFirst();
    final Optional<SchoolTimes.Range> times = ordered.stream()
        .map(e -> SchoolTimes.parse(e.time())).flatMap(Optional::stream).findFirst();
    final String location = ordered.stream().map(SchoolEvent::location)
        .filter(value -> value != null && !value.isBlank()).findFirst().map(String::strip)
        .orElse(null);
    final String chosenTitle = title == null || title.isBlank() ? tidyTitle(first.title())
        : cap(title.strip(), MAX_TITLE);
    final boolean timed = times.isPresent() && first.startDate().equals(first.endDate());
    return new SchoolEventSuggestion.Details(
        chosenTitle,
        first.startDate(),
        first.endDate(),
        timed ? SchoolTimes.format(times.get().start()) : null,
        timed ? SchoolTimes.format(times.get().end()) : null,
        location,
        notes(ordered),
        ordered.stream().map(SchoolEvent::time).filter(Objects::nonNull).findFirst()
            .orElse(null));
  }

  /**
   * The school's title without the school's own name in front of it.
   *
   * @param title a title as stored
   * @return the tidied title, capped
   */
  static String tidyTitle(final String title) {
    if (title == null || title.isBlank()) {
      return "School event";
    }
    final String stripped = SCHOOL_PREFIX.matcher(title.strip()).replaceFirst("").strip();
    return cap(stripped.isEmpty() ? title.strip() : stripped, MAX_TITLE);
  }

  /**
   * What decides whether an accepted event has changed: each row's dates, time and place. Not
   * the description, which the extractor rewords every time it reads a letter again.
   *
   * @param rows the rows the suggestion stands for that still exist
   * @return a stable hash, or {@link #GONE} when there are none
   */
  static String fingerprint(final List<SchoolEvent> rows) {
    if (rows.isEmpty()) {
      return GONE;
    }
    final StringBuilder text = new StringBuilder();
    rows.stream().sorted(Comparator.comparing(SchoolEvent::id)).forEach(e -> text
        .append(e.id()).append('|').append(e.startDate()).append('|').append(e.endDate())
        .append('|').append(e.allDay()).append('|').append(Objects.toString(e.time(), ""))
        .append('|').append(Objects.toString(e.location(), "")).append('\n'));
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(
          digest.digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is always available", impossible);
    }
  }

  private static String notes(final List<SchoolEvent> ordered) {
    final StringBuilder notes = new StringBuilder();
    ordered.stream().map(SchoolEvent::description)
        .filter(value -> value != null && !value.isBlank()).findFirst()
        .ifPresent(description -> notes.append(description.strip()));
    ordered.stream().map(SchoolEvent::time)
        .filter(value -> value != null && !value.isBlank()
            && SchoolTimes.parse(value).isEmpty() && !value.equalsIgnoreCase("all day"))
        .findFirst()
        .ifPresent(time -> appendLine(notes, "Time: " + time.strip()));
    ordered.stream().map(SchoolEvent::sourceUrl)
        .filter(value -> value != null && value.startsWith("https://")).findFirst()
        .ifPresent(url -> appendLine(notes, "From the school: " + url));
    appendLine(notes, "Suggested from Term Time.");
    return cap(notes.toString(), MAX_NOTES);
  }

  private static void appendLine(final StringBuilder notes, final String line) {
    if (!notes.isEmpty()) {
      notes.append("\n\n");
    }
    notes.append(line);
  }

  static String cap(final String text, final int max) {
    return text.length() <= max ? text : text.substring(0, max).strip();
  }
}
