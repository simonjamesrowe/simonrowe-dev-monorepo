package com.simonrowe.school.ingest;

import com.simonrowe.school.model.SchoolEvent.EventType;
import java.util.Locale;

/**
 * Works out what kind of dated fact an event title describes.
 *
 * <p>Deliberately rules rather than a model call. The vocabulary a primary school uses for these
 * five things is tiny and stable ("INSET", "half term", "term ends"), the classification is
 * consulted on every date question, and a model that occasionally files an INSET day as
 * {@code OTHER} would make "when are the INSET days" quietly incomplete — an error with no visible
 * symptom. Rules are wrong in ways you can read.
 */
public final class EventTypeClassifier {

  private EventTypeClassifier() {
  }

  /**
   * Classifies an event from its title.
   *
   * @param title the event name
   * @return the event type, defaulting to {@link EventType#OTHER}
   */
  public static EventType classify(final String title) {
    if (title == null || title.isBlank()) {
      return EventType.OTHER;
    }
    final String t = title.toLowerCase(Locale.ROOT);

    // "Inset" and "INSET day" are the common spellings; "training day" and "staff development"
    // are what the same day is called when it appears in a newsletter rather than the calendar.
    if (t.contains("inset") || t.contains("in-set")
        || t.contains("training day") || t.contains("staff development")) {
      return EventType.INSET;
    }
    if (t.contains("half term") || t.contains("half-term")) {
      return EventType.HALF_TERM;
    }
    // Checked after half term on purpose: "half term ends" contains "term end" and is a half
    // term, not a term boundary.
    if (t.contains("term start") || t.contains("term begin") || t.contains("term end")
        || t.contains("start of term") || t.contains("end of term")
        || t.contains("first day of term") || t.contains("last day of term")
        || t.contains("children return") || t.contains("school closes")
        || t.contains("school reopens")) {
      return EventType.TERM_BOUNDARY;
    }
    if (t.contains("club") || t.contains("enrichment") || t.contains("after school")
        || t.contains("lamda") || t.contains("karate")) {
      return EventType.CLUB;
    }
    if (t.contains("trip") || t.contains("visit to") || t.contains("residential")
        || t.contains("excursion")) {
      return EventType.TRIP;
    }
    return EventType.OTHER;
  }
}
