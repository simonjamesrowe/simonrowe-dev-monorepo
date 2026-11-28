package com.simonrowe.coparent.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.model.DocumentSection;
import com.simonrowe.coparent.model.DocumentSource;
import com.simonrowe.coparent.model.DueKind;
import com.simonrowe.coparent.model.FamilyDocument;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.FamilyDocumentRepository;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.migration.changeunits.V043CreateCoparentCollections;
import com.simonrowe.migration.changeunits.V069CreateCoparentDocuments;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;

/** The daily reminders go once per threshold, to both active parents, and skip the archived. */
class DocumentReminderServiceIntegrationTest extends AbstractIntegrationTest {

  private static final LocalDate TODAY = LocalDate.of(2027, 3, 1);
  private static final LocalDate DUE = LocalDate.of(2027, 8, 2);

  @Autowired
  @Qualifier("coparentMongoTemplate")
  private MongoTemplate mongoTemplate;

  @Autowired
  private ParentRepository parents;

  @Autowired
  private ChildRepository children;

  @Autowired
  private FamilyDocumentRepository documents;

  private final DocumentMailer mailer = mock(DocumentMailer.class);
  private final ObjectId familyId = new ObjectId();
  private DocumentReminderService service;

  @BeforeEach
  @AfterEach
  void clean() {
    List.of(V043CreateCoparentCollections.PARENTS, V043CreateCoparentCollections.CHILDREN,
            V069CreateCoparentDocuments.DOCUMENTS)
        .forEach(collection -> mongoTemplate.getCollection(collection)
            .deleteMany(new Document()));
    service = new DocumentReminderService(parents, children, mailer,
        new CoparentProperties(true, "coparent", "https://coparents.example", "email",
            "noreply@example.com", true, "legacy",
            new CoparentProperties.Assistant(false, "model")), mongoTemplate,
        java.time.Clock.systemUTC());
  }

  @Test
  void sendsEachThresholdOnceToEveryActiveParent() {
    final Parent alex = parents.save(parent("Alex", "active"));
    final Parent sam = parents.save(parent("Sam", "active"));
    parents.save(parent("Rhian", "invited"));
    final Child leo = children.save(new Child(null, familyId, "Leo Taylor",
        LocalDate.of(2019, 1, 1), null, null, null, null, null, null));
    final FamilyDocument passport = documents.save(document("Passport", List.of(leo.id()),
        DUE, DueKind.EXPIRES, null));

    assertThat(service.sendReminders(TODAY)).isEqualTo(1);
    assertThat(service.sendReminders(TODAY)).as("a re-run sends nothing").isZero();
    assertThat(service.sendReminders(TODAY.plusDays(1))).isZero();

    verify(mailer).reminder(eq(alex), any(), eq(" (Leo)"), eq("in about six months"));
    verify(mailer).reminder(eq(sam), any(), eq(" (Leo)"), eq("in about six months"));
    verify(mailer, times(2)).reminder(any(), any(), any(), any());
    final FamilyDocument after = documents.findById(passport.id()).orElseThrow();
    assertThat(after.remindersSent()).containsExactly("2027-08-02:6m");
    assertThat(after.version()).as("bumped so a stale edit cannot undo it").isEqualTo(2);

    assertThat(service.sendReminders(LocalDate.of(2027, 5, 2))).isEqualTo(1);
    assertThat(documents.findById(passport.id()).orElseThrow().remindersSent())
        .containsExactlyInAnyOrder("2027-08-02:6m", "2027-08-02:3m");
  }

  @Test
  void archivedFarOffAndUndatedDocumentsAreSkipped() {
    parents.save(parent("Alex", "active"));
    documents.save(document("Archived", List.of(), DUE, DueKind.EXPIRES, Instant.now()));
    documents.save(document("Far off", List.of(), DUE.plusYears(5), DueKind.EXPIRES, null));
    documents.save(document("Undated", List.of(), null, null, null));

    assertThat(service.sendReminders(TODAY)).isZero();
    verify(mailer, never()).reminder(any(), any(), any(), any());
  }

  @Test
  void reviewWarnsOneMonthAhead() {
    parents.save(parent("Alex", "active"));
    documents.save(document("Care plan", List.of(), LocalDate.of(2027, 3, 20), DueKind.REVIEW,
        null));

    assertThat(service.sendReminders(TODAY)).isEqualTo(1);
    verify(mailer).reminder(any(), any(), eq(""), eq("in about a month"));
  }

  private Parent parent(final String name, final String status) {
    return new Parent(null, "auth0|" + name, familyId, name, name + "@example.com", "co-parent",
        status, null, null, null, null, null);
  }

  private FamilyDocument document(final String title, final List<ObjectId> childIds,
      final LocalDate dueOn, final DueKind kind, final Instant archivedAt) {
    final Instant now = Instant.now();
    return new FamilyDocument(null, familyId, childIds, DocumentSection.IDENTITY, title,
        LocalDate.of(2017, 8, 2), dueOn, kind, null, List.of(), DocumentSource.upload(),
        archivedAt, null, Set.of(), new ObjectId(), now, now, 1);
  }
}
