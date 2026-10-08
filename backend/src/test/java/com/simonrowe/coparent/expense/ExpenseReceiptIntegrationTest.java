package com.simonrowe.coparent.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.model.Invitation;
import com.simonrowe.coparent.persistence.InvitationRepository;
import com.simonrowe.migration.changeunits.V043CreateCoparentCollections;
import com.simonrowe.migration.changeunits.V052CreateCoparentExpenses;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.FileSystemUtils;

/** Receipts are private to the family, proven by their bytes, and capped at five. */
@TestPropertySource(properties = {
    "coparent.enabled=true",
    "coparent.receipt-path=build/test-coparent-receipts/"
})
class ExpenseReceiptIntegrationTest extends AbstractIntegrationTest {

  private static final String RECEIPTS =
      "/api/coparent/families/{familyId}/expenses/{expenseId}/receipts";
  private static final Path STORE = Path.of("build/test-coparent-receipts");
  private static final byte[] JPEG = jpeg();

  private static final List<String> COLLECTIONS = List.of(
      V043CreateCoparentCollections.FAMILIES,
      V043CreateCoparentCollections.PARENTS,
      V043CreateCoparentCollections.CHILDREN,
      V043CreateCoparentCollections.INVITATIONS,
      V043CreateCoparentCollections.ONBOARDING,
      V043CreateCoparentCollections.AUDITS,
      V052CreateCoparentExpenses.EXPENSES);

  @Autowired
  @Qualifier("coparentMongoTemplate")
  private MongoTemplate mongoTemplate;

  @Autowired
  private InvitationRepository invitations;

  @BeforeEach
  @AfterEach
  void clean() throws IOException {
    COLLECTIONS.forEach(collection -> mongoTemplate.getCollection(collection)
        .deleteMany(new Document()));
    FileSystemUtils.deleteRecursively(STORE);
  }

  @Test
  void eitherParentCanAttachAndBothCanReadButNobodyElse() throws Exception {
    final Fixture f = expense();
    final MvcResult uploaded = upload(f, "bob", "IMG_0042.JPG", JPEG)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.receipts", hasSize(1)))
        .andExpect(jsonPath("$.receipts[0].contentType", is("image/jpeg")))
        .andExpect(jsonPath("$.receipts[0].displayName", is("IMG_0042.JPG")))
        .andExpect(jsonPath("$.receipts[0].uploadedBy", is(f.bobId())))
        .andExpect(jsonPath("$.version", is(2)))
        .andReturn();
    final String receiptId = JsonPath.read(uploaded.getResponse().getContentAsString(),
        "$.receipts[0].id");

    final MvcResult download = mockMvc.perform(get(RECEIPTS + "/{receiptId}", f.familyId(),
            f.expenseId(), receiptId).with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/jpeg"))
        .andExpect(header().string("Content-Disposition", "inline; filename=\"receipt-1.jpg\""))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Security-Policy", "sandbox"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andReturn();
    assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(JPEG);

    mockMvc.perform(get(RECEIPTS + "/{receiptId}", f.familyId(), f.expenseId(), receiptId)
            .with(user("mallory")))
        .andExpect(status().isNotFound());
    upload(f, "mallory", "x.jpg", JPEG).andExpect(status().isNotFound());

    // Stored by id under the family, never under the name the parent chose.
    try (var files = Files.walk(STORE)) {
      assertThat(files.filter(Files::isRegularFile).map(path -> STORE.relativize(path)
          .toString())).containsExactly(f.familyId() + "/" + receiptId);
    }
  }

  @Test
  void fileIsJudgedByItsBytesNotItsName() throws Exception {
    final Fixture f = expense();
    final byte[] heic = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
    upload(f, "alice", "receipt.jpg", heic).andExpect(status().isUnsupportedMediaType());
    upload(f, "alice", "receipt.pdf", JPEG).andExpect(status().isCreated())
        .andExpect(jsonPath("$.receipts[0].contentType", is("image/jpeg")));
    upload(f, "alice", "empty.pdf", new byte[0]).andExpect(status().isBadRequest());
  }

  @Test
  void anExpenseHoldsAtMostFiveReceipts() throws Exception {
    final Fixture f = expense();
    for (int index = 0; index < 5; index++) {
      upload(f, "alice", "r" + index + ".jpg", JPEG).andExpect(status().isCreated());
    }
    upload(f, "alice", "r5.jpg", JPEG).andExpect(status().isConflict());
    try (var files = Files.walk(STORE)) {
      assertThat(files.filter(Files::isRegularFile).count())
          .as("the refused sixth file is not left behind").isEqualTo(5);
    }
  }

  @Test
  void onlyTheParentWhoAddedReceiptCanRemoveIt() throws Exception {
    final Fixture f = expense();
    final MvcResult uploaded = upload(f, "alice", "r.jpg", JPEG)
        .andExpect(status().isCreated()).andReturn();
    final String receiptId = JsonPath.read(uploaded.getResponse().getContentAsString(),
        "$.receipts[0].id");

    mockMvc.perform(delete(RECEIPTS + "/{receiptId}", f.familyId(), f.expenseId(), receiptId)
            .with(user("bob")))
        .andExpect(status().isForbidden());
    mockMvc.perform(delete(RECEIPTS + "/{receiptId}", f.familyId(), f.expenseId(), receiptId)
            .with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.receipts", hasSize(0)));
    mockMvc.perform(get(RECEIPTS + "/{receiptId}", f.familyId(), f.expenseId(), receiptId)
            .with(user("alice")))
        .andExpect(status().isNotFound());
    assertThat(Files.exists(STORE.resolve(f.familyId()).resolve(receiptId))).isFalse();
  }

  // ---- helpers -----------------------------------------------------------------------------

  private ResultActions upload(final Fixture f, final String who, final String name,
      final byte[] bytes) throws Exception {
    return mockMvc.perform(multipart(RECEIPTS, f.familyId(), f.expenseId())
        .file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
        .with(user(who)));
  }

  private Fixture expense() throws Exception {
    mockMvc.perform(get("/api/coparent/me").with(user("alice"))).andExpect(status().isOk());
    final MvcResult family = mockMvc.perform(post("/api/coparent/families").with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Example Family","timeZone":"Europe/London","fullName":"Alice Example"}
                """))
        .andExpect(status().isCreated()).andReturn();
    final String familyId = JsonPath.read(family.getResponse().getContentAsString(), "$.id");
    mockMvc.perform(post("/api/coparent/families/{familyId}/invitations", familyId)
            .with(user("alice")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"bob@example.com\",\"role\":\"co-parent\"}"))
        .andExpect(status().isCreated());
    final Invitation invitation = invitations.findAll().getFirst();
    mockMvc.perform(post("/api/coparent/invitations/accept").with(user("bob"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + invitation.token() + "\"}"))
        .andExpect(status().isOk());
    final MvcResult child = mockMvc.perform(post("/api/coparent/families/{familyId}/children",
            familyId).with(user("alice")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"fullName\":\"Robin\",\"dateOfBirth\":\"2018-04-03\"}"))
        .andExpect(status().isCreated()).andReturn();
    final String childId = JsonPath.read(child.getResponse().getContentAsString(), "$.id");
    final String aliceId = parentId(familyId, "alice");
    final String bobId = parentId(familyId, "bob");
    final MvcResult created = mockMvc.perform(post("/api/coparent/families/{familyId}/expenses",
            familyId).with(user("alice")).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"title":"School shoes","category":"clothing","childIds":["%s"],
                 "amountPence":4500,"timing":"paid","date":"%s","payerId":"%s",
                 "shares":[{"parentId":"%s","percent":50},{"parentId":"%s","percent":50}]}
                """.formatted(childId, LocalDate.now().minusDays(1), aliceId, aliceId, bobId)))
        .andExpect(status().isCreated()).andReturn();
    return new Fixture(familyId,
        JsonPath.read(created.getResponse().getContentAsString(), "$.id"), bobId);
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

  private static byte[] jpeg() {
    final byte[] bytes = new byte[64];
    Arrays.fill(bytes, (byte) 0x11);
    bytes[0] = (byte) 0xFF;
    bytes[1] = (byte) 0xD8;
    bytes[2] = (byte) 0xFF;
    return bytes;
  }

  private record Fixture(String familyId, String expenseId, String bobId) {
  }
}
