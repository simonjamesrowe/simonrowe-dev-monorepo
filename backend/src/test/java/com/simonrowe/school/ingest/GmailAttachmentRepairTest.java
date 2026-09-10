package com.simonrowe.school.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.admin.SchoolLinkFetcher;
import com.simonrowe.school.classify.SchoolEventExtractor;
import com.simonrowe.school.classify.TierClassifier;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolLinkRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.SchoolSyncStateRepository;
import com.simonrowe.school.model.Visibility;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The attachment store has to be able to repair itself.
 *
 * <p>Its files are the one piece of Term Time state that lives outside Mongo and Elasticsearch,
 * and losing them is silent: the document survives, its chunks survive, and the chunk metadata
 * carries the {@code attachmentUrl}, so the assistant keeps citing a PDF whose bytes are gone
 * and every one of those links 404s. That is what production did — the store had no volume
 * behind it, so each recreate of the backend emptied it.
 *
 * <p>Ingest could not put them back either, because attachments were only visited when the
 * parent email's own text had changed, and an email never changes. These tests pin both halves:
 * a missing file is re-fetched, and a present one costs nothing.
 */
class GmailAttachmentRepairTest {

  private static final String MESSAGE_ID = "msg-1";
  private static final String ATTACHMENT_ID = "att-1";
  private static final String SOURCE_REF = "gmail:" + MESSAGE_ID + ":" + ATTACHMENT_ID;
  private static final String PDF_ID =
      SchoolIds.documentId(SchoolSourceType.PDF, SOURCE_REF);
  private static final byte[] BYTES =
      "%PDF-1.4 letter".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  private GmailClient gmail;
  private SchoolDocumentWriter documentWriter;
  private SchoolPdfExtractor pdfExtractor;
  private SchoolAttachmentStore attachmentStore;
  private SchoolIngestService ingestService;
  private GmailIngestService service;

  @BeforeEach
  void setUp() throws Exception {
    gmail = mock(GmailClient.class);
    documentWriter = mock(SchoolDocumentWriter.class);
    pdfExtractor = mock(SchoolPdfExtractor.class);
    attachmentStore = mock(SchoolAttachmentStore.class);
    ingestService = mock(SchoolIngestService.class);
    final SchoolEventExtractor eventExtractor = mock(SchoolEventExtractor.class);
    final SchoolLinkFilter linkFilter = mock(SchoolLinkFilter.class);

    when(gmail.isConfigured()).thenReturn(true);
    when(gmail.listMessageIds(anyString())).thenReturn(List.of(MESSAGE_ID));
    when(gmail.fetchMessage(MESSAGE_ID)).thenReturn(Optional.of(message()));
    when(gmail.fetchAttachment(MESSAGE_ID, ATTACHMENT_ID)).thenReturn(Optional.of(BYTES));
    when(pdfExtractor.extractTextFromBytes(any())).thenReturn("Year 6 homework books");
    when(eventExtractor.extract(any())).thenReturn(List.of());
    when(documentWriter.write(eq(SchoolSourceType.EMAIL), anyString(), anyString(), anyString(),
        any(), any(), any()))
        // The email is unchanged, which is the ordinary state of an email that arrived last
        // week and is exactly the case that used to skip attachments entirely.
        .thenReturn(new SchoolDocumentWriter.WriteResult(email(), false));
    when(documentWriter.write(eq(SchoolSourceType.PDF), anyString(), anyString(), anyString(),
        any(), any(), any()))
        .thenReturn(new SchoolDocumentWriter.WriteResult(pdf(), false));

    service = new GmailIngestService(
        properties(), gmail, mock(TierClassifier.class), documentWriter,
        mock(SchoolSyncStateRepository.class), ingestService, pdfExtractor, eventExtractor,
        mock(SchoolEventWriter.class), attachmentStore, mock(SchoolLinkRepository.class),
        linkFilter, mock(SchoolLinkFetcher.class));
  }

  @Test
  @DisplayName("a missing attachment file is re-fetched even when the email is unchanged")
  void refetchesMissingFile() throws Exception {
    when(attachmentStore.has(PDF_ID)).thenReturn(false);

    service.sync();

    verify(gmail).fetchAttachment(MESSAGE_ID, ATTACHMENT_ID);
    verify(attachmentStore).store(PDF_ID, BYTES);
    // The text is unchanged, so nothing is re-embedded. Repairing the store must not cost an
    // embedding call per attachment per sync.
    verify(ingestService, never()).embed(any());
  }

  @Test
  @DisplayName("an attachment already on disk costs no download at all")
  void skipsWhenTheFileIsPresent() throws Exception {
    when(attachmentStore.has(PDF_ID)).thenReturn(true);

    service.sync();

    verify(gmail, never()).fetchAttachment(anyString(), anyString());
    verify(pdfExtractor, never()).extractTextFromBytes(any());
    verify(attachmentStore, never()).store(anyString(), any());
  }

  @Test
  @DisplayName("the id the presence check uses is the one the store writes under")
  void presenceCheckUsesTheStoredId() throws Exception {
    when(attachmentStore.has(PDF_ID)).thenReturn(false);

    service.sync();

    // Derived from the source ref without downloading anything; if the two ever diverge the
    // guard above stops matching and every sync re-downloads every attachment.
    assertThat(PDF_ID).isEqualTo(pdf().id());
    verify(attachmentStore).has(PDF_ID);
  }

  private GmailMessage message() {
    return new GmailMessage(
        MESSAGE_ID,
        "Year 6 Homework Books",
        "office@kilmorie.lewisham.sch.uk",
        "Kilmorie Primary School",
        Instant.parse("2026-09-08T09:00:00Z"),
        "Please see the attached letter.",
        List.of(new GmailMessage.Attachment(
            "year-6-homework-books.pdf", ATTACHMENT_ID, "application/pdf", 1024)),
        List.of());
  }

  private SchoolDocument email() {
    return new SchoolDocument(
        SchoolIds.documentId(SchoolSourceType.EMAIL, MESSAGE_ID), SchoolSourceType.EMAIL,
        MESSAGE_ID, "Year 6 Homework Books", "Please see the attached letter.",
        Instant.parse("2026-09-08T09:00:00Z"), Instant.parse("2026-09-08T09:05:00Z"),
        Visibility.PUBLIC, Visibility.PUBLIC, "approved", "simon",
        Instant.parse("2026-09-08T10:00:00Z"), false, List.of(), "hash", null);
  }

  private SchoolDocument pdf() {
    return new SchoolDocument(
        PDF_ID, SchoolSourceType.PDF, SOURCE_REF, "year-6-homework-books.pdf",
        "Year 6 homework books", Instant.parse("2026-09-08T09:00:00Z"),
        Instant.parse("2026-09-08T09:05:00Z"), Visibility.PUBLIC, Visibility.PUBLIC, "approved",
        "simon", Instant.parse("2026-09-08T10:00:00Z"), false, List.of(), "hash", null);
  }

  private SchoolProperties properties() {
    return new SchoolProperties(true, LocalDate.of(2026, 7, 1),
        List.of("kilmorie.lewisham.sch.uk"), List.of(), null, null, null, 0, null, null, 0, null);
  }
}
