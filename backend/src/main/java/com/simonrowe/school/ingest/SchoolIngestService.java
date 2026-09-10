package com.simonrowe.school.ingest;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.chat.StaffDirectory;
import com.simonrowe.school.classify.SchoolEventExtractor;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.SchoolSyncState;
import com.simonrowe.school.model.SchoolSyncStateRepository;
import com.simonrowe.school.model.Visibility;
import com.simonrowe.school.retrieval.SchoolVectorStore;
import com.simonrowe.school.usage.SchoolUsage;
import com.simonrowe.school.usage.SchoolUsageRecorder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;

/**
 * Runs an ingest pass over the school's public sources.
 *
 * <p>The staff directory is still refreshed first, but no longer because anything gates on it —
 * name suppression was removed from Term Time entirely on the owner's instruction. It survives
 * because the crawl populates it and answers about who teaches what read from it.
 */
@Service
public class SchoolIngestService {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolIngestService.class);
  private static final String WEBSITE_SOURCE = "website";
  private static final String CALENDAR_SOURCE = "calendar";
  private static final int CALENDAR_LOOKBACK_MONTHS = 6;
  private static final int CALENDAR_LOOKAHEAD_MONTHS = 18;

  /**
   * The most pages one crawl will fetch, counting those found by following links.
   *
   * <p>Above the crawler's own sitemap cap on purpose, because this one has to cover the
   * discovered pages too — sized against a measured 218 sitemap entries plus the seven seeds
   * and the ~20 pages one hop from them, with room for the school to keep publishing. It is a
   * backstop against a link cycle or a CMS that starts generating addresses, not a tuning knob:
   * hitting it means discovery is misbehaving, and at ten seconds a page the crawl would be
   * over an hour before it got there.
   */
  private static final int MAX_CRAWL_PAGES = 400;

  /**
   * Cheap test for "could this text possibly contain a date".
   *
   * <p>Purely a cost control in front of the model, never a correctness filter — a false positive
   * costs one extraction call that returns nothing, which is harmless. It exists because the
   * website carries ~160 pages and most of them ("Our Vision", "Online Safety", the governors'
   * register) contain no date at all, and paying for an LLM call on every one of them every crawl
   * is real money for a guaranteed empty result.
   */
  private static final Pattern DATE_LIKE = Pattern.compile(
      "(?i)\\b(\\d{1,2}(st|nd|rd|th)?\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)"
          + "|(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+\\d{1,2}"
          + "|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}"
          + "|\\d{4}-\\d{2}-\\d{2}"
          + "|monday|tuesday|wednesday|thursday|friday|saturday|sunday"
          + "|half\\s*term|inset|term\\s+(starts|ends|begins))\\b");

  private final SchoolProperties properties;
  private final CalendarFeedClient calendarClient;
  private final SchoolWebsiteCrawler crawler;
  private final SchoolDocumentWriter documentWriter;
  private final SchoolEventWriter eventWriter;
  private final SchoolSyncStateRepository syncState;
  private final StaffDirectory staffDirectory;
  private final SchoolVectorStore vectorStore;
  private final TokenTextSplitter splitter;
  private final SchoolPdfExtractor pdfExtractor;
  private final SchoolUsageRecorder usageRecorder;
  private final SchoolEventExtractor eventExtractor;
  private final DocumentDateReader dateReader;
  private final SchoolLinkFilter linkFilter;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public SchoolIngestService(
      final SchoolProperties properties,
      final CalendarFeedClient calendarClient,
      final SchoolWebsiteCrawler crawler,
      final SchoolDocumentWriter documentWriter,
      final SchoolEventWriter eventWriter,
      final SchoolSyncStateRepository syncState,
      final StaffDirectory staffDirectory,
      final SchoolVectorStore vectorStore,
      final TokenTextSplitter splitter,
      final SchoolPdfExtractor pdfExtractor,
      final SchoolUsageRecorder usageRecorder,
      final SchoolEventExtractor eventExtractor,
      final DocumentDateReader dateReader,
      final SchoolLinkFilter linkFilter) {
    this.properties = properties;
    this.calendarClient = calendarClient;
    this.crawler = crawler;
    this.documentWriter = documentWriter;
    this.eventWriter = eventWriter;
    this.syncState = syncState;
    this.staffDirectory = staffDirectory;
    this.vectorStore = vectorStore;
    this.splitter = splitter;
    this.pdfExtractor = pdfExtractor;
    this.usageRecorder = usageRecorder;
    this.eventExtractor = eventExtractor;
    this.dateReader = dateReader;
    this.linkFilter = linkFilter;
  }

  /**
   * Refreshes the staff directory from the school's staff page.
   *
   * <p>Called before every ingest pass and on startup. Failure leaves the previous directory in
   * place rather than clearing it: a transient fetch failure must not turn the name gate into a
   * blanket block on content that was fine yesterday.
   *
   * @return how many staff names are now known
   */
  public int refreshStaffDirectory() {
    final Set<String> names = crawler.fetchStaffNames();
    if (names.isEmpty()) {
      LOG.warn("Staff page yielded no names; keeping the previous directory of {}",
          staffDirectory.size());
      return staffDirectory.size();
    }
    staffDirectory.replace(names);
    LOG.info("Staff directory refreshed with {} names", names.size());
    return names.size();
  }

  /**
   * Ingests the calendar feed.
   *
   * @return how many events were written
   */
  public int ingestCalendar() {
    final LocalDate today = LocalDate.now();
    final List<CalendarFeedEvent> events = calendarClient.fetchRange(
        calendarRangeStart(today),
        today.plusMonths(CALENDAR_LOOKAHEAD_MONTHS));

    if (events.isEmpty()) {
      recordFailure(CALENDAR_SOURCE, "calendar feed returned nothing");
      return 0;
    }

    final SchoolDocumentWriter.WriteResult container = documentWriter.write(
        SchoolSourceType.CALENDAR_FEED,
        properties.calendarBaseUrl(),
        "School calendar",
        "The school's published calendar feed.",
        Instant.now(),
        List.of(),
        Visibility.PUBLIC);

    events.forEach(e -> eventWriter.writeFromCalendar(e, container.document().id()));
    recordSuccess(CALENDAR_SOURCE);
    LOG.info("Ingested {} calendar events", events.size());
    return events.size();
  }

  /**
   * Works out how far back the calendar feed should be read.
   *
   * <p>{@code school.ingest-from-date} is a floor on the lookback, not a replacement for it: the
   * feed is asked for the shorter of the two windows. Without this the six-month lookback quietly
   * outlived the cutoff and repopulated the previous academic year's trips and assemblies, which
   * is what capping Gmail alone failed to stop. {@link SchoolEventWriter} enforces the same rule
   * on the way in, so a feed that ignores the range still cannot write pre-cutoff rows.
   *
   * @param today the date the sync is running
   * @return the earliest date to ask the feed for
   */
  private LocalDate calendarRangeStart(final LocalDate today) {
    final LocalDate lookback = today.minusMonths(CALENDAR_LOOKBACK_MONTHS);
    final LocalDate cutoff = properties.ingestFromDate();
    return cutoff != null && cutoff.isAfter(lookback) ? cutoff : lookback;
  }

  /**
   * Crawls and ingests the school website.
   *
   * <p>The work list starts as the sitemap plus {@code school.extra-page-urls}, and grows by one
   * hop: same-host links found in the <b>content</b> of a configured extra page are appended.
   * See {@link #shouldFollowLinksFrom} for why the hop starts only from those pages.
   *
   * @return how many pages produced new or changed content
   */
  public int ingestWebsite() {
    final List<String> urls = crawler.listPages();
    if (urls.isEmpty()) {
      recordFailure(WEBSITE_SOURCE, "sitemap returned nothing");
      return 0;
    }

    // Real publication dates where the CMS knows them. A page not in the feed keeps the date
    // it already had rather than being re-stamped as published today on every crawl.
    final Map<String, Instant> updateTimes = crawler.pageUpdateTimes();

    // Pages we asked for by name rather than found in the sitemap. A sitemap page that fails is
    // one of two hundred and stays at DEBUG; one of these failing means the configured slug has
    // rotted — the school renamed the page — and the symptom is Term Time quietly losing a
    // whole year group's teachers and PE days with nothing in the logs to say so.
    final Set<String> configuredPages = Set.copyOf(properties.extraPageUrls());

    // A queue rather than a for-each, because discovery appends to it while it is being read.
    final Deque<String> queue = new ArrayDeque<>(urls);
    // Both the address we asked for and the address the page says it really is. The CMS serves
    // every page under two URLs, so without the canonical half of this the same text is
    // ingested and embedded twice under two ids.
    final Set<String> seen = new LinkedHashSet<>(urls);
    int discovered = 0;
    int changed = 0;
    int visited = 0;

    while (!queue.isEmpty() && visited < MAX_CRAWL_PAGES) {
      // Between requests, at the TOP of the body rather than the bottom. Every path below can
      // `continue` — a page that 404s, one that is blank, one that turns out to be a second
      // address for a page already crawled — and from the server's point of view each of those
      // was still a request. With the pause at the bottom a run of them went out back to back,
      // which is precisely what the ten seconds robots.txt asks for is meant to prevent, and
      // discovery makes such runs more likely rather than less.
      if (visited > 0 && !crawler.politePause()) {
        // Reports pages VISITED, not pages changed. On an interrupted run "how far did it
        // get" is the question being asked, and changed undercounts it by every page that
        // was read and found unchanged — which on a settled site is nearly all of them.
        LOG.info("Website crawl interrupted after {} pages ({} changed)", visited, changed);
        break;
      }
      final String url = queue.poll();
      visited++;
      final SchoolWebsiteCrawler.CrawledPage page = crawler.fetchPage(url);
      if (page == null || page.text().isBlank()) {
        if (configuredPages.contains(url)) {
          LOG.warn("Configured school page {} could not be read - has it been renamed?", url);
        }
        continue;
      }

      // Stored under the canonical URL, so the friendly path and the /page/?pid= form collapse
      // to one document — and so a citation in an answer shows the address a parent would
      // recognise. Sitemap URLs are already canonical, so this is a no-op for almost all of them.
      final String storedUrl = page.canonicalUrl();
      if (!storedUrl.equals(url) && !seen.add(storedUrl)) {
        LOG.debug("{} is a second address for {}, already crawled", url, storedUrl);
        continue;
      }

      if (shouldFollowLinksFrom(url, configuredPages)) {
        discovered += enqueueLinksFrom(page, queue, seen);
      }

      // Website pages enter at PUBLIC. The name gate is NOT applied here, and that is a
      // correction rather than an oversight: these pages are already published to the open
      // internet by the school itself, so repeating them discloses nothing. Running the gate
      // over whole pages marked 162 of 162 restricted — it was matching navigation furniture
      // ("Contact Us", "Online Safety", "School Website") as people, leaving the public tier
      // with no prose in it whatsoever.
      //
      // The gate still runs where it earns its place: on email (private by default) and on
      // every generated answer served anonymously, which is the point at which a name would
      // actually reach a stranger.
      final SchoolDocumentWriter.WriteResult result = documentWriter.write(
          SchoolSourceType.WEBSITE_PAGE, storedUrl, page.title(), page.text(),
          publishedAtFor(storedUrl, updateTimes), List.of(), Visibility.PUBLIC);

      if (result.changed()) {
        embed(result.document());
        extractEventsFrom(result.document());
        changed++;
      }
      // Deliberately OUTSIDE the changed() branch. contentHash is computed over the extracted
      // TEXT, so a page that swaps which PDF it links to without changing a word of its prose
      // reports UNCHANGED — and this is not a corner case, it is the weekly spelling sheet:
      // the CMS names uploads by content hash, so a new sheet is a new URL behind link text
      // that still reads "Year 6 Spring 1 spellings". Gated inside changed(), the first sheet
      // of the term would be ingested and every later one silently skipped, which presents as
      // Term Time confidently reciting a month-old spelling list.
      //
      // Cheap despite running every crawl: the page HTML is already in hand, and
      // ingestPdfsLinkedFrom fetches only URLs it has not already stored.
      ingestPdfsLinkedFrom(page);
    }
    recordSuccess(WEBSITE_SOURCE);
    LOG.info("Website crawl complete: {} of {} pages changed ({} found by following links)",
        changed, visited, discovered);
    return changed;
  }

  /**
   * Pulls dated facts out of a document and stores them.
   *
   * <p>Website pages and the PDFs they link to used to produce no events whatsoever — only email
   * ran the extractor — so the enrichment timetable, the term-dates PDF and the lunch menu, which
   * are the most current documents the school publishes, contributed nothing to "what is on this
   * week". That was the single largest hole in the event data.
   *
   * <p>Two filters run <b>before</b> the model, in that order, because both are free and the model
   * is not: text with no date-like token in it cannot yield a dated event, and
   * {@link SchoolEventWriter#write} then discards anything falling before
   * {@code school.ingest-from-date}. The cutoff is applied to the model's output rather than to
   * the document, because a page last edited in 2022 can still announce a date this term.
   *
   * @param document the document to read
   * @return how many events were stored
   */
  private int extractEventsFrom(final SchoolDocument document) {
    final String body = document.body();
    if (body == null || !DATE_LIKE.matcher(body).find()) {
      return 0;
    }
    int stored = 0;
    int dropped = 0;
    for (SchoolEvent event : eventExtractor.extract(document)) {
      if (eventWriter.isBeforeCutoff(event)) {
        dropped++;
        continue;
      }
      eventWriter.write(event);
      stored++;
    }
    // Reports what was kept, not what the model returned. The extractor logs its own count
    // before the cutoff runs, so a page whose only date is historic logs "Extracted 1" and
    // stores nothing — which reads as a broken extractor to anyone watching the log.
    if (stored > 0 || dropped > 0) {
      LOG.info("'{}': stored {} event(s), dropped {} before the cutoff",
          document.title(), stored, dropped);
    }
    return stored;
  }

  /**
   * The best publication date available for a crawled page.
   *
   * <p>Order: the CMS feed, then whatever is already stored, then now. The middle step is what
   * stops a re-crawl re-dating an old page to today every time it changes by a byte.
   */
  private Instant publishedAtFor(final String url, final Map<String, Instant> updateTimes) {
    final Instant fromFeed = updateTimes.get(url);
    if (fromFeed != null) {
      return fromFeed;
    }
    return documentWriter.existingPublishedAt(SchoolSourceType.WEBSITE_PAGE, url)
        .orElseGet(Instant::now);
  }

  /**
   * Whether links found on this page should be followed.
   *
   * <p>Only from the configured extra pages, and so only one hop — the deliberate bound on
   * discovery, and the thing to think hardest about before relaxing.
   *
   * <p>The sitemap is the school's own statement of what its site contains, and it is 218
   * pages. Following links from all of them would mostly rediscover those 218, plus the long
   * tail of {@code /school-news/} and {@code /photo-gallery/} that a sitemap-listed page links
   * on to — at ten seconds a page, as {@code robots.txt} asks. The gap worth closing is
   * narrower than that and precisely known: the sitemap omits the year-group subtree entirely,
   * and it is the year pages that link on to home learning, class calendars and the weekly
   * spelling sheets.
   *
   * <p>So the extras are seeds rather than merely additions, and <b>adding a seed is how you
   * widen the crawl</b> — one configuration change, with the cost visible in the page count,
   * rather than a depth setting whose cost depends on someone else's markup.
   *
   * @param url the page just fetched
   * @param configuredPages the resolved {@code school.extra-page-urls}
   * @return true when this page's links should be queued
   */
  private boolean shouldFollowLinksFrom(final String url, final Set<String> configuredPages) {
    return configuredPages.contains(url);
  }

  /**
   * Queues the crawlable links found on a page.
   *
   * @param page the page just fetched
   * @param queue the work list to append to
   * @param seen every URL already queued or crawled, added to here
   * @return how many new pages were queued
   */
  private int enqueueLinksFrom(
      final SchoolWebsiteCrawler.CrawledPage page, final Deque<String> queue,
      final Set<String> seen) {
    int added = 0;
    for (String link : page.links()) {
      // Already absolute, http(s) and fragment-free — CrawledPage.links() guarantees that, so
      // the only questions left here are "may we?" and "have we?".
      if (!linkFilter.isCrawlableWebsitePage(link) || !seen.add(link)) {
        continue;
      }
      queue.add(link);
      added++;
      LOG.debug("Queued {} found on {}", link, page.url());
    }
    if (added > 0) {
      LOG.info("Followed {} link(s) from {}", added, page.url());
    }
    return added;
  }

  /**
   * Ingests the PDFs a page links to.
   *
   * <p>Not optional detail: the school publishes its enrichment timetable, term dates and lunch
   * menu as PDFs, and the enrichment timetable is the most current document on the whole site.
   * Without this the assistant answers "when do clubs start" with "I don't know" while the
   * answer sits one link away.
   *
   * @param page the page whose links to follow
   */
  private void ingestPdfsLinkedFrom(final SchoolWebsiteCrawler.CrawledPage page) {
    for (String pdfUrl : pdfExtractor.findPdfLinks(page.html(), page.url())) {
      // Already stored, so nothing to re-read. This is what makes running on every crawl
      // affordable rather than 133 extra fetches a night, each with its own politeness pause.
      //
      // The corresponding limit, and it is a real one: a PDF REPLACED at the same URL is never
      // re-read. Safe against this CMS specifically, which names uploads by content hash so a
      // replacement always lands on a new URL — but that is a property of their file naming,
      // not of anything here, and it is the assumption to check first if a stale document ever
      // shows up in an answer.
      if (documentWriter.existingPublishedAt(SchoolSourceType.PDF, pdfUrl).isPresent()) {
        continue;
      }
      final String text = pdfExtractor.extractText(pdfUrl);
      if (text == null || text.isBlank()) {
        continue;
      }
      final LocalDate today = LocalDate.now();
      final Optional<LocalDate> stated = dateReader.fromText(text, today);

      // A PDF that dates itself before the cutoff is historic correspondence and is dropped
      // outright — the "letters sent home" archive goes back years. Only a date the document
      // states about ITSELF counts: with no stated date we keep the file, because a policy or a
      // menu carries no letterhead and is current regardless of when it was written.
      if (stated.isPresent() && isBeforeCutoff(stated.get())) {
        LOG.debug("Skipping {} — it dates itself {}", pdfUrl, stated.get());
        if (!crawler.politePause()) {
          return;
        }
        continue;
      }

      final SchoolDocumentWriter.WriteResult pdf = documentWriter.write(
          SchoolSourceType.PDF, pdfUrl, pdfTitle(pdfUrl, page.title()), text,
          publishedAtForPdf(pdfUrl, stated), List.of(), Visibility.PUBLIC);
      if (pdf.changed()) {
        embed(pdf.document());
        extractEventsFrom(pdf.document());
      }
      if (!crawler.politePause()) {
        return;
      }
    }
  }

  /**
   * The best publication date available for a PDF.
   *
   * <p>Order: the date the document states for itself, then whatever is already stored, then now.
   * This used to be {@code Instant.now()} with no cascade at all, which stamped 133 of 147 site
   * PDFs with the time of the crawl — including a November 2022 letter about a Year 1 trip, shown
   * in the console as published today. The stored-date step is what stops a re-crawl re-dating an
   * undated PDF on every pass.
   *
   * @param url the PDF's address
   * @param stated the date the document gives for itself, if any
   * @return the date to record
   */
  private Instant publishedAtForPdf(final String url, final Optional<LocalDate> stated) {
    return stated
        .map(date -> date.atStartOfDay(ZoneId.systemDefault()).toInstant())
        .or(() -> documentWriter.existingPublishedAt(SchoolSourceType.PDF, url))
        .orElseGet(Instant::now);
  }

  /**
   * Whether a date falls before the configured ingest cutoff.
   *
   * @param date the date to test
   * @return true when it is before the cutoff, false when there is no cutoff
   */
  private boolean isBeforeCutoff(final LocalDate date) {
    final LocalDate cutoff = properties.ingestFromDate();
    return cutoff != null && date.isBefore(cutoff);
  }

  /**
   * A readable title for a PDF. The CMS serves most of them from opaque URLs
   * ({@code download.asp?file=819}, or an MD5 filename), so the linking page's title is a far
   * better citation than the URL — an answer that cites "file 819" helps nobody.
   */
  private String pdfTitle(final String pdfUrl, final String pageTitle) {
    final String tail = pdfUrl.substring(pdfUrl.lastIndexOf('/') + 1);
    final boolean opaque = tail.contains("download.asp") || tail.matches("[A-F0-9]{16,}\\.pdf");
    return opaque ? pageTitle + " (PDF)" : tail;
  }

  /**
   * Chunks a document and writes it to the school vector index.
   *
   * <p>The chunk metadata carries {@code visibility}, which is what the retrieval filter reads.
   * A chunk written without it is invisible to the filter's {@code in} clause and so never
   * returned to anyone — failing closed, which is the right way round for a bug here.
   *
   * @param document the document to index
   */
  public void embed(final SchoolDocument document) {
    final Map<String, Object> metadata = new HashMap<>();
    metadata.put("documentId", document.id());
    metadata.put("visibility", document.visibility().name());
    metadata.put("sourceType", document.sourceType().name());
    metadata.put("title", document.title());
    metadata.put("publishedAt", String.valueOf(document.publishedAt()));
    metadata.put("sourceRef", document.sourceRef());
    // A public email attachment has a first-party URL of its own. Website content already has
    // a real sourceRef; email pseudo-refs ("gmail:<id>:<att>") are not links and are filtered
    // out downstream, so this is the only way an attachment becomes citable.
    if (document.sourceType() == SchoolSourceType.PDF
        && document.visibility() == Visibility.PUBLIC
        && document.sourceRef().startsWith("gmail:")) {
      // Stored as a PATH, not an absolute URL, and made absolute at render time by
      // SchoolTools.renderChunk. Baking the origin in here would mean every chunk had to
      // be re-embedded to change hostname — and, worse, the chunks already in the index
      // would keep whatever origin was configured when they were written, because
      // unchanged content never re-embeds.
      metadata.put("attachmentUrl", "/api/school/attachments/" + document.id());
    }

    // Replace, never append. Spring AI ids each chunk randomly, so add() alone leaves the
    // previous pass in place — see SchoolVectorStore.deleteForDocument.
    vectorStore.deleteForDocument(document.id());

    final List<Document> chunks = splitter.apply(
        List.of(new Document(document.body(), metadata)));
    final List<Document> tagged = new ArrayList<>(chunks.size());
    for (Document chunk : chunks) {
      chunk.getMetadata().putAll(metadata);
      tagged.add(chunk);
    }
    vectorStore.add(tagged);

    // Embeddings are billed on input only. Estimated from the chunk text rather than reported:
    // the vector store owns the EmbeddingModel call and does not hand the usage back.
    usageRecorder.recordEstimated(SchoolUsage.Kind.EMBEDDING, "text-embedding-3-small",
        tagged.stream().mapToLong(c -> c.getText() == null ? 0 : c.getText().length()).sum(), 0);
  }

  private void recordSuccess(final String source) {
    final SchoolSyncState state = syncState.findById(source)
        .orElseGet(() -> SchoolSyncState.initial(source));
    syncState.save(new SchoolSyncState(
        source, state.cursor(), Instant.now(), state.lastFailureAt(), state.lastFailureReason(),
        state.pageEtags()));
  }

  private void recordFailure(final String source, final String reason) {
    final SchoolSyncState state = syncState.findById(source)
        .orElseGet(() -> SchoolSyncState.initial(source));
    syncState.save(new SchoolSyncState(
        source, state.cursor(), state.lastSuccessAt(), Instant.now(), reason, state.pageEtags()));
    LOG.warn("School ingest failed for {}: {}", source, reason);
  }
}
