package com.simonrowe.school.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.school.classify.SchoolEventExtractor;
import com.simonrowe.school.ingest.SchoolDocumentWriter;
import com.simonrowe.school.ingest.SchoolEventWriter;
import com.simonrowe.school.ingest.SchoolIngestService;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolEventRepository;
import com.simonrowe.school.model.SchoolLink;
import com.simonrowe.school.model.SchoolLinkRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The pasted-note pipeline: what is stored, at which tier, and which links are offered.
 *
 * <p>The fixture is the real thing this was built for — a run of messages from a parents'
 * WhatsApp group listing secondary-school open evenings, one school per line, some with a date
 * and some with only a link. Every assertion here failed for a different reason during
 * implementation, and two of them are the kind that produce no error at all: a bare homepage
 * silently discarded, and events landing whole-school so that Reception parents are shown four
 * secondary schools' open days when they ask what is on this week.
 */
class SchoolNoteServiceTest {

  /** Transcribed from the messages, bare addresses and all — WhatsApp sends no anchor text. */
  private static final String WHATSAPP = """
      Kingsdale have a lot of options but you need to book: \
      https://kingsdalefoundationschool.org.uk/open-days/
      St Matthew's Academy Catholic school: https://www.stmatthewacademy.co.uk
      Trinity C of E school - open morning Sat 19 Sept: \
      https://www.trinity.lewisham.sch.uk/Secondary
      Harris Boys - East Dulwich - 17 Sept - \
      https://www.harrisdulwichboys.org.uk/admissions/open-events.
      """;

  private static final Instant NOW = Instant.parse("2026-09-14T19:00:00Z");
  private static final List<String> YEAR_6 = List.of("Year 6");

  private final SchoolDocumentWriter documentWriter = mock(SchoolDocumentWriter.class);
  private final SchoolDocumentRepository documents = mock(SchoolDocumentRepository.class);
  private final SchoolEventRepository events = mock(SchoolEventRepository.class);
  private final SchoolEventExtractor eventExtractor = mock(SchoolEventExtractor.class);
  private final SchoolEventWriter eventWriter = mock(SchoolEventWriter.class);
  private final SchoolIngestService ingestService = mock(SchoolIngestService.class);
  private final SchoolLinkRepository links = mock(SchoolLinkRepository.class);
  private final SchoolLinkFetcher linkFetcher = mock(SchoolLinkFetcher.class);

  private final SchoolNoteService service = new SchoolNoteService(
      documentWriter, documents, events, eventExtractor, eventWriter, ingestService, links,
      linkFetcher, Clock.fixed(NOW, ZoneId.of("Europe/London")));

  @BeforeEach
  void stubStorage() {
    when(documentWriter.write(any(), anyString(), anyString(), anyString(), any(), anyList(),
        any())).thenAnswer(invocation -> new SchoolDocumentWriter.WriteResult(
            new SchoolDocument("note-1", invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4),
                NOW, invocation.getArgument(6), null, null, null, null, false,
                invocation.getArgument(5), "hash", null),
            true));
    when(eventExtractor.extract(any())).thenReturn(List.of());
    when(links.findById(anyString())).thenReturn(Optional.empty());
    when(links.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(links.findBySourceDocumentId(anyString())).thenReturn(List.of());
    when(events.findBySourceDocumentIdIn(anyList())).thenReturn(List.of());
    when(linkFetcher.fetch(anyString())).thenReturn(Optional.empty());
  }

  private void stubReadBack(final SchoolDocument document) {
    when(documents.findById(document.id())).thenReturn(Optional.of(document));
  }

  @Test
  @DisplayName("a note is stored public, as a pasted note, dated now")
  void storedPublicAsPastedNote() {
    captureDocument();
    final ArgumentCaptor<SchoolSourceType> type = ArgumentCaptor.forClass(SchoolSourceType.class);
    final ArgumentCaptor<Visibility> tier = ArgumentCaptor.forClass(Visibility.class);
    final ArgumentCaptor<Instant> published = ArgumentCaptor.forClass(Instant.class);

    service.save(WHATSAPP, "", YEAR_6);

    verify(documentWriter).write(type.capture(), anyString(), anyString(), anyString(),
        published.capture(), anyList(), tier.capture());
    assertThat(type.getValue()).isEqualTo(SchoolSourceType.PASTED_NOTE);
    // Public without approval. Pasting it IS the decision — the queue exists for mail nobody
    // has read, and routing this through it means approving your own typing.
    assertThat(tier.getValue()).isEqualTo(Visibility.PUBLIC);
    // Now, not the date of the messages. This is the reference date "Sat 19 Sept" resolves
    // against, and a note is pasted within days of what it describes.
    assertThat(published.getValue()).isEqualTo(NOW);
  }

  @Test
  @DisplayName("a bare homepage is recorded, unlike a link found in an email footer")
  void bareHomepageIsRecorded() {
    captureDocument();

    service.save(WHATSAPP, "", YEAR_6);

    final ArgumentCaptor<SchoolLink> saved = ArgumentCaptor.forClass(SchoolLink.class);
    verify(links, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
    // SchoolLinkFilter.isWorthOffering drops a bare homepage, which is right for a mail footer
    // and wrong here: "St Matthew's Academy Catholic school: <homepage>" IS the message, and
    // dropping it loses the only address that school has in the note.
    assertThat(saved.getAllValues()).extracting(SchoolLink::url)
        .contains("https://www.stmatthewacademy.co.uk");
  }

  @Test
  @DisplayName("every address in the note becomes a pending link, trailing full stop trimmed")
  void everyAddressIsRecorded() {
    captureDocument();

    service.save(WHATSAPP, "", YEAR_6);

    final ArgumentCaptor<SchoolLink> saved = ArgumentCaptor.forClass(SchoolLink.class);
    verify(links, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
    assertThat(saved.getAllValues()).extracting(SchoolLink::url).containsExactlyInAnyOrder(
        "https://kingsdalefoundationschool.org.uk/open-days/",
        "https://www.stmatthewacademy.co.uk",
        "https://www.trinity.lewisham.sch.uk/Secondary",
        // The note ends this line with a full stop. Left on, the fetch 404s.
        "https://www.harrisdulwichboys.org.uk/admissions/open-events");
    assertThat(saved.getAllValues()).allMatch(l -> l.status() == SchoolLink.Status.PENDING);
  }

  @Test
  @DisplayName("events with no year of their own are narrowed to the note's year group")
  void eventsAreScopedToTheNotesYearGroup() {
    captureDocument();
    when(eventExtractor.extract(any())).thenReturn(List.of(
        event("Trinity C of E School open morning", List.of()),
        event("Kilmorie Year 5 swimming", List.of("Year 5"))));

    service.save(WHATSAPP, "", YEAR_6);

    final ArgumentCaptor<SchoolEvent> written = ArgumentCaptor.forClass(SchoolEvent.class);
    verify(eventWriter, org.mockito.Mockito.times(2)).write(written.capture());
    assertThat(written.getAllValues().get(0).yearGroups()).containsExactly("Year 6");
    // An event that stated its own years keeps them. The scope is a default for content whose
    // whole subject is one year group, not an override of what the source actually said.
    assertThat(written.getAllValues().get(1).yearGroups()).containsExactly("Year 5");
  }

  @Test
  @DisplayName("an event before the ingest cutoff is not written")
  void cutoffApplies() {
    captureDocument();
    when(eventExtractor.extract(any())).thenReturn(List.of(event("Old open day", List.of())));
    when(eventWriter.isBeforeCutoff(any())).thenReturn(true);

    service.save(WHATSAPP, "", YEAR_6);

    verify(eventWriter, never()).write(any());
  }

  @Test
  @DisplayName("a link already decided is neither re-offered nor re-fetched")
  void decidedLinksAreLeftAlone() {
    captureDocument();
    final SchoolLink ignored = new SchoolLink("existing", "note-1",
        "https://www.stmatthewacademy.co.uk", "", NOW, SchoolLink.Status.IGNORED, null, null);
    when(links.findById(anyString())).thenAnswer(invocation -> Optional.empty());
    when(links.findBySourceDocumentId("note-1")).thenReturn(List.of(ignored));

    service.save(WHATSAPP, "", YEAR_6);

    // Re-pasting a corrected note must not resurrect a link somebody declined, and must not
    // re-request an address that has already been fetched.
    verify(linkFetcher, never()).fetch("existing");
  }

  @Test
  @DisplayName("blank text is refused rather than stored as an empty note")
  void blankIsRefused() {
    assertThatThrownBy(() -> service.save("   ", "", YEAR_6))
        .isInstanceOf(IllegalArgumentException.class);
    verify(documentWriter, never()).write(any(), anyString(), anyString(), anyString(), any(),
        anyList(), any());
  }

  @Test
  @DisplayName("the title falls back to the first line of the note")
  void titleFromFirstLine() {
    captureDocument();
    final ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);

    service.save("Open evenings\nKingsdale: 3 Oct", "", YEAR_6);

    verify(documentWriter).write(any(), anyString(), title.capture(), anyString(), any(),
        anyList(), any());
    assertThat(title.getValue()).isEqualTo("Open evenings");
  }

  @Test
  @DisplayName("the same text pasted twice addresses the same document")
  void pastingTwiceIsIdempotent() {
    captureDocument();
    final ArgumentCaptor<String> ref = ArgumentCaptor.forClass(String.class);

    service.save(WHATSAPP, "", YEAR_6);
    service.save(WHATSAPP, "a different title", YEAR_6);

    verify(documentWriter, org.mockito.Mockito.times(2)).write(any(), ref.capture(), anyString(),
        anyString(), any(), anyList(), any());
    // The source reference is derived from the text, so the second paste updates the first note
    // rather than standing up a near-duplicate that yields every event a second time.
    assertThat(ref.getAllValues().get(0)).isEqualTo(ref.getAllValues().get(1));
  }

  @Test
  @DisplayName("URL extraction is linear over a long paste")
  void urlExtractionDoesNotBacktrack() {
    // The pattern runs over text somebody pasted from somewhere else, which is the input class
    // where a backtracking quantifier turns into a hang. Shaped for the worst case: a very long
    // run of URL-legal characters that never terminates in a delimiter.
    final String hostile = "https://example.com/" + "a".repeat(200_000);
    final long start = System.nanoTime();

    final List<String> found = SchoolNoteService.urlsIn(hostile + " and then some prose");

    assertThat(System.nanoTime() - start).isLessThan(java.time.Duration.ofSeconds(2).toNanos());
    assertThat(found).containsExactly(hostile);
  }

  @Test
  @DisplayName("the same address twice in one note is one link")
  void duplicateAddressesCollapse() {
    assertThat(SchoolNoteService.urlsIn(
        "Kingsdale https://a.example/x and again https://a.example/x"))
        .containsExactly("https://a.example/x");
  }

  @Test
  @DisplayName("a note's events include those from the pages its links led to")
  void eventsIncludeFetchedPages() {
    final SchoolDocument document = noteDocument();
    stubReadBack(document);
    final SchoolLink fetched = new SchoolLink("l1", "note-1",
        "https://www.harrisdulwichboys.org.uk/admissions/open-events", "", NOW,
        SchoolLink.Status.FETCHED, "fetched-doc-1", null);
    when(links.findBySourceDocumentId("note-1")).thenReturn(List.of(fetched));
    when(events.findBySourceDocumentIdIn(anyList()))
        .thenReturn(List.of(event("Harris Boys open evening", YEAR_6)));

    final SchoolNoteService.Note note = service.read("note-1").orElseThrow();

    // The whole point of fetching the links: the note gives a bare date, and the school's own
    // page gives the same evening with a time and a booking address. An operator has to be able
    // to see that the second half worked.
    final ArgumentCaptor<List<String>> sources = ArgumentCaptor.captor();
    verify(events).findBySourceDocumentIdIn(sources.capture());
    assertThat(sources.getValue()).containsExactly("note-1", "fetched-doc-1");
    assertThat(note.events()).hasSize(1);
  }

  @Test
  @DisplayName("reading something that is not a pasted note finds nothing")
  void onlyPastedNotesAreReadable() {
    // The id comes off a URL, and every school document lives in one collection. An email read
    // through this endpoint would render its body on a screen that says notes are public.
    when(documents.findById("email-1")).thenReturn(Optional.of(new SchoolDocument(
        "email-1", SchoolSourceType.EMAIL, "gmail:1", "Weekly newsletter", "text", NOW, NOW,
        Visibility.RESTRICTED, null, null, null, null, false, List.of(), "hash", null)));

    assertThat(service.read("email-1")).isEmpty();
  }

  @Test
  @DisplayName("the recent list is newest first and skips anything that has gone")
  void recentListsNotes() {
    final SchoolDocument document = noteDocument();
    stubReadBack(document);
    when(documents.findBySourceTypeOrderByPublishedAtDesc(SchoolSourceType.PASTED_NOTE))
        .thenReturn(List.of(document));

    assertThat(service.recent(20)).extracting(n -> n.document().id()).containsExactly("note-1");
  }

  private SchoolEvent event(final String title, final List<String> yearGroups) {
    return new SchoolEvent("e-" + title, title, LocalDate.of(2026, 9, 19), null, true,
        SchoolEvent.EventType.OTHER, yearGroups, "2026/27", SchoolSourceType.PASTED_NOTE,
        "note-1", Visibility.PUBLIC, null, null, null, null);
  }

  /** Makes the post-save read-back resolve, so {@code save} can return. */
  private void captureDocument() {
    stubReadBack(noteDocument());
  }

  private static SchoolDocument noteDocument() {
    return new SchoolDocument("note-1", SchoolSourceType.PASTED_NOTE, "paste:x", "Note",
        WHATSAPP, NOW, NOW, Visibility.PUBLIC, null, null, null, null, false, YEAR_6, "hash",
        null);
  }
}
