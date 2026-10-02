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
import com.simonrowe.school.model.YearGroups;
import com.simonrowe.school.retrieval.SchoolVectorStore;
import com.simonrowe.school.usage.SchoolUsage;
import com.simonrowe.school.usage.SchoolUsageRecorder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
      """
      (?i)\\b(\\d{1,2}(st|nd|rd|th)?\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\
      |(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+\\d{1,2}\
      |\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}\
      |\\d{4}-\\d{2}-\\d{2}\
      |monday|tuesday|wednesday|thursday|friday|saturday|sunday\
      |half\\s*term|inset|term\\s+(starts|ends|begins))\\b""");

  /** Link text that names nothing, so is no use in a title. */
  private static final Pattern GENERIC_LINK_TEXT = Pattern.compile(
      "(?i)(?:click here|here|download|view|open|pdf|link|read more)[.!]?");

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
    boolean interrupted = false;
    // Every PDF linked from any page this crawl, with every page that linked it. A PDF has one
    // document however many pages link it, so which year it belongs to can only be decided once
    // all of them have been seen — see reattributePdfs.
    final Map<String, PdfSighting> pdfSightings = new LinkedHashMap<>();

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
        interrupted = true;
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
      //
      // The year group comes from the page's own address (/year-three, /year-four-home-learning)
      // and is written on every crawl, not only when the text changes, so pages stored before
      // year groups were recorded pick theirs up on the next pass.
      final SchoolDocumentWriter.WriteResult result = documentWriter.write(
          SchoolSourceType.WEBSITE_PAGE, storedUrl, page.title(), page.text(),
          publishedAtFor(storedUrl, updateTimes), YearGroups.fromPageUrl(storedUrl),
          Visibility.PUBLIC);

      if (result.reindex()) {
        embed(result.document());
      }
      if (result.changed()) {
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
      if (!ingestPdfsLinkedFrom(page, storedUrl, pdfSightings)) {
        LOG.info("Website crawl interrupted while reading PDFs linked from {}", storedUrl);
        interrupted = true;
        break;
      }
    }
    if (!interrupted && !queue.isEmpty()) {
      // Stopped by the page cap with pages still waiting: just as partial as a crawl that was
      // interrupted, and just as unsafe to settle PDF year groups on.
      LOG.warn("Website crawl stopped at the {}-page cap with {} page(s) unread",
          MAX_CRAWL_PAGES, queue.size());
      interrupted = true;
    }
    if (interrupted) {
      // Deliberately skipped, not run on what was seen. A PDF's year groups are the union over
      // every page linking it, and a crawl stopped halfway would narrow a PDF shared by four
      // year pages to whichever ones it reached — excluding it from the other years' answers.
      LOG.info("Skipping PDF year-group attribution for an interrupted crawl");
    } else {
      reattributePdfs(pdfSightings);
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
   * @param pageUrl the page's canonical address, which is what its year group is read from
   * @param sightings every PDF seen this crawl, added to here
   * @return false when the crawl was interrupted and should stop
   */
  private boolean ingestPdfsLinkedFrom(
      final SchoolWebsiteCrawler.CrawledPage page, final String pageUrl,
      final Map<String, PdfSighting> sightings) {
    final List<String> pageYears = YearGroups.fromPageUrl(pageUrl);
    for (SchoolPdfExtractor.PdfLink link : pdfExtractor.findPdfLinksWithText(
        page.html(), page.url())) {
      final String pdfUrl = link.url();
      final String title = pdfTitle(pdfUrl, page.title(), link.text());
      sightings.computeIfAbsent(pdfUrl, url -> new PdfSighting()).seenOn(pageYears, title);

      // Already stored, so nothing to re-read. This is what makes running on every crawl
      // affordable rather than 133 extra fetches a night, each with its own politeness pause.
      //
      // The corresponding limit, and it is a real one: a PDF REPLACED at the same URL is never
      // re-read. Safe against this CMS specifically, which names uploads by content hash so a
      // replacement always lands on a new URL — but that is a property of their file naming,
      // not of anything here, and it is the assumption to check first if a stale document ever
      // shows up in an answer.
      final Optional<Instant> stored =
          documentWriter.existingPublishedAt(SchoolSourceType.PDF, pdfUrl);
      if (stored.isPresent()) {
        if (isCrawlStamp(stored.get()) && !redate(pdfUrl, stored.get())) {
          return false;
        }
        continue;
      }
      final SchoolPdfExtractor.FetchedPdf fetched = pdfExtractor.fetch(pdfUrl);
      if (fetched == null || fetched.text() == null || fetched.text().isBlank()) {
        continue;
      }
      final String text = fetched.text();
      final LocalDate today = LocalDate.now();
      final Optional<LocalDate> stated = dateReader.fromText(text, today);

      // A PDF that dates itself before the cutoff is historic correspondence and is dropped
      // outright — the "letters sent home" archive goes back years. Only a date the document
      // states about ITSELF counts: with no stated date we keep the file, because a policy or a
      // menu carries no letterhead and is current regardless of when it was written.
      if (stated.isPresent() && isBeforeCutoff(stated.get())) {
        LOG.debug("Skipping {} — it dates itself {}", pdfUrl, stated.get());
        if (!crawler.politePause()) {
          return false;
        }
        continue;
      }

      // Provisionally the linking page's year group. reattributePdfs settles it at the end of
      // the crawl, once every page linking this file has been seen.
      final SchoolDocumentWriter.WriteResult pdf = documentWriter.write(
          SchoolSourceType.PDF, pdfUrl, title, text,
          publishedAtForPdf(pdfUrl, stated, fetched.lastModified()), pageYears,
          Visibility.PUBLIC);
      if (pdf.reindex()) {
        embed(pdf.document());
      }
      if (pdf.changed()) {
        extractEventsFrom(pdf.document());
      }
      if (!crawler.politePause()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Settles the year groups and title of every PDF seen this crawl.
   *
   * <p>A PDF belongs to the year groups of the pages that link it — the "Fun ways to learn
   * spellings" sheet is on four year pages and belongs to all four — and to <b>nobody in
   * particular</b> as soon as any page that is not a year page links it too. That last rule is the
   * conservative one: the assistant leaves out a source tagged only for other years, so wrongly
   * narrowing a whole-school letter would hide it, while wrongly widening one costs only a
   * missing label.
   *
   * <p>Also how PDFs stored before year groups were recorded get theirs: they are never re-read,
   * so this is the only write that reaches them.
   */
  private void reattributePdfs(final Map<String, PdfSighting> sightings) {
    int updated = 0;
    for (Map.Entry<String, PdfSighting> entry : sightings.entrySet()) {
      final PdfSighting sighting = entry.getValue();
      final Optional<SchoolDocument> refreshed = documentWriter.refreshMetadata(
          SchoolSourceType.PDF, entry.getKey(), sighting.title(), null, sighting.yearGroups());
      if (refreshed.isPresent()) {
        embed(refreshed.get());
        updated++;
      }
    }
    if (updated > 0) {
      LOG.info("Updated the title or year groups of {} of {} linked PDF(s)",
          updated, sightings.size());
    }
  }

  /**
   * Replaces a crawl-time date on a stored PDF with the school server's own date for the file.
   *
   * <p>Undated PDFs used to be stamped with the time of the crawl that first read them, so a
   * spelling sheet uploaded in January reached the assistant as "published 10 September 2026" and
   * was offered as this week's list. The school's server sends a {@code Last-Modified} for every
   * file, which is the upload date; this asks for it once per stored PDF.
   *
   * @param pdfUrl the PDF's address
   * @param stored the date currently recorded, known to be a crawl stamp
   * @return false when the crawl was interrupted and should stop
   */
  private boolean redate(final String pdfUrl, final Instant stored) {
    // Truncated when the server gives no date: a whole-second value is not a crawl stamp, so the
    // file is asked about once rather than on every crawl for ever.
    final Instant date = pdfExtractor.lastModified(pdfUrl)
        .orElse(stored.truncatedTo(ChronoUnit.SECONDS));
    documentWriter.refreshMetadata(SchoolSourceType.PDF, pdfUrl, null, date, null)
        .ifPresent(this::embed);
    return crawler.politePause();
  }

  /**
   * Whether a stored PDF date is the crawl-time stamp rather than a real date.
   *
   * <p>Told apart by precision, because nothing else records it. Every real date this class
   * writes is whole-second or coarser — a date the PDF states is midnight, and {@code
   * Last-Modified} is an HTTP date — while the old fallback was {@code Instant.now()}, which Mongo
   * keeps to the millisecond. One crawl stamp in a thousand lands on a whole second and is never
   * corrected; that is the accepted cost of not adding a field to every document to say where its
   * date came from.
   */
  private static boolean isCrawlStamp(final Instant publishedAt) {
    return publishedAt.getNano() != 0;
  }

  /**
   * The best publication date available for a PDF.
   *
   * <p>Order: the date the document states for itself, then the date the school's server gives
   * for the file, then whatever is already stored, then now. Without the server's date an undated
   * file was stamped with the crawl time, which is how a January spelling sheet came to be
   * offered as the current one.
   *
   * @param url the PDF's address
   * @param stated the date the document gives for itself, if any
   * @param lastModified the server's date for the file, if any
   * @return the date to record, never finer than a second — see {@link #isCrawlStamp}
   */
  private Instant publishedAtForPdf(
      final String url, final Optional<LocalDate> stated, final Optional<Instant> lastModified) {
    return stated
        .map(date -> date.atStartOfDay(ZoneId.systemDefault()).toInstant())
        .or(() -> lastModified)
        .or(() -> documentWriter.existingPublishedAt(SchoolSourceType.PDF, url))
        .orElseGet(() -> Instant.now().truncatedTo(ChronoUnit.SECONDS));
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
  private String pdfTitle(final String pdfUrl, final String pageTitle, final String linkText) {
    final String tail = pdfUrl.substring(pdfUrl.lastIndexOf('/') + 1);
    final boolean opaque = tail.contains("download.asp") || tail.matches("[A-F0-9]{16,}\\.pdf");
    if (!opaque) {
      return tail;
    }
    // The link text as well, when it says anything. Every year's home-learning page is titled
    // "Home Learning", so the page title alone gave four different spelling sheets one name.
    return isDescriptive(linkText)
        ? pageTitle + " - " + linkText + " (PDF)"
        : pageTitle + " (PDF)";
  }

  /** Link text worth putting in a title: not blank, not "click here", not absurdly long. */
  private static boolean isDescriptive(final String linkText) {
    return linkText != null
        && linkText.length() >= 3
        && linkText.length() <= 120
        && !GENERIC_LINK_TEXT.matcher(linkText).matches();
  }

  /**
   * Every PDF link seen on one crawl, and the pages it was seen on.
   *
   * <p>Mutable, and private to one call of {@link #ingestWebsite}.
   */
  private static final class PdfSighting {

    private final Set<String> years = new LinkedHashSet<>();
    private boolean onWholeSchoolPage;
    private String title;
    private boolean titleFromYearPage;

    void seenOn(final List<String> pageYears, final String linkTitle) {
      if (pageYears.isEmpty()) {
        onWholeSchoolPage = true;
      }
      years.addAll(pageYears);
      // A year page's wording wins, and after that the first page to link it: the crawl order is
      // fixed, so the title does not flip between crawls and re-index the file each time.
      if (title == null || (!titleFromYearPage && !pageYears.isEmpty())) {
        title = linkTitle;
        titleFromYearPage = !pageYears.isEmpty();
      }
    }

    List<String> yearGroups() {
      return onWholeSchoolPage ? List.of() : YearGroups.sanitise(List.copyOf(years));
    }

    String title() {
      return title;
    }
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
    // Which year groups this is about, empty for whole-school. Stored on the chunk because the
    // chunk is all the assistant is shown and all the search can filter on: the year ticked on a
    // pasted note was recorded on the document and nowhere else, so a Year 3 spelling list
    // reached the model with nothing to say it was Year 3's and lost to a Year 6 one.
    metadata.put("yearGroups", List.copyOf(document.yearGroups()));
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
