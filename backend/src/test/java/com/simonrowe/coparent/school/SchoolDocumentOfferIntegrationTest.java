package com.simonrowe.coparent.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.model.DocumentSchoolOffer;
import com.simonrowe.coparent.model.Family;
import com.simonrowe.coparent.model.FamilyDocument;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.DocumentSchoolOfferRepository;
import com.simonrowe.coparent.persistence.FamilyRepository;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.migration.changeunits.V043CreateCoparentCollections;
import com.simonrowe.migration.changeunits.V069CreateCoparentDocuments;
import com.simonrowe.school.ingest.GmailClient;
import com.simonrowe.school.ingest.GmailMessage;
import com.simonrowe.school.ingest.SchoolAttachmentStore;
import com.simonrowe.school.ingest.SchoolIds;
import com.simonrowe.school.model.AcademicYear;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.FileSystemUtils;

/**
 * "From school" end to end against real MongoDB: the sync offers the right letters to the right
 * children and records the classifier's refusals, and a parent keeps or dismisses each offer
 * once. Nothing is ever offered twice. Every name and letter is invented.
 */
@TestPropertySource(properties = {
    "coparent.enabled=true",
    "coparent.document-path=build/test-offer-documents/",
    "school.attachment-path=build/test-offer-attachments/",
    "coparent.school-calendar.mailbox-family-id=" + SchoolDocumentOfferIntegrationTest.MAILBOX
})
class SchoolDocumentOfferIntegrationTest extends AbstractIntegrationTest {

  static final String MAILBOX = "65a0000000000000000000a1";
  private static final String OFFERS = "/api/coparent/families/{familyId}/documents/school-offers";
  private static final ZoneId LONDON = ZoneId.of("Europe/London");
  private static final byte[] PDF =
      "%PDF-1.7\n% a synthetic letter\n".getBytes(StandardCharsets.US_ASCII);
  private static final List<String> COLLECTIONS = List.of(
      V043CreateCoparentCollections.FAMILIES,
      V043CreateCoparentCollections.PARENTS,
      V043CreateCoparentCollections.CHILDREN,
      V043CreateCoparentCollections.AUDITS,
      V069CreateCoparentDocuments.DOCUMENTS,
      V069CreateCoparentDocuments.OFFERS,
      V069CreateCoparentDocuments.NOTICES);

  @Autowired
  @Qualifier("coparentMongoTemplate")
  private MongoTemplate coparent;

  @Autowired
  private FamilyRepository families;

  @Autowired
  private ParentRepository parents;

  @Autowired
  private ChildRepository children;

  @Autowired
  private DocumentSchoolOfferRepository offers;

  @Autowired
  private SchoolDocumentRepository schoolDocuments;

  @Autowired
  private SchoolAttachmentStore attachments;

  @Autowired
  private CoparentProperties coparentProperties;

  private final GmailClient gmail = mock(GmailClient.class);
  private ObjectId mia;
  private ObjectId leo;
  private Instant sent;

  @BeforeEach
  void setUp() {
    clean();
    V069CreateCoparentDocuments.createIndexes(coparent);
    sent = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    final String academicYear = AcademicYear.of(LocalDate.ofInstant(sent, LONDON));
    final Instant now = Instant.now();
    final ObjectId familyId = new ObjectId(MAILBOX);
    final ObjectId alice = new ObjectId();
    mia = new ObjectId();
    leo = new ObjectId();
    families.save(new Family(familyId, "Example Family", "Europe/London", List.of(alice),
        List.of(mia, leo), List.of(), null, now, now));
    parents.save(new Parent(alice, "auth0|alice", familyId, "Alice", "alice@example.com",
        "primary", "active", "#123456", null, now, now, now));
    children.save(new Child(mia, familyId, "Mia Taylor", LocalDate.of(2018, 3, 1), null, null,
        null, null, now, now, new Child.SchoolCalendarLink("Elis", "Year 4", academicYear)));
    children.save(new Child(leo, familyId, "Leo Taylor", LocalDate.of(2021, 1, 10), null, null,
        null, null, now, now, new Child.SchoolCalendarLink(null, "Year 1", academicYear)));
  }

  @AfterEach
  void clean() {
    COLLECTIONS.forEach(collection -> coparent.getCollection(collection)
        .deleteMany(new Document()));
    schoolDocuments.deleteAll();
    FileSystemUtils.deleteRecursively(Path.of("build/test-offer-documents").toFile());
    FileSystemUtils.deleteRecursively(Path.of("build/test-offer-attachments").toFile());
    reset(chatModel);
  }

  @Test
  void offersLettersAndReportPromptsThenKeepsAndDismissesEachOnce() throws Exception {
    final String consent = letter("m1", "Year 4 residential: consent form",
        "Kingswood-consent.pdf", Visibility.PUBLIC, 0);
    final String menu = letter("m2", "Lunch menu", "menu.pdf", Visibility.PUBLIC, 1);
    letter("m3", "Year 2 trip to the farm", "farm.pdf", Visibility.PUBLIC, 2);
    final String personal = letter("m4", "A letter from the office", "Mia and Leo Taylor.pdf",
        Visibility.RESTRICTED, 3);
    final String report = email("m5", "A report for Mia is ready",
        "Read it at https://app.insighttracking.com/parent/report/9 today.", 4);
    final String forged = email("m6", "A report for Leo is ready", "Click here.", 5);
    when(gmail.isConfigured()).thenReturn(true);
    when(gmail.fetchMessage("m5")).thenReturn(Optional.of(message("m5",
        "system@insighttracking.com")));
    when(gmail.fetchMessage("m6")).thenReturn(Optional.of(message("m6",
        "office@school.example")));
    when(chatModel.call(any(Prompt.class))).thenAnswer(call -> {
      final String asked = ((Prompt) call.getArgument(0)).getContents();
      return answer(asked.contains("Kingswood")
          ? "{\"keep\":true,\"kind\":\"consent\",\"reason\":\"A form to sign.\"}"
          : "{\"keep\":false,\"kind\":\"other\",\"reason\":\"A lunch menu.\"}");
    });

    final SchoolDocumentOfferSync.Report first = sync().run();

    assertThat(first).isEqualTo(new SchoolDocumentOfferSync.Report(3, 2, 2));
    final DocumentSchoolOffer declinedMenu = offerFor(menu);
    assertThat(declinedMenu.status()).isEqualTo(DocumentSchoolOffer.Status.DISMISSED);
    assertThat(declinedMenu.decidedBy()).as("nobody was shown it").isNull();
    assertThat(declinedMenu.reason()).isEqualTo("Not offered: A lunch menu.");
    assertThat(offerFor(forged).status()).isEqualTo(DocumentSchoolOffer.Status.DISMISSED);

    mockMvc.perform(get(OFFERS, MAILBOX).with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(3)))
        .andExpect(jsonPath("$[0].kind", is("REPORT_READY")))
        .andExpect(jsonPath("$[0].childIds", contains(mia.toHexString())))
        .andExpect(jsonPath("$[0].sender", is("Insight Tracking")))
        .andExpect(jsonPath("$[0].reportLink",
            is("https://app.insighttracking.com/parent/report/9")))
        .andExpect(jsonPath("$[0].suggestedTitle",
            is(SchoolLetterRules.reportTitle(LocalDate.ofInstant(sentAt(4), LONDON)))))
        .andExpect(jsonPath("$[0].suggestedDatedOn",
            is(LocalDate.ofInstant(sentAt(4), LONDON).toString())))
        .andExpect(jsonPath("$[1].personal", is(true)))
        .andExpect(jsonPath("$[1].reason", is("Addressed to you and names Mia and Leo.")))
        .andExpect(jsonPath("$[1].childIds",
            containsInAnyOrder(mia.toHexString(), leo.toHexString())))
        .andExpect(jsonPath("$[2].kind", is("LETTER")))
        .andExpect(jsonPath("$[2].personal", is(false)))
        .andExpect(jsonPath("$[2].title", is("Year 4 residential: consent form")))
        .andExpect(jsonPath("$[2].section", is("SCHOOL")))
        .andExpect(jsonPath("$[2].attachmentName", is("Kingswood-consent.pdf")))
        .andExpect(jsonPath("$[2].childIds", contains(mia.toHexString())))
        .andExpect(jsonPath("$[2].reason",
            is("Sent to Year 4 parents. Mia is in Year 4 (Elis's class).")))
        .andExpect(jsonPath("$[2].reportLink", nullValue()));

    final String consentOffer = offerFor(consent).id().toHexString();
    keep(consentOffer, "[\"" + mia + "\"]", "Kingswood consent")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.source.kind", is("SCHOOL_LETTER")))
        .andExpect(jsonPath("$.source.schoolDocumentId", is(consent)))
        .andExpect(jsonPath("$.title", is("Kingswood consent")))
        .andExpect(jsonPath("$.datedOn", is(LocalDate.ofInstant(sentAt(0), LONDON).toString())))
        .andExpect(jsonPath("$.versions[0].files[0].mimeType", is("application/pdf")));
    keep(consentOffer, "[\"" + mia + "\"]", "Again").andExpect(status().isConflict());
    final DocumentSchoolOffer kept = offerFor(consent);
    assertThat(kept.status()).isEqualTo(DocumentSchoolOffer.Status.KEPT);
    assertThat(kept.documentId()).isNotNull();

    keep(offerFor(personal).id().toHexString(), "[\"" + mia + "\",\"" + leo + "\"]",
        "Office letter")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.childIds", hasSize(2)));
    assertThat(coparent.getCollection(V069CreateCoparentDocuments.DOCUMENTS).countDocuments())
        .as("one document for both children").isEqualTo(2);

    final String reportOffer = offerFor(report).id().toHexString();
    keep(reportOffer, "[]", "Report").andExpect(status().isConflict());
    mockMvc.perform(post(OFFERS + "/{id}/dismiss", MAILBOX, reportOffer).with(user("alice")))
        .andExpect(status().isNoContent());
    mockMvc.perform(post(OFFERS + "/{id}/dismiss", MAILBOX, reportOffer).with(user("alice")))
        .andExpect(status().isConflict());
    mockMvc.perform(post(OFFERS + "/{id}/dismiss", MAILBOX, new ObjectId().toHexString())
        .with(user("alice"))).andExpect(status().isNotFound());
    mockMvc.perform(get(OFFERS, MAILBOX).with(user("bob"))).andExpect(status().isNotFound());
    assertThat(offerFor(report).decidedBy()).isNotNull();

    assertThat(sync().run()).as("never offered again")
        .isEqualTo(new SchoolDocumentOfferSync.Report(0, 0, 0));
    verify(chatModel, times(2)).call(any(Prompt.class));
    mockMvc.perform(get(OFFERS, MAILBOX).with(user("alice")))
        .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void classifierFailureIsRetriedAndMissingFileIsNotFound() throws Exception {
    final String consent = letter("m1", "Year 4 trip letter", "trip.pdf", Visibility.PUBLIC, 0);
    when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("down"));

    assertThat(sync().run()).isEqualTo(new SchoolDocumentOfferSync.Report(0, 0, 1));
    assertThat(offers.findAll()).as("a failure records nothing").isEmpty();

    reset(chatModel);
    when(chatModel.call(any(Prompt.class))).thenReturn(
        answer("{\"keep\":true,\"kind\":\"trip\",\"reason\":\"Trip details.\"}"));
    assertThat(sync().run().offered()).isEqualTo(1);

    FileSystemUtils.deleteRecursively(Path.of("build/test-offer-attachments").toFile());
    keep(offerFor(consent).id().toHexString(), "[]", "Trip").andExpect(status().isNotFound());
    assertThat(offerFor(consent).status()).isEqualTo(DocumentSchoolOffer.Status.PENDING);
    assertThat(coparent.findAll(FamilyDocument.class)).isEmpty();
  }

  @Test
  void reportsWaitWhileGmailCannotSayWhoSentThem() throws Exception {
    email("m5", "A report for Mia is ready", "Log in.", 0);
    when(gmail.isConfigured()).thenReturn(false);

    assertThat(sync().run().offered()).isZero();
    assertThat(offers.findAll()).isEmpty();
    verify(gmail, times(0)).fetchMessage(anyString());
  }

  // ---- helpers -----------------------------------------------------------------------------

  private SchoolDocumentOfferSync sync() {
    final SchoolDocumentOfferProperties settings =
        new SchoolDocumentOfferProperties(true, true, "test-model", 10, 60, null);
    return new SchoolDocumentOfferSync(schoolDocuments, coparent,
        new SchoolLetterClassifier(chatModel, settings), gmail, coparentProperties,
        new SchoolCalendarProperties(true, false, "test-model", 60, MAILBOX), settings,
        Clock.systemUTC());
  }

  private Instant sentAt(final int order) {
    return sent.plusSeconds(60L * order);
  }

  private String email(final String messageId, final String subject, final String body,
      final int order) {
    final Visibility visibility = order >= 3 ? Visibility.RESTRICTED : Visibility.PUBLIC;
    final String id = SchoolIds.documentId(SchoolSourceType.EMAIL, messageId);
    schoolDocuments.save(new SchoolDocument(id, SchoolSourceType.EMAIL, messageId, subject, body,
        sentAt(order), sentAt(order), visibility, null, null, null, null, false, List.of(),
        "hash-" + messageId, null));
    return id;
  }

  private String letter(final String messageId, final String subject, final String filename,
      final Visibility visibility, final int order) {
    email(messageId, subject, "Please see the attached letter.", order);
    final String ref = "gmail:" + messageId + ":" + filename;
    final String id = SchoolIds.documentId(SchoolSourceType.PDF, ref);
    schoolDocuments.save(new SchoolDocument(id, SchoolSourceType.PDF, ref, filename,
        "The text of " + filename, sentAt(order), sentAt(order), visibility, null, null, null,
        null, false, List.of(), "hash-" + ref, null));
    attachments.store(id, PDF);
    return id;
  }

  private static GmailMessage message(final String id, final String from) {
    return new GmailMessage(id, "A report", from, "Kilmorie Primary School", Instant.now(),
        "Log in.", List.of(), List.of());
  }

  private static ChatResponse answer(final String json) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
  }

  private DocumentSchoolOffer offerFor(final String schoolDocumentId) {
    return offers.findAll().stream()
        .filter(offer -> offer.schoolDocumentId().equals(schoolDocumentId))
        .findFirst().orElseThrow();
  }

  private ResultActions keep(final String offerId, final String childIds, final String title)
      throws Exception {
    return mockMvc.perform(post(OFFERS + "/{id}/keep", MAILBOX, offerId).with(user("alice"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"childIds":%s,"section":"SCHOOL","title":"%s"}""".formatted(childIds, title)));
  }

  private static JwtRequestPostProcessor user(final String subject) {
    return jwt().jwt(token -> token.subject("auth0|" + subject)
        .claim("https://coparents.simonrowe.dev/email", subject + "@example.com"));
  }
}
