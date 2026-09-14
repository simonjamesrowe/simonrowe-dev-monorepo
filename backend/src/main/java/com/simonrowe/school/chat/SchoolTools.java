package com.simonrowe.school.chat;

import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import com.simonrowe.school.retrieval.SchoolAudience;
import com.simonrowe.school.retrieval.SchoolRetrievalService;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * The tools the school assistant can call.
 *
 * <p>Each instance is bound to one {@link SchoolAudience} and constructed per request. That is the
 * tool-boundary half of FR-025: the audience is not a tool parameter the model could get wrong or
 * a caller could spoof, it is baked into the object before the model ever sees it.
 *
 * <p>Every tool returns text already shaped for citation — title, date and source — because an
 * answer that cannot say where a date came from is not verifiable, and this corpus contains
 * contradictory dates by construction.
 */
public class SchoolTools {

  private static final int MAX_PROSE_CHARS = 6000;

  /**
   * The character budget for whole documents in {@link #getRecentCommunications}.
   *
   * <p>Twice {@link #MAX_PROSE_CHARS}, deliberately. That budget caps a set of retrieved
   * fragments, where losing the tail costs one more fragment; this one caps whole school
   * communications for a window a parent asked about, and a typical week is five short letters
   * and one newsletter of about five thousand characters. Sized so that week arrives complete,
   * because arriving nearly complete is what makes the assistant say a newsletter did not mention
   * something it mentioned in its last paragraph.
   */
  private static final int MAX_COMMUNICATION_CHARS = 12000;

  private static final String TERM_DATES_LABEL = "Checking term dates";
  private static final String INSET_LABEL = "Looking up INSET days";
  private static final String EVENTS_LABEL = "Finding school events";
  private static final String CLUBS_LABEL = "Looking up clubs";
  private static final String SEARCH_LABEL = "Searching school communications";
  private static final String COMMUNICATIONS_LABEL = "Reading recent school letters";
  private static final String TODAY_LABEL = "Checking today's date";

  private final SchoolQueryService queries;
  private final SchoolRetrievalService retrieval;
  private final SchoolAudience audience;
  private final List<String> yearGroups;
  private final SchoolStreamPublisher streamPublisher;
  private final String sessionId;
  private final String publicBaseUrl;

  /**
   * Creates a tool set for one request.
   *
   * @param queries the dated-fact query service
   * @param retrieval prose retrieval
   * @param audience which tiers this request may read
   * @param yearGroups the year groups the visitor selected, empty for none
   * @param streamPublisher publishes tool start/end frames, or null for a non-streaming caller
   * @param sessionId the topic to publish tool frames to, or null when not streaming
   * @param publicBaseUrl absolute origin that attachment citations are built against
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public SchoolTools(final SchoolQueryService queries, final SchoolRetrievalService retrieval,
      final SchoolAudience audience, final List<String> yearGroups,
      final SchoolStreamPublisher streamPublisher, final String sessionId,
      final String publicBaseUrl) {
    this.queries = queries;
    this.retrieval = retrieval;
    this.audience = audience;
    this.yearGroups = yearGroups == null ? List.of() : List.copyOf(yearGroups);
    this.streamPublisher = streamPublisher;
    this.sessionId = sessionId;
    this.publicBaseUrl = publicBaseUrl;
  }

  /**
   * Runs a tool, bracketing it with the start/end frames the UI renders as an activity line.
   *
   * <p>The end frame is emitted from a finally block: a tool that throws must not leave a
   * spinner running forever in the browser, which is exactly what an unguarded pair does the
   * first time a query fails.
   */
  private String tracked(final String label, final java.util.function.Supplier<String> work) {
    publish(true, label);
    try {
      return work.get();
    } finally {
      publish(false, label);
    }
  }

  private void publish(final boolean start, final String label) {
    if (streamPublisher == null || sessionId == null) {
      return;
    }
    if (start) {
      streamPublisher.toolStart(sessionId, label);
    } else {
      streamPublisher.toolEnd(sessionId, label);
    }
  }

  /**
   * Term dates and half terms for an academic year.
   *
   * @param academicYear e.g. {@code 2026/27}, or blank for the current year
   * @return the term boundaries and half terms, or a plain statement that none are known
   */
  @Tool(description = "Get the term start and end dates and half term breaks for the school. "
      + "Use this for any question about terms, holidays or half term.")
  public String getTermDates(
      @ToolParam(description = "Academic year like 2026/27. Leave blank for the current year.",
          required = false) final String academicYear) {
    return tracked(TERM_DATES_LABEL, () -> {
      final List<SchoolEvent> boundaries =
          queries.ofType(SchoolEvent.EventType.TERM_BOUNDARY, academicYear, audience);
      final List<SchoolEvent> halfTerms =
          queries.ofType(SchoolEvent.EventType.HALF_TERM, academicYear, audience);
      final List<SchoolEvent> all = java.util.stream.Stream.concat(
          boundaries.stream(), halfTerms.stream())
          .sorted(java.util.Comparator.comparing(SchoolEvent::startDate))
          .toList();
      return render(all, "No term dates are recorded for that year.");
    });
  }

  /**
   * INSET days, when school is closed to pupils.
   *
   * @param academicYear e.g. {@code 2026/27}, or blank for the current year
   * @return the INSET days
   */
  @Tool(description = "Get the INSET days (staff training days when school is closed to pupils).")
  public String getInsetDays(
      @ToolParam(description = "Academic year like 2026/27. Leave blank for the current year.",
          required = false) final String academicYear) {
    return tracked(INSET_LABEL,
        () -> render(queries.ofType(SchoolEvent.EventType.INSET, academicYear, audience),
            "No INSET days are recorded for that year."));
  }

  /**
   * Everything happening between two dates.
   *
   * @param from ISO date, inclusive
   * @param to ISO date, inclusive
   * @return the events in that window
   */
  @Tool(description = "Get school events between two dates, together with the newsletters, "
      + "letters and PDFs that describe them. Use for 'what is on this week', "
      + "'what is happening next month' and similar.")
  public String getEventsBetween(
      @ToolParam(description = "Start date, ISO format yyyy-MM-dd") final String from,
      @ToolParam(description = "End date, ISO format yyyy-MM-dd") final String to) {
    final LocalDate start;
    final LocalDate end;
    try {
      start = LocalDate.parse(from);
      end = LocalDate.parse(to);
    } catch (RuntimeException e) {
      return "Those dates could not be read. Use yyyy-MM-dd.";
    }
    return tracked(EVENTS_LABEL, () -> {
      final List<SchoolEvent> events = queries.eventsBetween(start, end, yearGroups, audience);
      final String dated = render(events, "Nothing is recorded for that period.");
      final String context = supportingProse(events, start, end);
      return context.isEmpty()
          ? dated
          : dated + "\n\n" + "Supporting detail from school communications:\n\n" + context;
    });
  }

  /**
   * The prose behind a set of dated facts.
   *
   * <p>An event row carries a date, a title and not much else; the letter announcing it carries
   * the time, the venue, what to bring, the booking link and often a PDF. Those live in two
   * different stores, so answering "what is on this week" from the event table alone produced
   * a bare list of titles when the detail was sitting one search away. Retrieval is keyed on the
   * event titles themselves, which is what pulls back the specific document rather than whatever
   * happens to be topically near the phrase "this week".
   *
   * <p>Returns empty rather than a placeholder when nothing is found: the caller concatenates,
   * and a "nothing found" line under a good list of events reads as though the events were
   * doubtful.
   *
   * @param events the events found for the window, possibly empty
   * @param start window start, used when there are no event titles to search on
   * @param end window end
   * @return rendered chunks, or an empty string
   */
  private String supportingProse(
      final List<SchoolEvent> events, final LocalDate start, final LocalDate end) {
    final String query = events.isEmpty()
        ? "school events and activities between %s and %s".formatted(start, end)
        : events.stream().map(SchoolEvent::title).collect(Collectors.joining(", "));
    final List<Document> hits = retrieval.search(query, audience);
    if (hits.isEmpty()) {
      return "";
    }
    final String body = hits.stream().map(this::renderChunk).collect(Collectors.joining("\n\n"));
    return body.length() > MAX_PROSE_CHARS ? body.substring(0, MAX_PROSE_CHARS) : body;
  }

  /**
   * Enrichment and after-school clubs.
   *
   * @return the clubs on record
   */
  @Tool(description = "Get enrichment and after-school clubs, including when they start.")
  public String getClubs() {
    return tracked(CLUBS_LABEL,
        () -> render(queries.ofType(SchoolEvent.EventType.CLUB, null, audience),
            "No clubs are recorded."));
  }

  /**
   * Everything the school published in a date window, newest first.
   *
   * <p>The document-shaped counterpart to {@link #getEventsBetween}, and the answer to a class of
   * question that had none. "Was there a newsletter last week" is a yes or a no; "what was in the
   * newsletter from the 11th" names one document. Neither is a similarity question, and putting
   * them through {@link #searchSchoolInformation} produced a confident answer from a newsletter
   * five weeks old, because a dozen weekly newsletters are nearly indistinguishable to an
   * embedding and there was no recency signal anywhere in the path.
   *
   * <p>It is also the only tool that can say <b>nothing was published</b>, which
   * {@code searchSchoolInformation} structurally cannot: an empty top-k means "nothing was
   * similar", never "nothing exists".
   *
   * @param from ISO date, inclusive
   * @param to ISO date, inclusive
   * @return an index of what was published, then as many full texts as the budget allows
   */
  @Tool(description = "List what the school actually sent or published between two dates - "
      + "newsletters, letters, emails, website pages and PDFs - newest first, with their full "
      + "text. Use this for 'was there a newsletter last week', 'what was in the latest "
      + "newsletter', 'what is the latest news' and any question about a named date or a "
      + "recent period. Prefer it over searching when the question is about WHEN something was "
      + "sent rather than WHAT it said.")
  public String getRecentCommunications(
      @ToolParam(description = "Start date, ISO format yyyy-MM-dd") final String from,
      @ToolParam(description = "End date, ISO format yyyy-MM-dd") final String to) {
    final LocalDate start;
    final LocalDate end;
    try {
      start = LocalDate.parse(from);
      end = LocalDate.parse(to);
    } catch (RuntimeException e) {
      return "Those dates could not be read. Use yyyy-MM-dd.";
    }
    return tracked(COMMUNICATIONS_LABEL, () -> {
      final List<SchoolDocument> found = queries.communicationsBetween(start, end, audience);
      if (found.isEmpty()) {
        // Stated as a fact about the window, not as "nothing was found". The difference is the
        // whole value of this tool: it licenses the assistant to tell a parent there was no
        // newsletter last week, which a similarity search never can.
        return "The school published nothing between %s and %s.".formatted(start, end);
      }
      return renderCommunications(found, start, end);
    });
  }

  /**
   * Renders a window's documents: an index of every one, then full texts until the budget runs
   * out.
   *
   * <p>The index comes first and covers <b>all</b> of them, because the question underneath is
   * often "did the school send X" and that must be answerable even when X's text was budgeted
   * out. Bodies are then included whole, in order, while they fit — never truncated mid-document.
   * A half-quoted newsletter is worse than an omitted one: the assistant cannot tell that it is
   * reading a fragment, so it answers "the newsletter does not mention it" about a paragraph that
   * was cut off. Anything omitted is named in the index and counted in the closing line, so the
   * assistant knows there is more and can say so.
   */
  private String renderCommunications(
      final List<SchoolDocument> found, final LocalDate start, final LocalDate end) {
    final StringBuilder out = new StringBuilder(
        "%d item(s) published between %s and %s, newest first:\n"
            .formatted(found.size(), start, end));
    for (SchoolDocument document : found) {
      out.append("- %s \"%s\" (%s)%s\n".formatted(
          localDateOf(document.publishedAt()), document.title(), document.sourceType(),
          urlOf(document).isEmpty() ? "" : " " + urlOf(document)));
    }

    out.append("\nFull text follows, newest first.\n");
    int budget = MAX_COMMUNICATION_CHARS;
    int included = 0;
    for (SchoolDocument document : found) {
      final String body = document.body() == null ? "" : document.body();
      if (included > 0 && body.length() > budget) {
        break;
      }
      out.append('\n').append(renderDocument(document));
      budget -= body.length();
      included++;
    }
    if (included < found.size()) {
      // Named rather than silent. An assistant that cannot see that it was given 3 of 9
      // documents will answer as though it read all nine.
      out.append("\n\nThe full text of the remaining %d older item(s) was not included. "
          .formatted(found.size() - included))
          .append("Narrow the dates, or search for one by name, to read them.");
    }
    return out.toString();
  }

  /**
   * Renders one stored document in the same envelope retrieved chunks arrive in.
   *
   * <p>Deliberately identical in shape to {@link #renderChunk} so the assistant reads a
   * whole document and a retrieved fragment the same way, and cites both the same way.
   */
  private String renderDocument(final SchoolDocument document) {
    return """
        <<<SOURCE title="%s" published="%s" type="%s" url="%s">>>
        %s
        <<<END SOURCE>>>"""
        .formatted(document.title(), document.publishedAt(), document.sourceType(),
            urlOf(document), document.body());
  }

  /**
   * The address a reader could click for a stored document, or empty when there is not one.
   *
   * <p>Mirrors {@link #renderChunk}: a real web address, or a first-party attachment URL for a
   * public email PDF, and never a {@code gmail:} pseudo-reference — which is meaningless to a
   * reader, and which the model will happily render as a link if it is handed one.
   */
  private String urlOf(final SchoolDocument document) {
    final String ref = document.sourceRef() == null ? "" : document.sourceRef();
    if (ref.startsWith("https://")) {
      return ref;
    }
    if (document.sourceType() == SchoolSourceType.PDF
        && document.visibility() == Visibility.PUBLIC
        && ref.startsWith("gmail:")) {
      return absolute("/api/school/attachments/" + document.id());
    }
    return "";
  }

  /** The publication date as a plain date, for the index lines. */
  private String localDateOf(final java.time.Instant instant) {
    return instant == null
        ? "undated"
        : instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
  }

  /**
   * Free-text search over school communications, for questions that are not about dates.
   *
   * @param query what to look for
   * @return matching passages with their sources, or a statement that nothing was found
   */
  @Tool(description = "Search school newsletters, letters and website pages for information that "
      + "is not a date - school lunches, uniform, PE days, policies and similar.")
  public String searchSchoolInformation(
      @ToolParam(description = "What to look for") final String query) {
    return tracked(SEARCH_LABEL, () -> {
      final List<Document> hits = retrieval.search(query, audience);
      if (hits.isEmpty()) {
        return "Nothing in the school communications covers that.";
      }
      final String body = hits.stream()
          .map(this::renderChunk)
          .collect(Collectors.joining("\n\n"));
      return body.length() > MAX_PROSE_CHARS ? body.substring(0, MAX_PROSE_CHARS) : body;
    });
  }

  /**
   * Today's date and the current academic year, so the model never has to guess.
   *
   * @return today and the academic year
   */
  @Tool(description = "Get today's date and the current academic year. Call this before doing "
      + "any date arithmetic.")
  public String getToday() {
    return tracked(TODAY_LABEL, () -> "Today is %s. The current academic year is %s."
        .formatted(queries.today(), queries.currentAcademicYear()));
  }

  private String renderChunk(final Document document) {
    final Object title = document.getMetadata().get("title");
    final Object published = document.getMetadata().get("publishedAt");
    final Object source = document.getMetadata().get("sourceType");
    // Only a real web address. Email-derived documents have a pseudo-ref (`gmail:<id>:<att>`)
    // which is meaningless to a reader and must never be offered as a link.
    final Object ref = document.getMetadata().get("sourceRef");
    final Object attachment = document.getMetadata().get("attachmentUrl");
    // Made absolute HERE rather than at ingest. A model writing a markdown link has to supply
    // an origin, and given a bare "/api/school/attachments/<id>" it invents one — in practice
    // the school's own domain, because that is what the rest of the answer cites, so every PDF
    // link resolved to kilmorieschool.co.uk and 404'd. Doing it at render time also means the
    // origin is not frozen into the index: chunks already embedded pick up a hostname change
    // on the next answer rather than needing a full re-embed they would never get, since
    // unchanged content never re-embeds.
    final String url = attachment instanceof String a && !a.isBlank()
        ? absolute(a)
        : ref instanceof String s && s.startsWith("https://") ? s : "";
    // Built with a single format call over one literal. Written as
    //   "…%s" + "\n<<<END SOURCE>>>".formatted(args)
    // it silently does the wrong thing: `.formatted` binds to the SECOND literal only, so the
    // placeholders in the first are never substituted and the chunk reaches the model as
    // `title="%s" … %s` with no document text in it whatsoever. Nothing errors; the assistant
    // just quietly stops being able to answer from prose.
    return """
        <<<SOURCE title="%s" published="%s" type="%s" url="%s">>>
        %s
        <<<END SOURCE>>>"""
        .formatted(title, published, source, url, document.getText());
  }

  /**
   * Turns a site-relative attachment path into an absolute first-party URL.
   *
   * @param path the stored path, or an already-absolute URL from an older chunk
   * @return an absolute URL the model can cite verbatim
   */
  private String absolute(final String path) {
    return path.startsWith("/") ? publicBaseUrl + path : path;
  }

  private String render(final List<SchoolEvent> events, final String emptyMessage) {
    if (events.isEmpty()) {
      return emptyMessage;
    }
    return events.stream().map(event -> {
      final String when = event.startDate().equals(event.endDate())
          ? event.startDate().toString()
          : event.startDate() + " to " + event.endDate();
      final String years = event.yearGroups().isEmpty()
          ? "whole school"
          : String.join(", ", event.yearGroups());
      final StringBuilder line = new StringBuilder(
          "- %s: %s (%s)".formatted(when, event.title(), years));
      // Everything the source actually gave us. These were being dropped, which is why answers
      // could name an event and a date and then tell the reader to ask the school for the rest.
      appendIfPresent(line, " time: ", event.time());
      appendIfPresent(line, " where: ", event.location());
      appendIfPresent(line, " details: ", event.description());
      appendIfPresent(line, " link: ", event.sourceUrl());
      line.append(" [source: ").append(event.sourceType()).append(']');
      return line.toString();
    }).collect(Collectors.joining("\n"));
  }

  private static void appendIfPresent(
      final StringBuilder target, final String label, final String value) {
    if (value != null && !value.isBlank()) {
      target.append(label).append(value.trim());
    }
  }
}
