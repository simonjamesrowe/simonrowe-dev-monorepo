package com.simonrowe.migration.changeunits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.school.ingest.SchoolIds;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import com.simonrowe.school.retrieval.SchoolVectorStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Exercises the attachment re-key directly, because Mongock is disabled in tests.
 *
 * <p>The scenario is the one production actually produced: two documents for the same PDF on
 * the same email, keyed on two different Gmail attachment ids, one of them approved and the
 * other a restricted duplicate minted by a later sync.
 */
class V041RekeyGmailAttachmentDocumentsTest extends AbstractIntegrationTest {

  private static final String DOCUMENTS = "school_documents";
  private static final String EVENTS = "school_events";
  private static final String MESSAGE_ID = "199abc";
  private static final String FILENAME = "Homework books Sept 26.pdf";
  private static final String CANONICAL_REF = "gmail:" + MESSAGE_ID + ":" + FILENAME;
  private static final String CANONICAL_ID =
      SchoolIds.documentId(SchoolSourceType.PDF, CANONICAL_REF);
  private static final String BODY = "Year 6 homework is set every Thursday.";

  @Autowired
  private MongoTemplate mongoTemplate;

  private SchoolVectorStore vectorStore;
  private final V041RekeyGmailAttachmentDocuments changeUnit =
      new V041RekeyGmailAttachmentDocuments();

  @BeforeEach
  @AfterEach
  void dropSchoolCollections() {
    mongoTemplate.getCollection(DOCUMENTS).drop();
    mongoTemplate.getCollection(EVENTS).drop();
  }

  @BeforeEach
  void stubVectorStore() {
    vectorStore = mock(SchoolVectorStore.class);
    when(vectorStore.isEnabled()).thenReturn(true);
  }

  @Test
  @DisplayName("duplicates collapse onto the filename key and keep the human approval")
  void collapsesDuplicatesAndKeepsTheApproval() {
    final SchoolDocument approved = attachment("att-old", Visibility.PUBLIC,
        Instant.parse("2026-09-09T20:49:00Z"), Instant.parse("2026-09-09T21:00:00Z"));
    final SchoolDocument duplicate = attachment("att-new", Visibility.RESTRICTED,
        Instant.parse("2026-09-10T06:06:00Z"), null);
    mongoTemplate.save(approved, DOCUMENTS);
    mongoTemplate.save(duplicate, DOCUMENTS);

    changeUnit.execution(mongoTemplate, vectorStore);

    final List<SchoolDocument> remaining =
        mongoTemplate.findAll(SchoolDocument.class, DOCUMENTS);
    assertThat(remaining).singleElement().satisfies(document -> {
      assertThat(document.id()).isEqualTo(CANONICAL_ID);
      assertThat(document.sourceRef()).isEqualTo(CANONICAL_REF);
      // The approval is the whole point: a "keep the newest row" rule would have kept the
      // restricted duplicate and silently un-approved a letter a human had published.
      assertThat(document.visibility()).isEqualTo(Visibility.PUBLIC);
      assertThat(document.approvedAt()).isEqualTo(Instant.parse("2026-09-09T21:00:00Z"));
      assertThat(document.body()).isEqualTo(BODY);
      // Nulled so the next sync treats it as changed and re-embeds it. Left intact, the
      // document would sit in the index with no chunks and nothing would report it.
      assertThat(document.contentHash()).isNull();
    });

    verify(vectorStore).deleteForDocument(idFor("att-old"));
    verify(vectorStore).deleteForDocument(idFor("att-new"));
  }

  @Test
  @DisplayName("events extracted from a removed duplicate are repointed, not orphaned")
  void repointsEventsAtTheSurvivor() {
    mongoTemplate.save(attachment("att-old", Visibility.PUBLIC,
        Instant.parse("2026-09-09T20:49:00Z"), Instant.parse("2026-09-09T21:00:00Z")), DOCUMENTS);
    mongoTemplate.save(attachment("att-new", Visibility.RESTRICTED,
        Instant.parse("2026-09-10T06:06:00Z"), null), DOCUMENTS);
    mongoTemplate.getCollection(EVENTS).insertOne(new org.bson.Document()
        .append("_id", "event-1")
        .append("title", "Homework books deadline")
        .append("sourceDocumentId", idFor("att-new")));

    changeUnit.execution(mongoTemplate, vectorStore);

    final org.bson.Document event = mongoTemplate.getCollection(EVENTS)
        .find(new org.bson.Document("_id", "event-1")).first();
    assertThat(event).isNotNull();
    assertThat(event.getString("sourceDocumentId")).isEqualTo(CANONICAL_ID);
  }

  @Test
  @DisplayName("an already-migrated document is left completely alone")
  void leavesMigratedDocumentsAlone() {
    final SchoolDocument migrated = new SchoolDocument(
        CANONICAL_ID, SchoolSourceType.PDF, CANONICAL_REF, FILENAME, BODY,
        Instant.parse("2026-09-08T09:00:00Z"), Instant.parse("2026-09-08T09:05:00Z"),
        Visibility.PUBLIC, Visibility.PUBLIC, "approved", "simon",
        Instant.parse("2026-09-08T10:00:00Z"), false, List.of(), "live-hash", null);
    mongoTemplate.save(migrated, DOCUMENTS);

    changeUnit.execution(mongoTemplate, vectorStore);

    // Re-running must not null a live hash: that would force a pointless paid re-embed of
    // every attachment in the corpus.
    assertThat(mongoTemplate.findById(CANONICAL_ID, SchoolDocument.class, DOCUMENTS))
        .isNotNull()
        .extracting(SchoolDocument::contentHash).isEqualTo("live-hash");
    verify(vectorStore, never()).deleteForDocument(anyString());
  }

  @Test
  @DisplayName("website PDFs are untouched")
  void leavesWebsitePdfsAlone() {
    final String ref = "https://www.kilmorieschool.co.uk/attachments/download.asp?file=819";
    final SchoolDocument websitePdf = new SchoolDocument(
        SchoolIds.documentId(SchoolSourceType.PDF, ref), SchoolSourceType.PDF, ref,
        "Term Dates (PDF)", BODY, Instant.parse("2026-05-01T00:00:00Z"),
        Instant.parse("2026-09-08T09:05:00Z"), Visibility.PUBLIC, null, null, null, null,
        false, List.of(), "website-hash", null);
    mongoTemplate.save(websitePdf, DOCUMENTS);

    changeUnit.execution(mongoTemplate, vectorStore);

    assertThat(mongoTemplate.findAll(SchoolDocument.class, DOCUMENTS))
        .singleElement()
        .extracting(SchoolDocument::sourceRef).isEqualTo(ref);
  }

  @Test
  @DisplayName("a vector-store outage does not stop the migration, and so does not stop boot")
  void survivesVectorStoreFailure() {
    mongoTemplate.save(attachment("att-old", Visibility.PUBLIC,
        Instant.parse("2026-09-09T20:49:00Z"), Instant.parse("2026-09-09T21:00:00Z")), DOCUMENTS);
    mongoTemplate.save(attachment("att-new", Visibility.RESTRICTED,
        Instant.parse("2026-09-10T06:06:00Z"), null), DOCUMENTS);
    doThrow(new IllegalStateException("elasticsearch is down"))
        .when(vectorStore).deleteForDocument(anyString());

    // Mongock runs at startup and a change-unit exception stops the application. A leftover
    // chunk is a much smaller problem than a backend that will not boot.
    changeUnit.execution(mongoTemplate, vectorStore);

    assertThat(mongoTemplate.findAll(SchoolDocument.class, DOCUMENTS))
        .singleElement()
        .extracting(SchoolDocument::id).isEqualTo(CANONICAL_ID);
  }

  private String idFor(final String attachmentId) {
    return SchoolIds.documentId(
        SchoolSourceType.PDF, "gmail:" + MESSAGE_ID + ":" + attachmentId);
  }

  private SchoolDocument attachment(final String attachmentId, final Visibility visibility,
      final Instant ingestedAt, final Instant approvedAt) {
    return new SchoolDocument(
        idFor(attachmentId), SchoolSourceType.PDF,
        "gmail:" + MESSAGE_ID + ":" + attachmentId, FILENAME, BODY,
        Instant.parse("2026-09-08T09:00:00Z"), ingestedAt, visibility,
        Visibility.RESTRICTED, "classifier proposal",
        approvedAt == null ? null : "simon", approvedAt, false, List.of(), "hash", null);
  }
}
