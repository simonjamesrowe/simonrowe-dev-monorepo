package com.simonrowe.school.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.chat.StaffDirectory;
import com.simonrowe.school.classify.SchoolEventExtractor;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.SchoolSyncStateRepository;
import com.simonrowe.school.model.Visibility;
import com.simonrowe.school.retrieval.SchoolVectorStore;
import com.simonrowe.school.usage.SchoolUsageRecorder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

/**
 * Following links out of the year-group pages.
 *
 * <p>The question that drove this: <i>"what are the spellings for this week?"</i>. The answer
 * lives on {@code /year-six-home-learning}, which is absent from the sitemap and reachable only
 * as a link from {@code /year-six} — and the spelling list itself is a PDF linked from there in
 * turn. Sitemap-only crawling could never reach either.
 */
class SchoolCrawlDiscoveryTest {

  private static final String BASE = "https://www.kilmorieschool.co.uk";
  private static final String SEED = BASE + "/year-six";
  private static final String HOME_LEARNING = BASE + "/year-six-home-learning";
  private static final String SPELLINGS_PDF = BASE + "/_site/data/files/938A752E.pdf";

  private SchoolWebsiteCrawler crawler;
  private SchoolDocumentWriter documentWriter;
  private SchoolPdfExtractor pdfExtractor;
  private SchoolVectorStore vectorStore;
  private SchoolEventExtractor eventExtractor;
  private SchoolIngestService service;
  private final List<String> fetched = new ArrayList<>();

  @BeforeEach
  void setUp() {
    crawler = mock(SchoolWebsiteCrawler.class);
    documentWriter = mock(SchoolDocumentWriter.class);
    pdfExtractor = mock(SchoolPdfExtractor.class);
    vectorStore = mock(SchoolVectorStore.class);
    eventExtractor = mock(SchoolEventExtractor.class);

    final SchoolProperties properties = new SchoolProperties(
        true, null, List.of(), List.of(), null, BASE, null, 0, null, null, null, 0L, null,
        List.of("/year-six"));

    when(crawler.politePause()).thenReturn(true);
    when(crawler.pageUpdateTimes()).thenReturn(Map.of());
    when(pdfExtractor.findPdfLinksWithText(anyString(), anyString())).thenReturn(List.of());
    // Nothing has been ingested before, so no PDF is skipped as already-stored.
    when(documentWriter.existingPublishedAt(any(), anyString())).thenReturn(Optional.empty());
    when(documentWriter.write(any(), anyString(), any(), any(), any(), any(), any()))
        .thenAnswer(call -> new SchoolDocumentWriter.WriteResult(
            document(call.getArgument(1)), true));

    service = new SchoolIngestService(
        properties,
        mock(CalendarFeedClient.class),
        crawler,
        documentWriter,
        mock(SchoolEventWriter.class),
        mock(SchoolSyncStateRepository.class),
        mock(StaffDirectory.class),
        vectorStore,
        mock(TokenTextSplitter.class),
        pdfExtractor,
        mock(SchoolUsageRecorder.class),
        eventExtractor,
        mock(DocumentDateReader.class),
        new SchoolLinkFilter(properties));
  }

  private static SchoolDocument document(final String sourceRef) {
    return new SchoolDocument(
        "id-" + sourceRef, SchoolSourceType.WEBSITE_PAGE, sourceRef, "title", "body",
        Instant.now(), Instant.now(), Visibility.PUBLIC, null, null, null, null, false,
        List.of(), "hash", null);
  }

  /** Serves a page with the given content links, recording that it was fetched. */
  private void serve(final String url, final String canonical, final String... links) {
    when(crawler.fetchPage(url)).thenAnswer(call -> {
      fetched.add(url);
      return new SchoolWebsiteCrawler.CrawledPage(
          url, canonical, "Title", "Readable text", "<html/>", List.of(links));
    });
  }

  /** Serves a page whose HTML links the given PDFs, each with its link text. */
  private void servePdfs(final String url, final SchoolPdfExtractor.PdfLink... pdfs) {
    final String html = "<html>" + url + "</html>";
    when(crawler.fetchPage(url)).thenAnswer(call -> {
      fetched.add(url);
      return new SchoolWebsiteCrawler.CrawledPage(
          url, url, "Title", "Readable text", html, List.of());
    });
    when(pdfExtractor.findPdfLinksWithText(eq(html), anyString())).thenReturn(List.of(pdfs));
  }

  private static SchoolPdfExtractor.PdfLink link(final String url, final String text) {
    return new SchoolPdfExtractor.PdfLink(url, text);
  }

  /** An address shaped like the CMS's own: a content hash, so the file name says nothing. */
  private static final String OPAQUE_PDF =
      BASE + """
          /_site/data/files/users/parents-and-carers-files/yr-group-pages/\
          938A752E316E2FCD10A5D23C20947DCE.pdf""";

  @Test
  @DisplayName("a year page is stored with its year group, and a whole-school page with none")
  void pagesAreStoredWithTheirYearGroup() {
    when(crawler.listPages()).thenReturn(List.of(SEED, BASE + "/curriculum"));
    serve(SEED, SEED);
    serve(BASE + "/curriculum", BASE + "/curriculum");

    service.ingestWebsite();

    verify(documentWriter).write(eq(SchoolSourceType.WEBSITE_PAGE), eq(SEED), any(), any(),
        any(), eq(List.of("Year 6")), any());
    verify(documentWriter).write(eq(SchoolSourceType.WEBSITE_PAGE), eq(BASE + "/curriculum"),
        any(), any(), any(), eq(List.of()), any());
  }

  @Test
  @DisplayName("a PDF linked from two year pages belongs to both")
  void sharedPdfBelongsToEveryYearLinkingIt() {
    // "Fun ways to learn spellings at home" really is on the Year 3, 4, 5 and 6 pages.
    final String y3 = BASE + "/year-three-home-learning";
    final String y6 = BASE + "/year-six-home-learning";
    when(crawler.listPages()).thenReturn(List.of(y3, y6));
    servePdfs(y3, link(OPAQUE_PDF, "Fun ways to learn spellings at home"));
    servePdfs(y6, link(OPAQUE_PDF, "Fun ways to learn spellings at home"));

    service.ingestWebsite();

    verify(documentWriter).refreshMetadata(eq(SchoolSourceType.PDF), eq(OPAQUE_PDF),
        eq("Title - Fun ways to learn spellings at home (PDF)"), eq(null),
        eq(List.of("Year 3", "Year 6")));
  }

  @Test
  @DisplayName("a PDF also linked from a whole-school page belongs to no year in particular")
  void pdfOnWholeSchoolPageIsUnscoped() {
    // Conservative on purpose: a source tagged only for other years is left out of an answer,
    // so narrowing a whole-school letter to one year would hide it from everyone else.
    final String y3 = BASE + "/year-three-home-learning";
    final String letters = BASE + "/letters-sent-home";
    when(crawler.listPages()).thenReturn(List.of(y3, letters));
    servePdfs(y3, link(OPAQUE_PDF, "Trip letter"));
    servePdfs(letters, link(OPAQUE_PDF, "Trip letter"));

    service.ingestWebsite();

    verify(documentWriter).refreshMetadata(eq(SchoolSourceType.PDF), eq(OPAQUE_PDF), any(),
        eq(null), eq(List.of()));
  }

  @Test
  @DisplayName("an interrupted crawl does not settle any PDF's year groups")
  void interruptedCrawlDoesNotReattribute() {
    // Settling on a partial crawl would narrow a four-year PDF to whichever pages it reached.
    final String y3 = BASE + "/year-three-home-learning";
    final String y6 = BASE + "/year-six-home-learning";
    when(crawler.listPages()).thenReturn(List.of(y3, y6));
    servePdfs(y3, link(OPAQUE_PDF, "Spellings"));
    servePdfs(y6, link(OPAQUE_PDF, "Spellings"));
    when(pdfExtractor.fetch(OPAQUE_PDF)).thenReturn(
        new SchoolPdfExtractor.FetchedPdf("words", Optional.empty()));
    when(crawler.politePause()).thenReturn(false);

    service.ingestWebsite();

    verify(documentWriter, never()).refreshMetadata(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("a crawl stopped by the page cap does not settle any PDF's year groups")
  void pageCapDoesNotReattribute() {
    // Caught by the reviewer on #202: the cap ends the loop without an interrupted pause, so the
    // guard above never fired and a shared PDF was settled on whichever pages fitted.
    final List<String> pages = new ArrayList<>();
    for (int i = 0; i < 401; i++) {
      pages.add(BASE + "/news-" + i);
    }
    when(crawler.listPages()).thenReturn(pages);
    when(crawler.fetchPage(anyString())).thenAnswer(call ->
        new SchoolWebsiteCrawler.CrawledPage(call.getArgument(0), call.getArgument(0), "Title",
            "Readable text", "<html/>", List.of()));
    when(pdfExtractor.findPdfLinksWithText(anyString(), anyString()))
        .thenReturn(List.of(link(OPAQUE_PDF, "Spellings")));
    when(documentWriter.existingPublishedAt(SchoolSourceType.PDF, OPAQUE_PDF))
        .thenReturn(Optional.of(Instant.parse("2026-01-15T10:39:47Z")));

    service.ingestWebsite();

    verify(crawler, times(400)).fetchPage(anyString());
    verify(documentWriter, never()).refreshMetadata(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("a crawl that reads every page settles PDF year groups")
  void completeCrawlReattributes() {
    // The control for the two tests above: the same PDF, a crawl that finishes.
    when(crawler.listPages()).thenReturn(List.of(SEED));
    servePdfs(SEED, link(OPAQUE_PDF, "Spellings"));
    when(documentWriter.existingPublishedAt(SchoolSourceType.PDF, OPAQUE_PDF))
        .thenReturn(Optional.of(Instant.parse("2026-01-15T10:39:47Z")));

    service.ingestWebsite();

    verify(documentWriter).refreshMetadata(eq(SchoolSourceType.PDF), eq(OPAQUE_PDF), any(),
        eq(null), eq(List.of("Year 6")));
  }

  @Test
  @DisplayName("an opaque PDF is titled with the words the page links it by")
  void pdfTitleUsesTheLinkText() {
    // Every year's home-learning page is titled "Home Learning", so the page title alone gave
    // four different spelling sheets one name.
    final String y4 = BASE + "/year-four-home-learning";
    when(crawler.listPages()).thenReturn(List.of(y4));
    servePdfs(y4, link(OPAQUE_PDF, "Spellings list for Spring 1"));
    when(pdfExtractor.fetch(OPAQUE_PDF)).thenReturn(
        new SchoolPdfExtractor.FetchedPdf("words", Optional.empty()));

    service.ingestWebsite();

    verify(documentWriter).write(eq(SchoolSourceType.PDF), eq(OPAQUE_PDF),
        eq("Title - Spellings list for Spring 1 (PDF)"), any(), any(),
        eq(List.of("Year 4")), any());
  }

  @Test
  @DisplayName("link text that names nothing is left out of the title")
  void genericLinkTextIsNeverTheTitle() {
    when(crawler.listPages()).thenReturn(List.of(SEED));
    servePdfs(SEED, link(OPAQUE_PDF, "Click here"));
    when(pdfExtractor.fetch(OPAQUE_PDF)).thenReturn(
        new SchoolPdfExtractor.FetchedPdf("words", Optional.empty()));

    service.ingestWebsite();

    verify(documentWriter).write(eq(SchoolSourceType.PDF), eq(OPAQUE_PDF), eq("Title (PDF)"),
        any(), any(), any(), any());
  }

  @Test
  @DisplayName("an undated PDF is dated by the school server, not by the crawl")
  void newPdfIsDatedByLastModified() {
    // The Year 6 sheet was uploaded on 15 January and reached the assistant as "published 10
    // September 2026", the day the crawl first read it.
    final Instant uploaded = Instant.parse("2026-01-15T10:39:47Z");
    when(crawler.listPages()).thenReturn(List.of(SEED));
    servePdfs(SEED, link(OPAQUE_PDF, "Spellings"));
    when(pdfExtractor.fetch(OPAQUE_PDF)).thenReturn(
        new SchoolPdfExtractor.FetchedPdf("caution, injection", Optional.of(uploaded)));

    service.ingestWebsite();

    verify(documentWriter).write(eq(SchoolSourceType.PDF), eq(OPAQUE_PDF), any(), any(),
        eq(uploaded), any(), any());
  }

  @Test
  @DisplayName("with no date anywhere a PDF is stamped to the second, so it is not re-dated")
  void undatablePdfIsStampedToTheSecond() {
    when(crawler.listPages()).thenReturn(List.of(SEED));
    servePdfs(SEED, link(OPAQUE_PDF, "Spellings"));
    when(pdfExtractor.fetch(OPAQUE_PDF)).thenReturn(
        new SchoolPdfExtractor.FetchedPdf("caution, injection", Optional.empty()));
    final org.mockito.ArgumentCaptor<Instant> date =
        org.mockito.ArgumentCaptor.forClass(Instant.class);

    service.ingestWebsite();

    verify(documentWriter).write(eq(SchoolSourceType.PDF), eq(OPAQUE_PDF), any(), any(),
        date.capture(), any(), any());
    assertThat(date.getValue().getNano()).isZero();
  }

  @Test
  @DisplayName("a stored PDF still carrying a crawl-time stamp is re-dated once from the server")
  void storedCrawlStampIsRedated() {
    final Instant uploaded = Instant.parse("2026-01-15T10:39:47Z");
    when(crawler.listPages()).thenReturn(List.of(SEED));
    servePdfs(SEED, link(OPAQUE_PDF, "Spellings"));
    when(documentWriter.existingPublishedAt(SchoolSourceType.PDF, OPAQUE_PDF))
        .thenReturn(Optional.of(Instant.parse("2026-09-10T12:38:45.121Z")));
    when(pdfExtractor.lastModified(OPAQUE_PDF)).thenReturn(Optional.of(uploaded));

    service.ingestWebsite();

    verify(documentWriter).refreshMetadata(
        SchoolSourceType.PDF, OPAQUE_PDF, null, uploaded, null);
    verify(pdfExtractor, never()).fetch(OPAQUE_PDF);
  }

  @Test
  @DisplayName("a change to only the metadata re-indexes the page without re-reading its dates")
  void metadataOnlyChangeReindexesWithoutExtracting() {
    when(crawler.listPages()).thenReturn(List.of(SEED));
    serve(SEED, SEED);
    when(documentWriter.write(any(), anyString(), any(), any(), any(), any(), any()))
        .thenAnswer(call -> new SchoolDocumentWriter.WriteResult(
            document(call.getArgument(1)), false, true));

    service.ingestWebsite();

    verify(vectorStore).deleteForDocument("id-" + SEED);
    verify(eventExtractor, never()).extract(any());
  }

  @Test
  @DisplayName("a link on a seed page is crawled")
  void seedLinksAreFollowed() {
    when(crawler.listPages()).thenReturn(List.of(SEED));
    serve(SEED, SEED, HOME_LEARNING);
    serve(HOME_LEARNING, HOME_LEARNING);

    service.ingestWebsite();

    assertThat(fetched).containsExactly(SEED, HOME_LEARNING);
    verify(documentWriter).write(
        eq(SchoolSourceType.WEBSITE_PAGE), eq(HOME_LEARNING), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("a link on a SITEMAP page is not followed — one hop, from the seeds only")
  void sitemapLinksAreNotFollowed() {
    // The deliberate bound on discovery. The sitemap is the school's own 218-page statement of
    // what its site contains; following links from all of it would mostly rediscover those 218
    // plus the /school-news/ and /photo-gallery/ long tail, at ten seconds a page. The gap
    // worth closing is the year-group subtree, which the sitemap omits entirely.
    when(crawler.listPages()).thenReturn(List.of(BASE + "/curriculum", SEED));
    serve(BASE + "/curriculum", BASE + "/curriculum", BASE + "/curriculum/subjects/science");
    serve(SEED, SEED);

    service.ingestWebsite();

    assertThat(fetched).containsExactly(BASE + "/curriculum", SEED);
    assertThat(fetched).doesNotContain(BASE + "/curriculum/subjects/science");
  }

  @Test
  @DisplayName("a link to another domain is never followed")
  void externalLinksAreNeverFollowed() {
    // /year-six-home-learning really does link to four external research sites for the
    // children's Ancient Greece topic.
    when(crawler.listPages()).thenReturn(List.of(SEED));
    serve(SEED, SEED,
        "https://www.natgeokids.com/uk/discover/history/greece/",
        "http://www.primaryhomeworkhelp.co.uk/Greece.html",
        HOME_LEARNING);
    serve(HOME_LEARNING, HOME_LEARNING);

    service.ingestWebsite();

    assertThat(fetched).containsExactly(SEED, HOME_LEARNING);
  }

  @Test
  @DisplayName("two addresses for one page produce one document, keyed on the canonical")
  void canonicalCollapsesDuplicateAddresses() {
    // The CMS serves every page twice, and the only link from /year-six to home learning is
    // the ugly form. Without canonicalisation the same text is stored and embedded under two
    // ids, which surfaces as duplicate search results rather than as an error.
    final String ugly = BASE + "/page/?title=Home+Learning&pid=158";
    when(crawler.listPages()).thenReturn(List.of(SEED, HOME_LEARNING));
    serve(SEED, SEED, ugly);
    serve(HOME_LEARNING, HOME_LEARNING);
    serve(ugly, HOME_LEARNING);

    service.ingestWebsite();

    // The ugly form is still fetched — the canonical is only knowable after fetching it — but
    // it is not written a second time.
    assertThat(fetched).contains(ugly);
    verify(documentWriter, never()).write(any(), eq(ugly), any(), any(), any(), any(), any());
    verify(documentWriter).write(
        eq(SchoolSourceType.WEBSITE_PAGE), eq(HOME_LEARNING), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("a discovered page is stored under its canonical URL, not the one followed")
  void discoveredPagesAreStoredUnderTheirCanonical() {
    final String ugly = BASE + "/page/?title=Home+Learning&pid=158";
    when(crawler.listPages()).thenReturn(List.of(SEED));
    serve(SEED, SEED, ugly);
    serve(ugly, HOME_LEARNING);

    service.ingestWebsite();

    verify(documentWriter).write(
        eq(SchoolSourceType.WEBSITE_PAGE), eq(HOME_LEARNING), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("PDFs are re-checked even when the page's text has not changed")
  void pdfsAreScannedOnAnUnchangedPage() {
    // The weekly spelling sheet, and the reason this sits outside the changed() branch.
    // contentHash is computed over the extracted TEXT, so a page that swaps which PDF it links
    // to without changing a word reports UNCHANGED. The CMS names uploads by content hash, so
    // a new sheet is a new URL behind link text that still reads "Year 6 Spring 1 spellings".
    // Gated inside changed(), the first sheet of term would be ingested and every later one
    // silently skipped.
    when(crawler.listPages()).thenReturn(List.of(SEED));
    serve(SEED, SEED);
    when(documentWriter.write(any(), anyString(), any(), any(), any(), any(), any()))
        .thenAnswer(call -> new SchoolDocumentWriter.WriteResult(
            document(call.getArgument(1)), false));
    when(pdfExtractor.findPdfLinksWithText(anyString(), anyString()))
        .thenReturn(List.of(new SchoolPdfExtractor.PdfLink(SPELLINGS_PDF, "Spellings")));
    when(pdfExtractor.fetch(SPELLINGS_PDF)).thenReturn(new SchoolPdfExtractor.FetchedPdf(
        "accommodate, conscience, rhythm", Optional.empty()));

    service.ingestWebsite();

    verify(pdfExtractor).fetch(SPELLINGS_PDF);
    verify(documentWriter).write(
        eq(SchoolSourceType.PDF), eq(SPELLINGS_PDF), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("a PDF already stored is not fetched again")
  void alreadyStoredPdfsAreNotRefetched() {
    // What makes scanning on every crawl affordable rather than 133 extra fetches a night,
    // each with its own ten-second politeness pause.
    when(crawler.listPages()).thenReturn(List.of(SEED));
    serve(SEED, SEED);
    when(pdfExtractor.findPdfLinksWithText(anyString(), anyString()))
        .thenReturn(List.of(new SchoolPdfExtractor.PdfLink(SPELLINGS_PDF, "Spellings")));
    when(documentWriter.existingPublishedAt(SchoolSourceType.PDF, SPELLINGS_PDF))
        .thenReturn(Optional.of(Instant.parse("2026-01-15T10:39:47Z")));

    service.ingestWebsite();

    verify(pdfExtractor, never()).fetch(SPELLINGS_PDF);
    verify(pdfExtractor, never()).lastModified(SPELLINGS_PDF);
  }

  @Test
  @DisplayName("a link cycle terminates")
  void linkCyclesTerminate() {
    when(crawler.listPages()).thenReturn(List.of(SEED));
    serve(SEED, SEED, HOME_LEARNING);
    // The discovered page links back. It is not a seed, so its links are not followed at all —
    // but the visited set would stop this regardless.
    serve(HOME_LEARNING, HOME_LEARNING, SEED);

    service.ingestWebsite();

    assertThat(fetched).containsExactly(SEED, HOME_LEARNING);
  }

  @Test
  @DisplayName("the crawl delay is honoured before every fetch, including skipped pages")
  void politenessPauseCoversSkippedPages() {
    // robots.txt asks for ten seconds. Three paths in the loop `continue` — a page that could
    // not be read, a blank one, and one that turns out to be a second address for a page
    // already crawled — and with the pause at the bottom of the body a run of them went out
    // back to back. Discovery makes such runs more likely, not less.
    when(crawler.listPages()).thenReturn(List.of(SEED, BASE + "/gone", HOME_LEARNING));
    serve(SEED, SEED);
    when(crawler.fetchPage(BASE + "/gone")).thenReturn(null);
    serve(HOME_LEARNING, HOME_LEARNING);

    service.ingestWebsite();

    // One pause between each of the three fetches, and none before the first.
    verify(crawler, times(2)).politePause();
  }

  @Test
  @DisplayName("an interrupted pause stops the crawl")
  void interruptedPauseStopsTheCrawl() {
    when(crawler.listPages()).thenReturn(List.of(SEED, HOME_LEARNING));
    serve(SEED, SEED);
    serve(HOME_LEARNING, HOME_LEARNING);
    when(crawler.politePause()).thenReturn(false);

    service.ingestWebsite();

    assertThat(fetched).containsExactly(SEED);
  }
}
