package com.simonrowe.school.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.school.ingest.SchoolAttachmentStore;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolLink;
import com.simonrowe.school.model.SchoolLinkRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.SchoolSyncStateRepository;
import com.simonrowe.school.model.Visibility;
import com.simonrowe.school.usage.SchoolUsageRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;

/**
 * The three pasted-note endpoints.
 *
 * <p>Constructed directly rather than through MockMvc: these are thin delegations over
 * {@link SchoolNoteService}, and what is worth asserting is the request normalisation, the
 * status codes and the response mapping — none of which needs a Spring context, and all three of
 * which are places a wrong answer is silent rather than loud.
 */
class SchoolAdminNotesControllerTest {

  private static final Instant AT = Instant.parse("2026-09-14T19:00:00Z");

  private final SchoolNoteService notes = mock(SchoolNoteService.class);

  private final SchoolAdminController controller = new SchoolAdminController(
      mock(MongoTemplate.class),
      mock(SchoolSyncStateRepository.class),
      mock(SchoolApprovalService.class),
      mock(SchoolIngestTrigger.class),
      mock(SchoolUsageRepository.class),
      mock(SchoolAttachmentStore.class),
      mock(SchoolLinkRepository.class),
      mock(SchoolLinkFetcher.class),
      notes);

  @Test
  @DisplayName("saving a note returns what was understood, not just an acknowledgement")
  void saveReturnsTheUnderstanding() {
    when(notes.save(anyString(), anyString(), anyList())).thenReturn(note());
    when(notes.read("note-1")).thenReturn(Optional.of(note()));

    final ResponseEntity<SchoolAdminController.NoteResponse> response =
        controller.createNote(new SchoolAdminController.NoteRequest(
            "Trinity - open morning Sat 19 Sept", "", List.of("Year 6")));

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    final SchoolAdminController.NoteResponse body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.id()).isEqualTo("note-1");
    assertThat(body.yearGroups()).containsExactly("Year 6");
    // The events and the link statuses are the whole reason the endpoint is synchronous: an
    // extraction nobody can see is an extraction nobody should trust.
    assertThat(body.events()).hasSize(1);
    assertThat(body.links()).extracting(SchoolAdminController.LinkSummary::status)
        .containsExactly("FETCHED");
    assertThat(body.fetching()).isFalse();
  }

  @Test
  @DisplayName("blank text is a 400, never an empty note")
  void blankTextIsRejected() {
    when(notes.save(anyString(), anyString(), anyList()))
        .thenThrow(new IllegalArgumentException("nothing to save"));

    assertThat(controller.createNote(new SchoolAdminController.NoteRequest("   ", "", List.of()))
        .getStatusCode().value()).isEqualTo(400);
  }

  @Test
  @DisplayName("a missing field in the body cannot NPE the service call")
  void nullsAreNormalised() {
    final SchoolAdminController.NoteRequest request =
        new SchoolAdminController.NoteRequest(null, null, null);

    assertThat(request.text()).isEmpty();
    assertThat(request.title()).isEmpty();
    assertThat(request.yearGroups()).isEmpty();
  }

  @Test
  @DisplayName("an unrecognised year group is dropped rather than passed through")
  void yearGroupsAreSanitised() {
    // The only place a client's list reaches a stored scope. "Year 13" would narrow a note's
    // events to a year group that matches nothing, hiding them from every reader.
    assertThat(new SchoolAdminController.NoteRequest("x", "", List.of("Year 6", "Year 13"))
        .yearGroups()).containsExactly("Year 6");
  }

  @Test
  @DisplayName("polling an unknown note is a 404, not an empty note")
  void unknownNoteIs404() {
    when(notes.read("nope")).thenReturn(Optional.empty());

    assertThat(controller.note("nope").getStatusCode().value()).isEqualTo(404);
  }

  @Test
  @DisplayName("polling a known note returns its current state")
  void knownNoteIsReturned() {
    when(notes.read("note-1")).thenReturn(Optional.of(note()));

    final ResponseEntity<SchoolAdminController.NoteResponse> response = controller.note("note-1");

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().title()).isEqualTo("Secondary open evenings");
  }

  @Test
  @DisplayName("the recent list is capped and never asks for fewer than one")
  void recentIsBounded() {
    when(notes.recent(anyInt())).thenReturn(List.of(note()));
    final ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);

    assertThat(controller.recentNotes(5000)).hasSize(1);
    controller.recentNotes(0);

    verify(notes, org.mockito.Mockito.times(2)).recent(limit.capture());
    assertThat(limit.getAllValues().get(0)).isEqualTo(200);
    assertThat(limit.getAllValues().get(1)).isEqualTo(1);
  }

  private static SchoolNoteService.Note note() {
    final SchoolDocument document = new SchoolDocument("note-1", SchoolSourceType.PASTED_NOTE,
        "paste:abc", "Secondary open evenings", "Trinity - open morning Sat 19 Sept", AT, AT,
        Visibility.PUBLIC, null, null, null, null, false, List.of("Year 6"), "hash", null);
    final SchoolEvent event = new SchoolEvent("e-1", "Trinity C of E School open morning",
        LocalDate.of(2026, 9, 19), null, true, SchoolEvent.EventType.OTHER, List.of("Year 6"),
        "2026/27", SchoolSourceType.PASTED_NOTE, "note-1", Visibility.PUBLIC, null, null, null,
        "https://www.trinity.lewisham.sch.uk/Secondary");
    final SchoolLink link = new SchoolLink("l-1", "note-1",
        "https://www.trinity.lewisham.sch.uk/Secondary",
        "https://www.trinity.lewisham.sch.uk/Secondary", AT, SchoolLink.Status.FETCHED,
        "doc-1", null);
    return new SchoolNoteService.Note(document, List.of(event), List.of(link), false);
  }

}
