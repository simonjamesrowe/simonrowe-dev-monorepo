package com.simonrowe.school.admin;

import com.simonrowe.school.classify.SchoolEventExtractor;
import com.simonrowe.school.ingest.SchoolDocumentWriter;
import com.simonrowe.school.ingest.SchoolEventWriter;
import com.simonrowe.school.ingest.SchoolIds;
import com.simonrowe.school.ingest.SchoolIngestService;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolEventRepository;
import com.simonrowe.school.model.SchoolLink;
import com.simonrowe.school.model.SchoolLinkRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Takes text an administrator pasted into the console and turns it into school content.
 *
 * <p>The case it exists for: a parents' WhatsApp group posts a run of secondary-school open
 * evenings — a school name, a date, a link, one per message — and none of it will ever reach
 * Term Time through the mailbox, the calendar feed or the school's own website, because none of
 * it is Kilmorie's. Retyping it into a form with a date picker and a title box would be slower
 * than reading the messages, so the input is the text itself and a model does the reading.
 *
 * <p>Nothing here is new machinery. The note becomes an ordinary {@link SchoolDocument}, the
 * dates come out through the same {@link SchoolEventExtractor} that reads newsletters, and the
 * links are fetched by the same {@link SchoolLinkFetcher} that serves the Fetch button — SSRF
 * guard, redirect revalidation and all. What this class contributes is the wiring and three
 * decisions:
 *
 * <ul>
 *   <li><b>Public immediately.</b> The approval queue exists because email arrives from somebody
 *       else and a human has not read it. A note is typed by an administrator who has read it —
 *       pasting it <i>is</i> the decision, and routing it through a queue would mean approving
 *       your own typing.</li>
 *   <li><b>Every link is fetched, without asking.</b> The standing rule is "record links, never
 *       follow them", and it is about <i>email</i>: a sender the school does not control can put
 *       any address in a message body. These addresses were pasted deliberately by the one
 *       person allowed to press Fetch, so asking them to press it six more times is ceremony.
 *       The SSRF guard still runs on every one of them.</li>
 *   <li><b>{@link com.simonrowe.school.ingest.SchoolLinkFilter#isWorthOffering} is deliberately
 *       NOT applied.</b> It drops bare homepages, because in a mail footer a homepage is the
 *       single most repeated link there is and carries nothing the website crawl has not read.
 *       Here a bare homepage is often the whole message — "St Matthew's Academy:
 *       https://www.stmatthewacademy.co.uk" — and dropping it would silently discard the only
 *       address for that school.</li>
 * </ul>
 */
@Component
public class SchoolNoteService {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolNoteService.class);

  /**
   * Addresses in the pasted text.
   *
   * <p>The repetition is <b>possessive</b>. This runs over text of unbounded length that a
   * person pasted from somewhere else, which is exactly the input class where a greedy
   * quantifier inside an alternation turns into catastrophic backtracking; a single possessive
   * character class cannot backtrack at all, so the match is linear whatever arrives.
   *
   * <p>The excluded characters are the ones that end a URL in prose rather than belong to it:
   * whitespace, quotes, angle brackets and brackets. Trailing sentence punctuation is stripped
   * afterwards — it cannot be excluded here, since a real path may contain a dot or a comma.
   */
  private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'()\\[\\]]++");

  /** Trailing punctuation swept up from prose: "…/open-days/." and "…/open-events," */
  private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,;:!?]++$");

  /**
   * How much pasted text is read.
   *
   * <p>Generous next to the extractor's own 12,000-character prompt window, because the whole
   * text is stored and embedded even where only the first part is read for dates.
   */
  private static final int MAX_CHARS = 50_000;

  private static final DateTimeFormatter TITLE_DATE =
      DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

  private final SchoolDocumentWriter documentWriter;
  private final SchoolDocumentRepository documents;
  private final SchoolEventRepository events;
  private final SchoolEventExtractor eventExtractor;
  private final SchoolEventWriter eventWriter;
  private final SchoolIngestService ingestService;
  private final SchoolLinkRepository links;
  private final SchoolLinkFetcher linkFetcher;
  private final Clock clock;

  /**
   * Notes whose links are being fetched right now.
   *
   * <p>Deliberately in-memory, and deliberately not inferred from link status. A {@code PENDING}
   * link means "nobody has decided about this", which after a restart is exactly what an
   * interrupted fetch leaves behind — so a UI that polled on link status alone would spin for
   * ever on a note whose fetch died with the process. An empty set after a restart is the
   * truthful answer: nothing is running, and the links are there to be fetched by hand.
   */
  private final Set<String> fetching = ConcurrentHashMap.newKeySet();

  /**
   * Single-threaded and daemon, for the same reasons as {@link SchoolIngestTrigger}: this
   * application has no {@code @EnableAsync}, so an {@code @Async} method here would run
   * synchronously with no warning and block the request for the length of every fetch.
   */
  private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
    final Thread thread = new Thread(runnable, "school-note-links");
    thread.setDaemon(true);
    return thread;
  });

  @SuppressWarnings("checkstyle:ParameterNumber")
  public SchoolNoteService(
      final SchoolDocumentWriter documentWriter,
      final SchoolDocumentRepository documents,
      final SchoolEventRepository events,
      final SchoolEventExtractor eventExtractor,
      final SchoolEventWriter eventWriter,
      final SchoolIngestService ingestService,
      final SchoolLinkRepository links,
      final SchoolLinkFetcher linkFetcher,
      final Clock clock) {
    this.documentWriter = documentWriter;
    this.documents = documents;
    this.events = events;
    this.eventExtractor = eventExtractor;
    this.eventWriter = eventWriter;
    this.ingestService = ingestService;
    this.links = links;
    this.linkFetcher = linkFetcher;
    this.clock = clock;
  }

  /**
   * Stores a pasted note, reads the dates out of it and starts fetching its links.
   *
   * <p>Returns as soon as the dates are read, which is one model call. The links are fetched on
   * a background thread because there may be six of them against six different hosts, each with
   * a thirty-second timeout — three minutes is not a request.
   *
   * @param text the pasted text
   * @param title a title, or blank to derive one
   * @param yearGroups the year groups this note is about, applied to every event it yields that
   *     does not name its own. Empty means whole-school
   * @return what was stored and what was understood
   * @throws IllegalArgumentException when the text is blank
   */
  public Note save(final String text, final String title, final List<String> yearGroups) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("There is nothing to save — paste some text first.");
    }
    final String body = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
    final Instant now = clock.instant();

    final SchoolDocumentWriter.WriteResult result = documentWriter.write(
        SchoolSourceType.PASTED_NOTE,
        // Content-derived, so pasting the same messages twice updates one note rather than
        // accumulating near-duplicates that then yield the same events twice over.
        "paste:" + sha256(body),
        titleFor(title, body, now),
        body,
        // Now, always. This is the reference date the extractor resolves "Sat 19 Sept" against,
        // and a note is pasted within days of the messages it came from.
        now,
        yearGroups,
        Visibility.PUBLIC);

    final SchoolDocument document = result.document();
    ingestService.embed(document);
    final List<SchoolEvent> found = extractEvents(document, yearGroups);
    final List<SchoolLink> recorded = recordLinks(document, body);

    if (!recorded.isEmpty() && fetching.add(document.id())) {
      executor.submit(() -> fetchAll(document.id(), yearGroups));
    }
    LOG.info("Pasted note {} stored: {} event(s), {} link(s) queued",
        document.id(), found.size(), recorded.size());
    return read(document.id()).orElseThrow();
  }

  /**
   * Reads back a note, its events and its links.
   *
   * <p>The events include those extracted from the pages the links led to, not only from the
   * note itself — which is the whole point of fetching them. A note reading "Harris Boys — 17
   * Sept — &lt;link&gt;" yields one bare event; the page behind it yields the same evening with
   * a time and a booking link, and the operator needs to see that it worked.
   *
   * @param id the note's document id
   * @return the note, or empty when there is no such note
   */
  public Optional<Note> read(final String id) {
    return documents.findById(id)
        .filter(d -> d.sourceType() == SchoolSourceType.PASTED_NOTE)
        .map(document -> {
          final List<SchoolLink> found = links.findBySourceDocumentId(document.id());
          final Set<String> sources = new LinkedHashSet<>();
          sources.add(document.id());
          found.stream().map(SchoolLink::fetchedDocumentId).filter(java.util.Objects::nonNull)
              .forEach(sources::add);
          return new Note(
              document,
              events.findBySourceDocumentIdIn(List.copyOf(sources)).stream()
                  .sorted(java.util.Comparator.comparing(SchoolEvent::startDate))
                  .toList(),
              found.stream()
                  .sorted(java.util.Comparator.comparing(SchoolLink::discoveredAt))
                  .toList(),
              fetching.contains(document.id()));
        });
  }

  /**
   * The most recent notes, newest first.
   *
   * @param limit how many to return
   * @return the notes
   */
  public List<Note> recent(final int limit) {
    return documents.findBySourceTypeOrderByPublishedAtDesc(SchoolSourceType.PASTED_NOTE).stream()
        .limit(Math.max(1, limit))
        .map(document -> read(document.id()).orElse(null))
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  /**
   * Pulls the dated facts out of a note and stores them.
   *
   * <p>The extractor is shared with every other source, so this adds only the year-group scope
   * and the cutoff, which {@link SchoolEventWriter#write} applies for itself.
   */
  private List<SchoolEvent> extractEvents(
      final SchoolDocument document, final List<String> yearGroups) {
    final List<SchoolEvent> stored = new ArrayList<>();
    for (SchoolEvent event : eventExtractor.extract(document)) {
      final SchoolEvent scoped = event.withYearGroupScope(yearGroups);
      if (eventWriter.isBeforeCutoff(scoped)) {
        continue;
      }
      eventWriter.write(scoped);
      stored.add(scoped);
    }
    return stored;
  }

  /**
   * Records every address in the text, without following any of them yet.
   *
   * <p>Existing rows are left exactly as they are, so re-pasting a note whose link was declined
   * does not re-offer it and does not re-fetch it. That is the same rule the mail path applies,
   * and here it is what makes a correction to a note safe to paste again.
   */
  private List<SchoolLink> recordLinks(final SchoolDocument document, final String body) {
    final List<SchoolLink> recorded = new ArrayList<>();
    for (String url : urlsIn(body)) {
      final String id = SchoolIds.documentId(
          SchoolSourceType.PASTED_NOTE, document.id() + '|' + url);
      recorded.add(links.findById(id).orElseGet(() -> links.save(new SchoolLink(
          id, document.id(), url, url, clock.instant(),
          SchoolLink.Status.PENDING, null, null))));
    }
    return recorded;
  }

  /**
   * Fetches every link of a note that has not been decided, one at a time.
   *
   * <p>Never throws. A note whose third link times out must still keep the four that worked, and
   * the failure is recorded on the link row where the operator can see it and retry.
   */
  private void fetchAll(final String documentId, final List<String> yearGroups) {
    try {
      for (SchoolLink link : links.findBySourceDocumentId(documentId)) {
        if (link.status() != SchoolLink.Status.PENDING) {
          continue;
        }
        try {
          linkFetcher.fetch(link.id())
              .map(SchoolLink::fetchedDocumentId)
              .filter(java.util.Objects::nonNull)
              .ifPresent(fetched -> applyScope(fetched, yearGroups));
        } catch (RuntimeException e) {
          LOG.warn("Fetching {} for note {} failed: {}", link.url(), documentId, e.getMessage());
        }
      }
    } finally {
      fetching.remove(documentId);
    }
  }

  /**
   * Narrows the events of a fetched page to the note's year groups.
   *
   * <p>Applied here rather than inside {@link SchoolLinkFetcher} so that the fetcher stays a
   * general-purpose "fetch this link" and does not have to know why it was called. The scope
   * belongs to the note: an open-evening page fetched from a Year 6 note is Year 6 content, and
   * the same page fetched from the Documents screen is not.
   */
  private void applyScope(final String fetchedDocumentId, final List<String> yearGroups) {
    if (yearGroups == null || yearGroups.isEmpty()) {
      return;
    }
    for (SchoolEvent event : events.findBySourceDocumentIdIn(List.of(fetchedDocumentId))) {
      final SchoolEvent scoped = event.withYearGroupScope(yearGroups);
      if (scoped != event) {
        events.save(scoped);
      }
    }
  }

  /**
   * Every http(s) address in the text, in order, de-duplicated.
   *
   * @param body the pasted text
   * @return the addresses found
   */
  static List<String> urlsIn(final String body) {
    final Set<String> found = new LinkedHashSet<>();
    final Matcher matcher = URL.matcher(body == null ? "" : body);
    while (matcher.find()) {
      final String url = TRAILING_PUNCTUATION.matcher(matcher.group()).replaceAll("");
      if (!url.isBlank()) {
        found.add(url);
      }
    }
    return List.copyOf(found);
  }

  /**
   * A title for the note.
   *
   * <p>The first non-blank line when there is one, because a pasted run of messages almost
   * always starts with the thing it is about; a date stamp otherwise. Never blank — the title is
   * what every citation in an answer names, and "" cited as a source reads as a bug.
   */
  private String titleFor(final String supplied, final String body, final Instant at) {
    if (supplied != null && !supplied.isBlank()) {
      return supplied.trim();
    }
    final String firstLine = body.lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .findFirst()
        .orElse("");
    final String stamp = "Pasted note, " + TITLE_DATE.format(at.atZone(zone()));
    if (firstLine.isEmpty()) {
      return stamp;
    }
    return firstLine.length() > 80 ? firstLine.substring(0, 80).trim() + "…" : firstLine;
  }

  private ZoneId zone() {
    return clock.getZone();
  }

  private static String sha256(final String input) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * A pasted note and everything that came of it.
   *
   * @param document the note itself
   * @param events every dated fact from the note and from the pages its links led to
   * @param links the addresses found in it, with what has happened to each
   * @param fetching true while links are still being fetched on this instance
   */
  public record Note(
      SchoolDocument document,
      List<SchoolEvent> events,
      List<SchoolLink> links,
      boolean fetching) {
  }
}
