package com.simonrowe.school.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Hand edits to a stored document.
 *
 * <p>The fixture is the note that prompted the feature: a Year 3 spelling list pasted before
 * year groups reached the search, titled by its first line and tagged with nothing.
 */
class SchoolDocumentEditorTest {

  private static final Instant PASTED = Instant.parse("2026-09-30T07:00:00Z");
  private static final Instant APPROVED = Instant.parse("2026-09-30T07:05:00Z");
  private static final String SPELLINGS = """
      Autumn 1 Week 3
      Prefixes 'un', 'dis'

      unhappy
      unusual
      disagree
      disobey
      """;

  private final SchoolDocumentRepository documents = mock(SchoolDocumentRepository.class);
  private final SchoolDocumentWriter documentWriter = mock(SchoolDocumentWriter.class);
  private final SchoolEventRepository events = mock(SchoolEventRepository.class);
  private final SchoolEventExtractor eventExtractor = mock(SchoolEventExtractor.class);
  private final SchoolEventWriter eventWriter = mock(SchoolEventWriter.class);
  private final SchoolIngestService ingestService = mock(SchoolIngestService.class);
  private final SchoolNoteService notes = mock(SchoolNoteService.class);

  private final SchoolDocumentEditor editor = new SchoolDocumentEditor(
      documents, documentWriter, events, eventExtractor, eventWriter, ingestService, notes);

  @BeforeEach
  void stubStorage() {
    when(documentWriter.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(eventExtractor.extract(any())).thenReturn(List.of());
    when(events.findBySourceDocumentIdIn(anyList())).thenReturn(List.of());
  }

  private static SchoolDocument note(final List<String> yearGroups) {
    return new SchoolDocument("note-1", SchoolSourceType.PASTED_NOTE, "paste:abc",
        "Prefixes 'un', 'dis' - Autumn 1 Week 3", SPELLINGS, PASTED, PASTED, Visibility.PUBLIC,
        Visibility.RESTRICTED, "a reason", "simon", APPROVED, false, yearGroups,
        SchoolDocumentWriter.contentHash(SPELLINGS), null);
  }

  private static SchoolEvent event(final String id, final List<String> yearGroups) {
    return new SchoolEvent(id, "Spelling check", LocalDate.of(2026, 10, 1),
        LocalDate.of(2026, 10, 1), true, SchoolEvent.EventType.values()[0], yearGroups,
        "2026/27", SchoolSourceType.PASTED_NOTE, "note-1", Visibility.PUBLIC, null, null, null,
        null);
  }

  private SchoolDocument saved() {
    final ArgumentCaptor<SchoolDocument> captor = ArgumentCaptor.forClass(SchoolDocument.class);
    verify(documentWriter).save(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("an edit is made in place and keeps every human decision")
  void editInPlaceKeepsDecisions() {
    when(documents.findById("note-1")).thenReturn(Optional.of(note(List.of())));
    final String body = SPELLINGS + "\nSpelling check on Thursday\n";

    final var result = editor.edit("note-1", "  Year 3 spellings - Autumn 1 Week 3 ",
        body, List.of("Year 3"));

    assertThat(result).isPresent();
    assertThat(result.get().changed()).isTrue();
    final SchoolDocument stored = saved();
    // Same id and sourceRef: a note's id is a hash of its text, so re-pasting forked a second
    // note. An edit must correct the first one.
    assertThat(stored.id()).isEqualTo("note-1");
    assertThat(stored.sourceRef()).isEqualTo("paste:abc");
    assertThat(stored.title()).isEqualTo("Year 3 spellings - Autumn 1 Week 3");
    assertThat(stored.body()).isEqualTo(body);
    assertThat(stored.yearGroups()).containsExactly("Year 3");
    assertThat(stored.contentHash()).isEqualTo(SchoolDocumentWriter.contentHash(body));
    assertThat(stored.publishedAt()).isEqualTo(PASTED);
    assertThat(stored.ingestedAt()).isEqualTo(PASTED);
    assertThat(stored.visibility()).isEqualTo(Visibility.PUBLIC);
    assertThat(stored.proposedVisibility()).isEqualTo(Visibility.RESTRICTED);
    assertThat(stored.proposalReason()).isEqualTo("a reason");
    assertThat(stored.approvedBy()).isEqualTo("simon");
    assertThat(stored.approvedAt()).isEqualTo(APPROVED);
    assertThat(stored.declinedAt()).isNull();
  }

  @Test
  @DisplayName("every edit that changes anything rewrites the chunks")
  void rewritesChunks() {
    when(documents.findById("note-1")).thenReturn(Optional.of(note(List.of())));

    editor.edit("note-1", "Year 3 spellings", SPELLINGS, List.of());

    // A title-only edit still has to reach Elasticsearch: the chunk carries the title, and the
    // assistant cites from the chunk.
    verify(ingestService).embed(any());
  }

  @Test
  @DisplayName("an edit that matches what is stored writes nothing")
  void unchangedWritesNothing() {
    final SchoolDocument stored = note(List.of("Year 3"));
    when(documents.findById("note-1")).thenReturn(Optional.of(stored));

    final var result = editor.edit("note-1", stored.title(), SPELLINGS, List.of("Year 3"));

    assertThat(result.orElseThrow().changed()).isFalse();
    verify(documentWriter, never()).save(any());
    verify(ingestService, never()).embed(any());
    verify(eventExtractor, never()).extract(any());
  }

  @Test
  @DisplayName("ticking a year re-reads the events, replacing the old ones and scoping the new")
  void yearChangeReplacesEvents() {
    when(documents.findById("note-1")).thenReturn(Optional.of(note(List.of())));
    final List<SchoolEvent> previous = List.of(event("old", List.of()));
    when(events.findBySourceDocumentIdIn(List.of("note-1"))).thenReturn(previous);
    when(eventExtractor.extract(any())).thenReturn(List.of(event("new", List.of())));

    final var result = editor.edit("note-1", note(List.of()).title(), SPELLINGS,
        List.of("Year 3"));

    assertThat(result.orElseThrow().eventRefreshFailed()).isFalse();
    verify(events).deleteAll(previous);
    final ArgumentCaptor<SchoolEvent> written = ArgumentCaptor.forClass(SchoolEvent.class);
    verify(eventWriter).write(written.capture());
    assertThat(written.getValue().id()).isEqualTo("new");
    assertThat(written.getValue().yearGroups()).containsExactly("Year 3");
  }

  @Test
  @DisplayName("a failed re-read keeps the previous events and says so")
  void failedExtractionKeepsEvents() {
    when(documents.findById("note-1")).thenReturn(Optional.of(note(List.of())));
    when(eventExtractor.extract(any())).thenThrow(new IllegalStateException("model down"));

    final var result = editor.edit("note-1", "Year 3 spellings", SPELLINGS + "more",
        List.of());

    assertThat(result.orElseThrow().eventRefreshFailed()).isTrue();
    // The edit itself still stands.
    verify(documentWriter).save(any());
    verify(events, never()).deleteAll(anyList());
  }

  @Test
  @DisplayName("a title-only edit spends no model call")
  void titleOnlySkipsExtraction() {
    when(documents.findById("note-1")).thenReturn(Optional.of(note(List.of())));

    editor.edit("note-1", "Year 3 spellings", SPELLINGS, List.of());

    verify(eventExtractor, never()).extract(any());
    verify(events, never()).deleteAll(anyList());
  }

  @Test
  @DisplayName("a note whose text changed follows its links; other edits do not")
  void noteTextChangeFollowsLinks() {
    when(documents.findById("note-1")).thenReturn(Optional.of(note(List.of())));

    editor.edit("note-1", "Year 3 spellings", SPELLINGS + " https://example.org/x", List.of());

    verify(notes).followLinks(any());
  }

  @Test
  @DisplayName("an email edit records no links")
  void emailEditFollowsNoLinks() {
    final SchoolDocument email = new SchoolDocument("mail-1", SchoolSourceType.EMAIL,
        "gmail:1", "Newsletter", "text", PASTED, PASTED, Visibility.RESTRICTED, null, null,
        null, null, false, List.of(), "hash", null);
    when(documents.findById("mail-1")).thenReturn(Optional.of(email));

    editor.edit("mail-1", "Newsletter", "corrected text https://example.org", List.of());

    verify(notes, never()).followLinks(any());
  }

  @Test
  @DisplayName("an unknown id is empty, not an error")
  void unknownIdIsEmpty() {
    when(documents.findById("nope")).thenReturn(Optional.empty());

    assertThat(editor.edit("nope", "t", "b", List.of())).isEmpty();
  }

  @Test
  @DisplayName("a blank title or text is refused before anything is read")
  void blankIsRefused() {
    assertThatThrownBy(() -> editor.edit("note-1", " ", SPELLINGS, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> editor.edit("note-1", "Title", "  \n", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    verify(documents, never()).findById(any());
  }

  @Test
  @DisplayName("an unknown year group is dropped rather than stored")
  void unknownYearDropped() {
    when(documents.findById("note-1")).thenReturn(Optional.of(note(List.of())));

    editor.edit("note-1", "Title", SPELLINGS, List.of("Year 3", "Year 9"));

    assertThat(saved().yearGroups()).containsExactly("Year 3");
  }

  @Test
  @DisplayName("only crawled sources are flagged as having temporary edits")
  void rewrittenByIngest() {
    assertThat(SchoolDocumentEditor.isRewrittenByIngest(note(List.of()))).isFalse();
    assertThat(SchoolDocumentEditor.isRewrittenByIngest(ofType(
        SchoolSourceType.WEBSITE_PAGE, "https://kilmorie.example/year-three"))).isTrue();
    assertThat(SchoolDocumentEditor.isRewrittenByIngest(ofType(
        SchoolSourceType.CALENDAR_FEED, "calendar"))).isTrue();
    // A website PDF's title and year groups are re-derived on every complete crawl...
    assertThat(SchoolDocumentEditor.isRewrittenByIngest(ofType(
        SchoolSourceType.PDF, "https://kilmorie.example/a.pdf"))).isTrue();
    // ...but a PDF attached to an email is never looked at again.
    assertThat(SchoolDocumentEditor.isRewrittenByIngest(ofType(
        SchoolSourceType.PDF, "gmail:1:att"))).isFalse();
    assertThat(SchoolDocumentEditor.isRewrittenByIngest(ofType(
        SchoolSourceType.EMAIL, "gmail:1"))).isFalse();
  }

  private static SchoolDocument ofType(final SchoolSourceType type, final String ref) {
    return new SchoolDocument("d", type, ref, "t", "b", PASTED, PASTED, Visibility.PUBLIC,
        null, null, null, null, false, List.of(), "h", null);
  }
}
