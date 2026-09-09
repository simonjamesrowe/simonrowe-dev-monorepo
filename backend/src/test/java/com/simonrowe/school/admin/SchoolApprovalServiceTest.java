package com.simonrowe.school.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

class SchoolApprovalServiceTest {

  private SchoolDocumentRepository documents;
  private SchoolEventRepository events;
  private SchoolIngestService ingestService;
  private SchoolApprovalService service;

  @BeforeEach
  void setUp() {
    documents = mock(SchoolDocumentRepository.class);
    events = mock(SchoolEventRepository.class);
    ingestService = mock(SchoolIngestService.class);
    service = new SchoolApprovalService(documents, events, ingestService);
    when(events.findAll()).thenReturn(List.of());
    when(documents.save(any())).thenAnswer(i -> i.getArgument(0));
  }

  private SchoolDocument document(final String body) {
    return new SchoolDocument("doc-1", SchoolSourceType.EMAIL, "msg-1", "Newsletter", body,
        Instant.EPOCH, Instant.EPOCH, Visibility.RESTRICTED, Visibility.PUBLIC,
        "classifier proposed PUBLIC", null, null, false, List.of(), "hash", null);
  }

  @Test
  @DisplayName("approval is the only path to PUBLIC")
  void approvalPromotes() {
    when(documents.findById("doc-1"))
        .thenReturn(Optional.of(document("Half term runs from 26 October.")));

    final Optional<SchoolDocument> result = service.approve("doc-1", "simon");

    assertThat(result).isPresent();
    assertThat(result.get().visibility()).isEqualTo(Visibility.PUBLIC);
    assertThat(result.get().approvedBy()).isEqualTo("simon");
  }

  @Test
  @DisplayName("a document naming a person is approved like any other")
  void namesNoLongerBlockApproval() {
    // Term Time used to re-run a staff-directory heuristic here and refuse anything naming
    // someone it could not place. It was removed on the owner's explicit instruction: it
    // blocked 98 of 99 school broadcasts, could not tell the catering company from a child,
    // and left the queue unusable. A human reading the text is the control now.
    when(documents.findById("doc-1"))
        .thenReturn(Optional.of(document("Well done to Amelia Watts in Year 4")));

    final Optional<SchoolDocument> result = service.approve("doc-1", "simon");

    assertThat(result).isPresent();
    assertThat(result.get().visibility()).isEqualTo(Visibility.PUBLIC);
    assertThat(result.get().nameGateBlocked()).isFalse();
    assertThat(result.get().approvedBy()).isEqualTo("simon");
  }

  @Test
  @DisplayName("approving re-embeds, or the index and Mongo disagree about the tier")
  void approvalReEmbeds() {
    // visibility is chunk metadata and the retrieval filter reads the copy in Elasticsearch.
    // Without a re-embed the document is public in Mongo, restricted in the index, and the
    // approval appears to have done nothing at all.
    when(documents.findById("doc-1")).thenReturn(Optional.of(document("Term dates attached.")));

    service.approve("doc-1", "simon");

    verify(ingestService).embed(any());
  }

  @Test
  @DisplayName("the tier cascades to events extracted from the document")
  void tierCascadesToEvents() {
    final SchoolEvent event = new SchoolEvent("evt-1", "Inset Day", LocalDate.of(2026, 9, 2),
        LocalDate.of(2026, 9, 2), true, SchoolEvent.EventType.INSET, List.of(), "2026/27",
        SchoolSourceType.EMAIL, "doc-1", Visibility.RESTRICTED, null, null, null, null);
    when(events.findAll()).thenReturn(List.of(event));
    when(documents.findById("doc-1"))
        .thenReturn(Optional.of(document("Inset day on 2 September")));

    service.approve("doc-1", "simon");

    final var captor = org.mockito.ArgumentCaptor.forClass(SchoolEvent.class);
    verify(events).save(captor.capture());
    assertThat(captor.getValue().visibility()).isEqualTo(Visibility.PUBLIC);
  }

  @Test
  @DisplayName("revoking removes public access without needing a re-ingest")
  void revokeDemotes() {
    final SchoolDocument approved = document("Term dates attached.")
        .withApproval("simon", Instant.EPOCH, false);
    when(documents.findById("doc-1")).thenReturn(Optional.of(approved));

    final Optional<SchoolDocument> result = service.revoke("doc-1");

    assertThat(result).isPresent();
    assertThat(result.get().visibility()).isEqualTo(Visibility.RESTRICTED);
    verify(ingestService).embed(any());
  }

  @Test
  @DisplayName("declining leaves the document restricted and clears it from the queue")
  void declineClearsTheProposal() {
    when(documents.findById("doc-1")).thenReturn(Optional.of(document("Something private.")));

    final Optional<SchoolDocument> result = service.decline("doc-1");

    assertThat(result).isPresent();
    assertThat(result.get().visibility()).isEqualTo(Visibility.RESTRICTED);
    assertThat(result.get().needsDecision()).isFalse();
  }

  @Test
  @DisplayName("an unknown id changes nothing")
  void unknownIdChangesNothing() {
    when(documents.findById("nope")).thenReturn(Optional.empty());

    assertThat(service.approve("nope", "simon")).isEmpty();
    verify(documents, never()).save(any());
    verify(ingestService, never()).embed(any());
  }
}
