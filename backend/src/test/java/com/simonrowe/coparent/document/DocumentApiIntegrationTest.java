package com.simonrowe.coparent.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.model.DocumentNotice;
import com.simonrowe.coparent.model.DocumentSchoolOffer;
import com.simonrowe.coparent.model.DocumentSection;
import com.simonrowe.coparent.model.Invitation;
import com.simonrowe.coparent.persistence.DocumentNoticeRepository;
import com.simonrowe.coparent.persistence.DocumentSchoolOfferRepository;
import com.simonrowe.coparent.persistence.InvitationRepository;
import com.simonrowe.migration.changeunits.V043CreateCoparentCollections;
import com.simonrowe.migration.changeunits.V069CreateCoparentDocuments;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.FileSystemUtils;

/** The documents API end to end: guarded writes, either parent, private files. */
@TestPropertySource(properties = {
    "coparent.enabled=true",
    "coparent.document-path=build/test-coparent-documents/"
})
class DocumentApiIntegrationTest extends AbstractIntegrationTest {

  private static final String DOCUMENTS = "/api/coparent/families/{familyId}/documents";
  private static final Path STORE = Path.of("build/test-coparent-documents");
  private static final byte[] JPEG = DocumentTestFiles.jpeg();

  private static final List<String> COLLECTIONS = List.of(
      V043CreateCoparentCollections.FAMILIES,
      V043CreateCoparentCollections.PARENTS,
      V043CreateCoparentCollections.CHILDREN,
      V043CreateCoparentCollections.INVITATIONS,
      V043CreateCoparentCollections.ONBOARDING,
      V043CreateCoparentCollections.AUDITS,
      V069CreateCoparentDocuments.DOCUMENTS,
      V069CreateCoparentDocuments.OFFERS,
      V069CreateCoparentDocuments.NOTICES);

  @Autowired
  @Qualifier("coparentMongoTemplate")
  private MongoTemplate mongoTemplate;

  @Autowired
  private InvitationRepository invitations;

  @Autowired
  private DocumentSchoolOfferRepository offers;

  @Autowired
  private DocumentNoticeRepository notices;

  @Autowired
  private DocumentNoticeService noticeService;

  @BeforeEach
  @AfterEach
  void clean() throws IOException {
    COLLECTIONS.forEach(collection -> mongoTemplate.getCollection(collection)
        .deleteMany(new Document()));
    FileSystemUtils.deleteRecursively(STORE);
  }

  @Test
  void createsListsAndShowsHistoryWithFilesStoredByIdOnly() throws Exception {
    final Fixture f = family(true);
    final MvcResult created = create(f, "alice", details(f.childId(), "Passport",
        "\"dueOn\":\"2035-08-02\",\"dueKind\":\"EXPIRES\""), JPEG, DocumentTestFiles.png())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.title", is("Passport")))
        .andExpect(jsonPath("$.section", is("IDENTITY")))
        .andExpect(jsonPath("$.childIds", contains(f.childId())))
        .andExpect(jsonPath("$.datedOn", is("2025-08-02")))
        .andExpect(jsonPath("$.dueOn", is("2035-08-02")))
        .andExpect(jsonPath("$.dueKind", is("EXPIRES")))
        .andExpect(jsonPath("$.version", is(1)))
        .andExpect(jsonPath("$.source.kind", is("UPLOAD")))
        .andExpect(jsonPath("$.archivedAt", nullValue()))
        .andExpect(jsonPath("$.versions", hasSize(1)))
        .andExpect(jsonPath("$.versions[0].files", hasSize(2)))
        .andExpect(jsonPath("$.versions[0].files[0].mimeType", is("image/jpeg")))
        .andExpect(jsonPath("$.versions[0].files[1].mimeType", is("image/png")))
        .andExpect(jsonPath("$.versions[0].dueOn", is("2035-08-02")))
        .andExpect(jsonPath("$.versions[0].uploadedBy", is(f.aliceId())))
        .andExpect(jsonPath("$.createdBy", is(f.aliceId())))
        .andExpect(jsonPath("$.history").doesNotExist())
        .andReturn();
    final String id = id(created);

    mockMvc.perform(get(DOCUMENTS, f.familyId()).with(user("bob")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id", is(id)));
    mockMvc.perform(get(DOCUMENTS + "/{id}", f.familyId(), id).with(user("bob")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.history", hasSize(1)))
        .andExpect(jsonPath("$.history[0].action", is("CREATED")))
        .andExpect(jsonPath("$.history[0].parentId", is(f.aliceId())));

    final String fileId = JsonPath.read(created.getResponse().getContentAsString(),
        "$.versions[0].files[0].fileId");
    try (var files = Files.walk(STORE)) {
      assertThat(files.filter(Files::isRegularFile).map(path -> STORE.relativize(path)
          .toString())).hasSize(2).contains(f.familyId() + "/" + fileId);
    }
  }

  @Test
  void detailsMayArriveAsPlainFormField() throws Exception {
    final Fixture f = family(true);
    mockMvc.perform(multipart(DOCUMENTS, f.familyId())
            .part(new MockPart("details", details(null, "Red book", "")
                .getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("files", "a.jpg", "image/jpeg", JPEG))
            .with(user("alice")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.childIds", hasSize(0)));
  }

  @Test
  void editIsGuardedOnTheVersionAndCorrectsTheCurrentVersionsDates() throws Exception {
    final Fixture f = family(true);
    final String id = id(create(f, "alice", details(f.childId(), "Care plan", ""), JPEG)
        .andExpect(status().isCreated()).andReturn());

    edit(f, "bob", id, """
        {"childIds":[],"section":"HEALTH","title":"Asthma care plan","datedOn":"2026-01-10",
         "dueOn":"2027-01-10","dueKind":"REVIEW","note":"In the kitchen drawer","version":1}""")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version", is(2)))
        .andExpect(jsonPath("$.title", is("Asthma care plan")))
        .andExpect(jsonPath("$.section", is("HEALTH")))
        .andExpect(jsonPath("$.note", is("In the kitchen drawer")))
        .andExpect(jsonPath("$.childIds", hasSize(0)))
        .andExpect(jsonPath("$.versions[0].dueOn", is("2027-01-10")))
        .andExpect(jsonPath("$.versions[0].dueKind", is("REVIEW")));

    edit(f, "alice", id, """
        {"section":"HEALTH","title":"Stale","datedOn":"2026-01-10","version":1}""")
        .andExpect(status().isConflict());
    edit(f, "alice", id, """
        {"section":"HEALTH","title":"No version","datedOn":"2026-01-10"}""")
        .andExpect(status().isBadRequest());
  }

  @Test
  void newVersionBecomesCurrentAndTheDocumentMirrorsItsDates() throws Exception {
    final Fixture f = family(true);
    final String id = id(create(f, "alice", details(f.childId(), "Passport",
        "\"dueOn\":\"2027-08-02\",\"dueKind\":\"EXPIRES\""), JPEG)
        .andExpect(status().isCreated()).andReturn());

    mockMvc.perform(multipart(DOCUMENTS + "/{id}/versions", f.familyId(), id)
            .file(json("details", """
                {"datedOn":"2027-07-01","dueOn":"2037-07-01","dueKind":"EXPIRES","version":1}"""))
            .file(new MockMultipartFile("files", "new.pdf", "application/pdf",
                DocumentTestFiles.fakePdf()))
            .with(user("bob")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version", is(2)))
        .andExpect(jsonPath("$.versions", hasSize(2)))
        .andExpect(jsonPath("$.versions[0].dueOn", is("2027-08-02")))
        .andExpect(jsonPath("$.versions[1].dueOn", is("2037-07-01")))
        .andExpect(jsonPath("$.versions[1].uploadedBy", is(f.bobId())))
        .andExpect(jsonPath("$.versions[1].files[0].mimeType", is("application/pdf")))
        .andExpect(jsonPath("$.datedOn", is("2027-07-01")))
        .andExpect(jsonPath("$.dueOn", is("2037-07-01")));

    mockMvc.perform(get(DOCUMENTS + "/{id}", f.familyId(), id).with(user("alice")))
        .andExpect(jsonPath("$.history[1].action", is("NEW_VERSION")))
        .andExpect(jsonPath("$.history[1].parentId", is(f.bobId())));
  }

  @Test
  void eitherParentArchivesAndRestoresAndNothingIsDeleted() throws Exception {
    final Fixture f = family(true);
    final String id = id(create(f, "alice", details(f.childId(), "Trip letter", ""), JPEG)
        .andExpect(status().isCreated()).andReturn());

    version(f, "bob", id, "archive", 1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archivedBy", is(f.bobId())))
        .andExpect(jsonPath("$.version", is(2)));
    mockMvc.perform(get(DOCUMENTS, f.familyId()).with(user("alice")))
        .andExpect(jsonPath("$", hasSize(0)));
    mockMvc.perform(get(DOCUMENTS, f.familyId()).param("archived", "true").with(user("alice")))
        .andExpect(jsonPath("$", hasSize(1)));
    version(f, "alice", id, "archive", 2).andExpect(status().isConflict());
    edit(f, "alice", id, """
        {"section":"SCHOOL","title":"x","datedOn":"2026-01-10","version":2}""")
        .andExpect(status().isConflict());

    version(f, "alice", id, "restore", 2)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archivedAt", nullValue()))
        .andExpect(jsonPath("$.archivedBy", nullValue()));
    mockMvc.perform(get(DOCUMENTS + "/{id}", f.familyId(), id).with(user("bob")))
        .andExpect(jsonPath("$.history[*].action", contains("CREATED", "ARCHIVED", "RESTORED")))
        .andExpect(jsonPath("$.history[1].parentId", is(f.bobId())))
        .andExpect(jsonPath("$.history[2].parentId", is(f.aliceId())));
    try (var files = Files.walk(STORE)) {
      assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1);
    }
  }

  @Test
  void someoneOutsideTheFamilySeesNothing() throws Exception {
    final Fixture f = family(true);
    final MvcResult created = create(f, "alice", details(f.childId(), "Passport", ""), JPEG)
        .andExpect(status().isCreated()).andReturn();
    final String id = id(created);
    final String fileId = JsonPath.read(created.getResponse().getContentAsString(),
        "$.versions[0].files[0].fileId");

    mockMvc.perform(get(DOCUMENTS, f.familyId()).with(user("mallory")))
        .andExpect(status().isNotFound());
    mockMvc.perform(get(DOCUMENTS + "/{id}", f.familyId(), id).with(user("mallory")))
        .andExpect(status().isNotFound());
    mockMvc.perform(get(DOCUMENTS + "/{id}/files/{fileId}", f.familyId(), id, fileId)
            .with(user("mallory")))
        .andExpect(status().isNotFound());
    create(f, "mallory", details(null, "x", ""), JPEG).andExpect(status().isNotFound());

    // Mallory's own family cannot reach Alice's document by its id either.
    mockMvc.perform(get("/api/coparent/me").with(user("mallory"))).andExpect(status().isOk());
    final String other = createFamily("mallory");
    mockMvc.perform(get(DOCUMENTS + "/{id}", other, id).with(user("mallory")))
        .andExpect(status().isNotFound());
    mockMvc.perform(get(DOCUMENTS + "/{id}/files/{fileId}", other, id, fileId)
            .with(user("mallory")))
        .andExpect(status().isNotFound());
  }

  @Test
  void filesAreServedPrivatelyInlineOrAsDownload() throws Exception {
    final Fixture f = family(true);
    final MvcResult created = create(f, "alice", details(f.childId(), "Birth certificate", ""),
        JPEG, DocumentTestFiles.png()).andExpect(status().isCreated()).andReturn();
    final String id = id(created);
    final String fileId = JsonPath.read(created.getResponse().getContentAsString(),
        "$.versions[0].files[0].fileId");

    final MvcResult inline = mockMvc.perform(get(DOCUMENTS + "/{id}/files/{fileId}",
            f.familyId(), id, fileId).with(user("bob")))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/jpeg"))
        .andExpect(header().string("Content-Disposition",
            startsWith("inline; filename=\"Birth certificate page 1.jpg\"")))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Security-Policy", "sandbox"))
        .andExpect(header().stringValues("Cache-Control", "private, no-store"))
        .andReturn();
    assertThat(inline.getResponse().getContentAsByteArray()).isEqualTo(JPEG);

    mockMvc.perform(get(DOCUMENTS + "/{id}/files/{fileId}", f.familyId(), id, fileId)
            .param("download", "true").with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition",
            startsWith("attachment; filename=\"Birth certificate page 1.jpg\"")));
    mockMvc.perform(get(DOCUMENTS + "/{id}/files/{fileId}", f.familyId(), id,
            new ObjectId().toHexString()).with(user("alice")))
        .andExpect(status().isNotFound());
  }

  @Test
  void refusesBadDetailsAndFilesBeforeWritingAnything() throws Exception {
    final Fixture f = family(true);
    create(f, "alice", details(f.childId(), "Scan", ""), DocumentTestFiles.html())
        .andExpect(status().isBadRequest());
    create(f, "alice", details(new ObjectId().toHexString(), "Scan", ""), JPEG)
        .andExpect(status().isBadRequest());
    create(f, "alice", details(f.childId(), "Scan", "\"dueOn\":\"2030-01-01\""), JPEG)
        .andExpect(status().isBadRequest());
    create(f, "alice", details(f.childId(), "x".repeat(121), ""), JPEG)
        .andExpect(status().isBadRequest());
    create(f, "alice", details(f.childId(), "Scan", "")).andExpect(status().isBadRequest());
    create(f, "alice", "{not json", JPEG).andExpect(status().isBadRequest());

    assertThat(mongoTemplate.getCollection(V069CreateCoparentDocuments.DOCUMENTS)
        .countDocuments()).isZero();
    assertThat(Files.exists(STORE)).isFalse();
  }

  @Test
  void documentCanBeAddedBeforeTheCoparentJoins() throws Exception {
    final Fixture f = family(false);

    create(f, "alice", details(f.childId(), "Passport", ""), JPEG)
        .andExpect(status().isCreated());
    assertThat(notices.findAll()).as("nobody active to tell").isEmpty();
  }

  @Test
  void reportPromptIsFulfilledOnceByUploadingTheReport() throws Exception {
    final Fixture f = family(true);
    final DocumentSchoolOffer offer = offers.insert(offer(f, "school-doc-1",
        DocumentSchoolOffer.Kind.REPORT_READY));

    create(f, "alice", details(f.childId(), "Autumn report 2026/27",
        "\"schoolOfferId\":\"" + offer.id().toHexString() + "\""), JPEG)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.source.kind", is("SCHOOL_REPORT")))
        .andExpect(jsonPath("$.source.schoolDocumentId", is("school-doc-1")));
    final DocumentSchoolOffer kept = offers.findById(offer.id()).orElseThrow();
    assertThat(kept.status()).isEqualTo(DocumentSchoolOffer.Status.KEPT);
    assertThat(kept.documentId()).isNotNull();
    assertThat(kept.decidedBy().toHexString()).isEqualTo(f.aliceId());
    assertThat(kept.decidedAt()).isNotNull();

    create(f, "bob", details(f.childId(), "Again",
        "\"schoolOfferId\":\"" + offer.id().toHexString() + "\""), JPEG)
        .andExpect(status().isConflict());

    final DocumentSchoolOffer letter = offers.insert(offer(f, "school-doc-2",
        DocumentSchoolOffer.Kind.LETTER));
    create(f, "alice", details(f.childId(), "Letter",
        "\"schoolOfferId\":\"" + letter.id().toHexString() + "\""), JPEG)
        .andExpect(status().isConflict());
    create(f, "alice", details(f.childId(), "Elsewhere",
        "\"schoolOfferId\":\"" + new ObjectId().toHexString() + "\""), JPEG)
        .andExpect(status().isNotFound());

    assertThat(mongoTemplate.getCollection(V069CreateCoparentDocuments.DOCUMENTS)
        .countDocuments()).isEqualTo(1);
    try (var files = Files.walk(STORE)) {
      assertThat(files.filter(Files::isRegularFile).count())
          .as("refused uploads leave no file behind").isEqualTo(1);
    }
  }

  @Test
  void uniqueOfferIndexRefusesSecondOfferForOneSchoolDocument() throws Exception {
    final Fixture f = family(true);
    V069CreateCoparentDocuments.createIndexes(mongoTemplate);
    offers.insert(offer(f, "school-doc-1", DocumentSchoolOffer.Kind.LETTER));

    assertThatThrownBy(() -> offers.insert(offer(f, "school-doc-1",
        DocumentSchoolOffer.Kind.LETTER))).isInstanceOf(DuplicateKeyException.class);
    offers.insert(offer(f, "school-doc-2", DocumentSchoolOffer.Kind.LETTER));
  }

  @Test
  void changesBatchIntoOneNoticeForTheOtherParentOnly() throws Exception {
    final Fixture f = family(true);
    V069CreateCoparentDocuments.createIndexes(mongoTemplate);
    final String first = id(create(f, "alice", details(f.childId(), "Passport", ""), JPEG)
        .andExpect(status().isCreated()).andReturn());
    create(f, "alice", details(f.childId(), "Red book", ""), JPEG)
        .andExpect(status().isCreated());
    version(f, "alice", first, "archive", 1).andExpect(status().isOk());

    final List<DocumentNotice> waiting = notices.findAll();
    assertThat(waiting).singleElement().satisfies(notice -> {
      assertThat(notice.recipientParentId().toHexString()).isEqualTo(f.bobId());
      assertThat(notice.items()).extracting(DocumentNotice.Item::change).containsExactly(
          DocumentNotice.Change.ADDED, DocumentNotice.Change.ADDED,
          DocumentNotice.Change.ARCHIVED);
      assertThat(notice.sendAfter()).isAfter(notice.firstAt().plusSeconds(14 * 60));
    });

    assertThat(noticeService.sendDue(Instant.now())).as("not due yet").isZero();
    assertThat(noticeService.sendDue(Instant.now().plusSeconds(16 * 60))).isEqualTo(1);
    assertThat(notices.findAll()).isEmpty();
  }

  @Test
  void readingIsUnavailableWhileQuickAddIsOff() throws Exception {
    final Fixture f = family(true);
    mockMvc.perform(multipart(DOCUMENTS + "/read", f.familyId())
            .file(new MockMultipartFile("files", "a.jpg", "image/jpeg", JPEG))
            .with(user("alice")))
        .andExpect(status().isServiceUnavailable());
  }

  // ---- helpers -----------------------------------------------------------------------------

  private static String details(final String childId, final String title, final String extra) {
    return """
        {"childIds":[%s],"section":"%s","title":"%s","datedOn":"2025-08-02"%s}"""
        .formatted(childId == null ? "" : "\"" + childId + "\"", DocumentSection.IDENTITY,
            title, extra.isEmpty() ? "" : "," + extra);
  }

  private ResultActions create(final Fixture f, final String who, final String details,
      final byte[]... files) throws Exception {
    final var request = multipart(DOCUMENTS, f.familyId()).file(json("details", details));
    for (int index = 0; index < files.length; index++) {
      request.file(new MockMultipartFile("files", "file" + index, "application/octet-stream",
          files[index]));
    }
    return mockMvc.perform(request.with(user(who)));
  }

  private ResultActions edit(final Fixture f, final String who, final String id,
      final String body) throws Exception {
    return mockMvc.perform(put(DOCUMENTS + "/{id}", f.familyId(), id).with(user(who))
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private ResultActions version(final Fixture f, final String who, final String id,
      final String action, final long version) throws Exception {
    return mockMvc.perform(post(DOCUMENTS + "/{id}/" + action, f.familyId(), id)
        .with(user(who)).contentType(MediaType.APPLICATION_JSON)
        .content("{\"version\":" + version + "}"));
  }

  private static MockMultipartFile json(final String name, final String body) {
    return new MockMultipartFile(name, "", MediaType.APPLICATION_JSON_VALUE,
        body.getBytes(StandardCharsets.UTF_8));
  }

  private static String id(final MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private static DocumentSchoolOffer offer(final Fixture f, final String schoolDocumentId,
      final DocumentSchoolOffer.Kind kind) {
    final Instant now = Instant.now();
    return new DocumentSchoolOffer(null, new ObjectId(f.familyId()), schoolDocumentId, kind,
        List.of(new ObjectId(f.childId())), DocumentSection.SCHOOL, "A report for Robin",
        now, true, kind == DocumentSchoolOffer.Kind.REPORT_READY
            ? "https://app.insighttracking.com/report" : null,
        DocumentSchoolOffer.Status.PENDING, null, null, null, now, now, "Insight Tracking",
        "The school publishes reports on Insight Tracking, not by email.", null, null, null);
  }

  private String createFamily(final String who) throws Exception {
    final MvcResult family = mockMvc.perform(post("/api/coparent/families").with(user(who))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Example Family","timeZone":"Europe/London","fullName":"%s Example"}
                """.formatted(who)))
        .andExpect(status().isCreated()).andReturn();
    return JsonPath.read(family.getResponse().getContentAsString(), "$.id");
  }

  private Fixture family(final boolean bobJoins) throws Exception {
    mockMvc.perform(get("/api/coparent/me").with(user("alice"))).andExpect(status().isOk());
    final String familyId = createFamily("alice");
    mockMvc.perform(post("/api/coparent/families/{familyId}/invitations", familyId)
            .with(user("alice")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"bob@example.com\",\"role\":\"co-parent\"}"))
        .andExpect(status().isCreated());
    String bobId = null;
    if (bobJoins) {
      final Invitation invitation = invitations.findAll().getFirst();
      mockMvc.perform(post("/api/coparent/invitations/accept").with(user("bob"))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"token\":\"" + invitation.token() + "\"}"))
          .andExpect(status().isOk());
      bobId = parentId(familyId, "bob");
    }
    final MvcResult child = mockMvc.perform(post("/api/coparent/families/{familyId}/children",
            familyId).with(user("alice")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"fullName\":\"Robin\",\"dateOfBirth\":\"2018-04-03\"}"))
        .andExpect(status().isCreated()).andReturn();
    return new Fixture(familyId, JsonPath.read(child.getResponse().getContentAsString(), "$.id"),
        parentId(familyId, "alice"), bobId);
  }

  private String parentId(final String familyId, final String who) throws Exception {
    final MvcResult profile = mockMvc.perform(get("/api/coparent/me").with(user(who)))
        .andExpect(status().isOk()).andReturn();
    final List<String> ids = JsonPath.read(profile.getResponse().getContentAsString(),
        "$.profiles[?(@.familyId=='" + familyId + "')].id");
    return ids.getFirst();
  }

  private JwtRequestPostProcessor user(final String subject) {
    return jwt().jwt(token -> token.subject("auth0|" + subject)
        .claim("https://coparents.simonrowe.dev/email", subject + "@example.com"));
  }

  private record Fixture(String familyId, String childId, String aliceId, String bobId) {
  }
}
