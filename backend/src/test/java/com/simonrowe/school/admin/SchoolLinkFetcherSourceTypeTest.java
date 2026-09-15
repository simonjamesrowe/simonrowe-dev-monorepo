package com.simonrowe.school.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.classify.SchoolEventExtractor;
import com.simonrowe.school.ingest.DocumentDateReader;
import com.simonrowe.school.ingest.SchoolAttachmentStore;
import com.simonrowe.school.ingest.SchoolDocumentWriter;
import com.simonrowe.school.ingest.SchoolEventWriter;
import com.simonrowe.school.ingest.SchoolIngestService;
import com.simonrowe.school.ingest.SchoolLinkFilter;
import com.simonrowe.school.ingest.SchoolPdfExtractor;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolLink;
import com.simonrowe.school.model.SchoolLinkRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Which source type a fetched page lands under, and what it is called.
 *
 * <p>Both answers used to be one thing: every non-PDF fetch became a {@code WEBSITE_PAGE} titled
 * with the link's anchor text. Neither held once notes could be pasted in.
 *
 * <p>The type matters because {@code SchoolQueryService.communicationsBetween} — the tool behind
 * "what did the school send last week" — reads an allowlist of source types that includes
 * {@code WEBSITE_PAGE}. A secondary school's admissions page stored under that type is reported
 * to a parent as something Kilmorie published. That was invisible while every fetched
 * third-party page was restricted; pasted notes are public, and so is everything fetched from
 * them.
 *
 * <p>The title matters because WhatsApp sends no anchor text. Without the page's own
 * {@code <title>} the document is called {@code https://www.harrisdulwichboys.org.uk/…}, every
 * citation built from it reads as that address, and the extractor — which is shown the title —
 * has nothing naming the school for a page headed only "Open Events".
 */
class SchoolLinkFetcherSourceTypeTest {

  private final SchoolEventExtractor eventExtractor = mock(SchoolEventExtractor.class);
  private final SchoolEventWriter eventWriter = mock(SchoolEventWriter.class);

  private final SchoolLinkFetcher fetcher = new SchoolLinkFetcher(
      mock(SchoolLinkRepository.class),
      mock(SchoolDocumentRepository.class),
      mock(SchoolDocumentWriter.class),
      mock(SchoolPdfExtractor.class),
      mock(SchoolAttachmentStore.class),
      mock(SchoolIngestService.class),
      eventExtractor,
      eventWriter,
      mock(DocumentDateReader.class),
      new SchoolLinkFilter(properties()));

  private static final byte[] HTML = "<html><body>Open Events</body></html>"
      .getBytes(StandardCharsets.UTF_8);
  private static final byte[] PDF = "%PDF-1.7 ...".getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("a page on the school's own host is still a website page")
  void schoolHostStaysAsWebsitePage() {
    assertThat(fetcher.sourceTypeFor(HTML, "https://www.kilmorieschool.co.uk/term-dates"))
        .isEqualTo(SchoolSourceType.WEBSITE_PAGE);
  }

  @Test
  @DisplayName("another school's page is an external page, not this school's website")
  void thirdPartyHostIsExternal() {
    assertThat(fetcher.sourceTypeFor(HTML, "https://www.harrisdulwichboys.org.uk/open-events"))
        .isEqualTo(SchoolSourceType.EXTERNAL_PAGE);
  }

  @Test
  @DisplayName("a PDF is a PDF wherever it was fetched from")
  void pdfIsUnaffected() {
    assertThat(fetcher.sourceTypeFor(PDF, "https://www.harrisdulwichboys.org.uk/prospectus.pdf"))
        .isEqualTo(SchoolSourceType.PDF);
    assertThat(fetcher.sourceTypeFor(PDF, "https://www.kilmorieschool.co.uk/menu.pdf"))
        .isEqualTo(SchoolSourceType.PDF);
  }

  @Test
  @DisplayName("a bare address falls back to the page's own title")
  void bareAddressTakesThePageTitle() {
    assertThat(SchoolLinkFetcher.titleFor(
        link("https://www.harrisdulwichboys.org.uk/admissions/open-events"),
        "Open Events | Harris Boys' Academy East Dulwich"))
        .isEqualTo("Open Events | Harris Boys' Academy East Dulwich");
  }

  @Test
  @DisplayName("real anchor text beats the page title")
  void anchorTextWins() {
    final SchoolLink link = new SchoolLink("l1", "d1", "https://example.school/news",
        "Autumn Newsletter - 12 September 2026", Instant.now(), SchoolLink.Status.PENDING,
        null, null);
    // The school's CMS puts the date in the link text and leads the page with navigation, so
    // the anchor is by far the better title when there is one.
    assertThat(SchoolLinkFetcher.titleFor(link, "Kilmorie Primary School"))
        .isEqualTo("Autumn Newsletter - 12 September 2026");
  }

  @Test
  @DisplayName("an untitled page keeps the address rather than being left blank")
  void untitledFallsBackToTheAddress() {
    assertThat(SchoolLinkFetcher.titleFor(link("https://example.school/x"), "  "))
        .isEqualTo("https://example.school/x");
    assertThat(SchoolLinkFetcher.titleFor(link("https://example.school/x"), null))
        .isEqualTo("https://example.school/x");
  }

  @Test
  @DisplayName("a fetched page's events take the document's year groups on EVERY fetch")
  void eventsTakeTheDocumentsYearGroupsOnEveryFetch() {
    final SchoolDocument fetched = external(List.of("Year 6"));
    Mockito.when(eventExtractor.extract(fetched))
        .thenReturn(List.of(event("Harris Boys open evening", List.of())));

    fetcher.writeEventsFor(fetched);

    // The regression this pins: the scope used to be applied by a pass in SchoolNoteService
    // straight after its own call to fetch(), so it only held for links fetched through the
    // note pipeline. fetch() re-extracts and rewrites events on every invocation and is
    // reachable from the admin Fetch button, so retrying a note's failed link re-wrote its
    // events whole-school — putting four secondary schools' open evenings back into every
    // year group's "what is on this week", with no error and no log line.
    final ArgumentCaptor<SchoolEvent> written = ArgumentCaptor.forClass(SchoolEvent.class);
    Mockito.verify(eventWriter).write(written.capture());
    assertThat(written.getValue().yearGroups()).containsExactly("Year 6");
  }

  @Test
  @DisplayName("an event that stated its own year groups keeps them")
  void statedYearGroupsSurvive() {
    final SchoolDocument fetched = external(List.of("Year 6"));
    Mockito.when(eventExtractor.extract(fetched))
        .thenReturn(List.of(event("Years 5 and 6 taster morning", List.of("Year 5", "Year 6"))));

    fetcher.writeEventsFor(fetched);

    final ArgumentCaptor<SchoolEvent> written = ArgumentCaptor.forClass(SchoolEvent.class);
    Mockito.verify(eventWriter).write(written.capture());
    assertThat(written.getValue().yearGroups()).containsExactly("Year 5", "Year 6");
  }

  @Test
  @DisplayName("a document with no scope leaves its events whole-school")
  void unscopedDocumentLeavesEventsWholeSchool() {
    final SchoolDocument fetched = external(List.of());
    Mockito.when(eventExtractor.extract(fetched))
        .thenReturn(List.of(event("Open evening", List.of())));

    fetcher.writeEventsFor(fetched);

    final ArgumentCaptor<SchoolEvent> written = ArgumentCaptor.forClass(SchoolEvent.class);
    Mockito.verify(eventWriter).write(written.capture());
    assertThat(written.getValue().yearGroups()).isEmpty();
  }

  private static SchoolDocument external(final List<String> yearGroups) {
    return new SchoolDocument("doc-1", SchoolSourceType.EXTERNAL_PAGE,
        "https://www.harrisdulwichboys.org.uk/admissions/open-events", "Open Events",
        "Open evening on 17 September.", Instant.now(), Instant.now(), Visibility.PUBLIC,
        null, null, null, null, false, yearGroups, "hash", null);
  }

  private static SchoolEvent event(final String title, final List<String> yearGroups) {
    return new SchoolEvent("e-1", title, LocalDate.of(2026, 9, 17), null, true,
        SchoolEvent.EventType.OTHER, yearGroups, "2026/27", SchoolSourceType.EXTERNAL_PAGE,
        "doc-1", Visibility.PUBLIC, null, null, null, null);
  }

  private SchoolLink link(final String url) {
    return new SchoolLink("l1", "d1", url, url, Instant.now(), SchoolLink.Status.PENDING,
        null, null);
  }

  private static SchoolProperties properties() {
    return new SchoolProperties(true, LocalDate.of(2026, 9, 1), List.of(), List.of(),
        "https://www.kilmorieschool.co.uk/calendar",
        "https://www.kilmorieschool.co.uk",
        "https://www.kilmorieschool.co.uk/our-school/our-staff",
        10, "gpt-5.6-luna", "gpt-5.6-luna", 100000L, "https://simonrowe.dev", List.of());
  }
}
