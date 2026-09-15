package com.simonrowe.school.classify;

import com.embabel.agent.api.common.Ai;
import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.ingest.SchoolIds;
import com.simonrowe.school.model.AcademicYear;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.usage.SchoolUsage;
import com.simonrowe.school.usage.SchoolUsageRecorder;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pulls dated facts out of school prose — newsletters, letters, PDF timetables.
 *
 * <p>Uses Embabel's {@link Ai} rather than a raw Spring AI call, matching {@code
 * ArticleSectionWriter} and {@code DigestComposer}. {@code createObjectIfPossible} is the right
 * method here rather than {@code createObject}: most emails contain no dated facts at all, and
 * "nothing to extract" must be an ordinary null rather than an exception thrown once per
 * newsletter.
 *
 * <p>The calendar feed remains the authority for term structure. This exists because the feed
 * carries 17 events for a whole year while the newsletters carry the rest of school life, and
 * {@code SchoolSourceType} precedence means anything found here loses to the feed when they
 * describe the same day.
 */
@Component
public class SchoolEventExtractor {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolEventExtractor.class);
  private static final int MAX_CHARS = 12000;

  private static final String PROMPT = """
      Extract every dated school event from the text below.

      Rules:
      - Only include something if you can give it a specific calendar date. Skip anything vague \
      ("later this term", "TBC", "soon").
      - Resolve relative dates using the reference date given below. "Next Friday" in a letter \
      dated 3 September 2026 is 11 September 2026.
      - Dates are British: 03/09/2026 is 3 September 2026, never 9 March.
      - Use ISO format, yyyy-MM-dd, for startDate and endDate. For a single-day event set both \
      to the same date.
      - eventType must be exactly one of: TERM_BOUNDARY, HALF_TERM, INSET, CLUB, TRIP, OTHER.
      - yearGroups uses the form "Year 3" or "Reception". Leave it empty for whole-school events.
      - description: one short sentence on what the event involves and
      anything a parent must do (book, bring something, arrive early).
      Empty if the text does not say.
      - location: where it happens, only if the text says. Leave empty otherwise.
      - time: the time of day as written, e.g. "6:00pm" or "9:15am". Leave empty if not given.
      - Never invent a description, location or time. An empty field is
      correct when the text is silent; a plausible guess is not.
      - url: a web address for this specific event - a booking page, an announcement, \
      an open day listing. Copy it EXACTLY as it appears in the text. If the text contains \
      no address for this event, leave it empty. Never construct, complete or guess one: a \
      link that goes nowhere is worse than no link, and anything you did not copy will be \
      discarded anyway.
      - If the event belongs to a named school, venue or organisation OTHER than the one \
      this text is from, begin the title with that name - "Kingsdale Foundation School open \
      day", not "Open day". The reader may be comparing four schools at once and a bare \
      title tells them nothing about which.
      - Return an empty list if the text contains no dated events. Do not invent any.

      Reference date (the date this text was published): %s

      Title of the document this text came from: %s

      Text:
      %s
      """;

  private final Ai ai;
  private final SchoolProperties properties;
  private final SchoolUsageRecorder usageRecorder;

  public SchoolEventExtractor(final Ai ai, final SchoolProperties properties,
      final SchoolUsageRecorder usageRecorder) {
    this.ai = ai;
    this.properties = properties;
    this.usageRecorder = usageRecorder;
  }

  /**
   * Extracts events from a document.
   *
   * @param document the source document; its tier and publication date are inherited by every
   *     event found in it
   * @return the events, possibly empty. Never throws — a failed extraction must not fail the
   *     ingest of the document itself, whose prose is still useful to retrieval
   */
  public List<SchoolEvent> extract(final SchoolDocument document) {
    final String body = document.body();
    if (body == null || body.isBlank()) {
      return List.of();
    }
    final String text = body.length() > MAX_CHARS ? body.substring(0, MAX_CHARS) : body;
    final LocalDate published = document.publishedAt() == null
        ? LocalDate.now()
        : document.publishedAt().atZone(ZoneId.systemDefault()).toLocalDate();

    final ExtractedSchoolEvents extracted;
    try {
      extracted = ai.withLlm(properties.guardrailModel())
          .createObjectIfPossible(
              PROMPT.formatted(published, titleOf(document), text),
              ExtractedSchoolEvents.class);
    } catch (Exception e) {
      LOG.warn("Event extraction failed for '{}': {}", document.title(), e.getMessage());
      return List.of();
    }
    usageRecorder.recordEstimated(SchoolUsage.Kind.EXTRACT, properties.guardrailModel(),
        PROMPT.length() + text.length(),
        extracted == null ? 0 : extracted.events().size() * 120L);
    if (extracted == null) {
      return List.of();
    }

    final List<SchoolEvent> events = new ArrayList<>();
    for (ExtractedSchoolEvents.Event candidate : extracted.events()) {
      toEvent(candidate, document).ifPresent(events::add);
    }
    if (!events.isEmpty()) {
      LOG.info("Extracted {} dated events from '{}'", events.size(), document.title());
    }
    return events;
  }

  private java.util.Optional<SchoolEvent> toEvent(
      final ExtractedSchoolEvents.Event candidate, final SchoolDocument document) {
    if (candidate.title() == null || candidate.title().isBlank()) {
      return java.util.Optional.empty();
    }
    final LocalDate start = parseDate(candidate.startDate());
    if (start == null) {
      // Dropped individually rather than failing the batch: one "TBC" in a newsletter must not
      // discard the eleven real dates alongside it.
      LOG.debug("Skipping '{}': unparseable start date '{}'",
          candidate.title(), candidate.startDate());
      return java.util.Optional.empty();
    }
    final LocalDate end = parseDate(candidate.endDate());
    final String academicYear = AcademicYear.of(start);

    return java.util.Optional.of(new SchoolEvent(
        SchoolIds.eventId(academicYear, start, candidate.title()),
        candidate.title().trim(),
        start,
        end == null || end.isBefore(start) ? start : end,
        true,
        parseType(candidate.eventType()),
        candidate.yearGroups(),
        academicYear,
        document.sourceType(),
        document.id(),
        document.visibility(),
        blankToNull(candidate.description()),
        blankToNull(candidate.location()),
        blankToNull(candidate.time()),
        // The one field the model is allowed to contribute that a reader will click. Verified
        // against the source text rather than taken on trust — see verbatimUrl.
        verbatimUrl(candidate.url(), document.body())));
  }

  /**
   * A title for the document, for the prompt's benefit.
   *
   * <p>The body of a fetched web page does not contain its own {@code <title>}, so without this
   * a page headed "Open Events" at a school named only in the browser tab produces events called
   * "Open Evening" with no school attached to them. Those then collide, by title, with every
   * other school's open evening on the same date.
   */
  private static String titleOf(final SchoolDocument document) {
    return document.title() == null || document.title().isBlank()
        ? "(untitled)"
        : document.title().trim();
  }

  /**
   * Returns the model's URL only if it appears literally in the text it was reading.
   *
   * <p>The single defence against an invented link, and it is a complete one: a model asked to
   * copy an address out of a document either copied it or did not, and an address that is not in
   * the document cannot have come from it. Checked as a plain substring rather than by parsing —
   * the question is "did you copy this", not "is this a well-formed URL".
   *
   * <p>A trailing {@code .} or {@code ,} swept up from prose is trimmed before the check, or a
   * link at the end of a sentence fails verification and is dropped for punctuation. That
   * quantifier is <b>possessive</b>: the input is a string a model produced, which is exactly
   * the unbounded-input case where a greedy quantifier against an anchor backtracks
   * super-linearly. It changes no match here — a maximal run of those characters at the end of
   * the input is the only thing either form can match — it only removes the backtracking.
   *
   * @param candidate what the model returned, possibly null
   * @param body the text the model was shown
   * @return the verified URL, or null
   */
  static String verbatimUrl(final String candidate, final String body) {
    if (candidate == null || body == null) {
      return null;
    }
    final String trimmed = candidate.trim().replaceAll("[.,;:)]++$", "");
    if (trimmed.isBlank() || !trimmed.toLowerCase(Locale.ROOT).startsWith("http")) {
      return null;
    }
    return body.contains(trimmed) ? trimmed : null;
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static LocalDate parseDate(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value.trim());
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static SchoolEvent.EventType parseType(final String value) {
    if (value == null || value.isBlank()) {
      return SchoolEvent.EventType.OTHER;
    }
    try {
      return SchoolEvent.EventType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return SchoolEvent.EventType.OTHER;
    }
  }
}
