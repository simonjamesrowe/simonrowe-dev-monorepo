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
  private SchoolIngestService service;
  private final List<String> fetched = new ArrayList<>();

  @BeforeEach
  void setUp() {
    crawler = mock(SchoolWebsiteCrawler.class);
    documentWriter = mock(SchoolDocumentWriter.class);
    pdfExtractor = mock(SchoolPdfExtractor.class);

    final SchoolProperties properties = new SchoolProperties(
        true, null, List.of(), List.of(), null, BASE, null, 0, null, null, null, 0L, null,
        List.of("/year-six"));

    when(crawler.politePause()).thenReturn(true);
    when(crawler.pageUpdateTimes()).thenReturn(Map.of());
    when(pdfExtractor.findPdfLinks(anyString(), anyString())).thenReturn(List.of());
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
        mock(SchoolVectorStore.class),
        mock(TokenTextSplitter.class),
        pdfExtractor,
        mock(SchoolUsageRecorder.class),
        mock(SchoolEventExtractor.class),
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
    when(pdfExtractor.findPdfLinks(anyString(), anyString())).thenReturn(List.of(SPELLINGS_PDF));
    when(pdfExtractor.extractText(SPELLINGS_PDF)).thenReturn("accommodate, conscience, rhythm");

    service.ingestWebsite();

    verify(pdfExtractor).extractText(SPELLINGS_PDF);
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
    when(pdfExtractor.findPdfLinks(anyString(), anyString())).thenReturn(List.of(SPELLINGS_PDF));
    when(documentWriter.existingPublishedAt(SchoolSourceType.PDF, SPELLINGS_PDF))
        .thenReturn(Optional.of(Instant.now()));

    service.ingestWebsite();

    verify(pdfExtractor, never()).extractText(SPELLINGS_PDF);
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
