package com.simonrowe.coparent.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.expense.ExpenseMailer;
import com.simonrowe.coparent.model.Invitation;
import com.simonrowe.coparent.model.StatementTransaction;
import com.simonrowe.coparent.persistence.InvitationRepository;
import com.simonrowe.migration.changeunits.V043CreateCoparentCollections;
import com.simonrowe.migration.changeunits.V052CreateCoparentExpenses;
import com.simonrowe.migration.changeunits.V054CreateCoparentStatements;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import net.minidev.json.JSONArray;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Statement upload, checking and logging over HTTP, against real MongoDB. */
@TestPropertySource(properties = {"coparent.enabled=true", "coparent.statements.ai-enabled=true"})
class StatementApiIntegrationTest extends AbstractIntegrationTest {

  private static final String STATEMENTS = "/api/coparent/families/{familyId}/statements";

  private static final List<String> COLLECTIONS = List.of(
      V043CreateCoparentCollections.FAMILIES,
      V043CreateCoparentCollections.PARENTS,
      V043CreateCoparentCollections.CHILDREN,
      V043CreateCoparentCollections.INVITATIONS,
      V043CreateCoparentCollections.ONBOARDING,
      V043CreateCoparentCollections.AUDITS,
      V052CreateCoparentExpenses.EXPENSES,
      V054CreateCoparentStatements.TRANSACTIONS,
      V054CreateCoparentStatements.UPLOADS);

  @Autowired
  @Qualifier("coparentMongoTemplate")
  private MongoTemplate mongoTemplate;

  @Autowired
  private InvitationRepository invitations;

  @MockitoBean
  private ExpenseMailer mailer;

  @MockitoBean
  private StatementClassifier classifier;

  @BeforeEach
  void setUp() {
    cleanCollections();
    V054CreateCoparentStatements.createIndexes(mongoTemplate);
    // The class collection looks shared; everything else does not.
    when(classifier.classify(any(), anyList())).thenAnswer(invocation -> {
      final List<StatementClassifier.Item> items = invocation.getArgument(1);
      return items.stream().map(item -> item.description().startsWith("Collctiv")
          ? new StatementClassifier.Verdict(true, "medium", "Class gift", "education",
              List.of(), "A class collection")
          : StatementClassifier.Verdict.notShared()).toList();
    });
  }

  @AfterEach
  void cleanCollections() {
    COLLECTIONS.forEach(collection -> mongoTemplate.getCollection(collection)
        .deleteMany(new Document()));
  }

  @Test
  void uploadingTheSameStatementTwiceAddsNothing() throws Exception {
    final Fixture f = family();
    final MvcResult first = upload(f, "alice", "amex.csv").andExpect(status().isCreated())
        .andExpect(jsonPath("$.format", is("AMEX")))
        .andExpect(jsonPath("$.moneyIn", is(1)))
        .andExpect(jsonPath("$.rows", hasSize(4)))
        .andExpect(jsonPath("$.rows[0].state", nullValue()))
        .andExpect(jsonPath("$.upload.newCount", is(4)))
        .andReturn();
    final String uploadId = read(first, "$.upload.id");
    final List<Map<String, Object>> rows = JsonPath.read(body(first), "$.rows");

    check(f, "alice", uploadId, rows).andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(4)))
        .andExpect(jsonPath("$[?(@.state=='suggested')]", hasSize(1)));
    // Sending the same batch again, as a retry would, asks the model nothing new.
    check(f, "alice", uploadId, rows).andExpect(status().isOk());
    verify(classifier, times(1)).classify(any(), anyList());

    upload(f, "alice", "amex.csv").andExpect(status().isCreated())
        .andExpect(jsonPath("$.upload", nullValue()))
        .andExpect(jsonPath("$.rows[?(@.state=='suggested')]", hasSize(1)))
        .andExpect(jsonPath("$.rows[?(@.state=='checked')]", hasSize(3)));
    assertThat(mongoTemplate.getCollection(StatementTransaction.COLLECTION).countDocuments())
        .isEqualTo(4);

    overview(f, "alice").andExpect(jsonPath("$.aiEnabled", is(true)))
        .andExpect(jsonPath("$.toReview", is(1)))
        .andExpect(jsonPath("$.uploads", hasSize(1)))
        .andExpect(jsonPath("$.uploads[0].checkedCount", is(4)))
        .andExpect(jsonPath("$.uploads[0].suggestedCount", is(1)))
        .andExpect(jsonPath("$.uploads[0].status", is("done")));
  }

  @Test
  void onlyTheParentWhoUploadedCanSeeOrTouchIt() throws Exception {
    final Fixture f = family();
    final String uploadId = uploadAndCheck(f, "amex.csv");
    final String suggestionId = suggestions(f, "alice").getFirst();

    overview(f, "bob").andExpect(jsonPath("$.toReview", is(0)))
        .andExpect(jsonPath("$.uploads", hasSize(0)));
    mockMvc.perform(get(STATEMENTS + "/transactions", f.familyId()).with(user("bob")))
        .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    mockMvc.perform(post(STATEMENTS + "/transactions/{id}/dismiss", f.familyId(), suggestionId)
        .with(user("bob"))).andExpect(status().isNotFound());
    check(f, "bob", uploadId, List.of(row("a".repeat(64))))
        .andExpect(status().isNotFound());
    mockMvc.perform(get(STATEMENTS, f.familyId()).with(user("mallory")))
        .andExpect(status().isNotFound());
  }

  @Test
  void suggestionBecomesAnExpenseEitherParentPaidForOnce() throws Exception {
    final Fixture f = family();
    uploadAndCheck(f, "amex.csv");
    final MvcResult listed = mockMvc.perform(get(STATEMENTS + "/transactions", f.familyId())
            .with(user("alice")))
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].description", is("Collctiv Class Gift Example")))
        .andExpect(jsonPath("$[0].amountPence", is(3000)))
        .andExpect(jsonPath("$[0].suggestion.title", is("Class gift")))
        .andReturn();
    final String fingerprint = read(listed, "$[0].fingerprint");

    // A joint account: Bob paid, though Alice uploaded the statement.
    convert(f, "alice", fingerprint, null, f.bobId(), 3000).andExpect(status().isCreated())
        .andExpect(jsonPath("$.payerId", is(f.bobId())))
        .andExpect(jsonPath("$.timing", is("paid")))
        .andExpect(jsonPath("$.agreement.status", is("pending")))
        .andExpect(jsonPath("$.agreement.requestedBy", is(f.aliceId())));
    convert(f, "alice", fingerprint, null, f.aliceId(), 3000).andExpect(status().isConflict());

    final Document logged = mongoTemplate.getCollection(StatementTransaction.COLLECTION)
        .find(new Document("fingerprint", fingerprint)).first();
    assertThat(logged.getString("status")).isEqualTo(StatementTransaction.LOGGED);
    assertThat(logged.get("pending")).isNull();
    assertThat(logged.getString("merchant")).isEqualTo("Collctiv Class Gift Example");
    assertThat(logged.getString("category")).isEqualTo("education");

    upload(f, "alice", "amex.csv")
        .andExpect(jsonPath("$.rows[?(@.state=='logged')]", hasSize(1)));
    overview(f, "alice").andExpect(jsonPath("$.toReview", is(0)))
        .andExpect(jsonPath("$.logged", is(1)));
  }

  @Test
  void anyRowOfAnUploadCanBeLoggedAndThenWarnsOfTheMatch() throws Exception {
    final Fixture f = family();
    final MvcResult uploaded = upload(f, "alice", "starling.csv")
        .andExpect(status().isCreated()).andReturn();
    final Map<String, Object> uniform = JsonPath.<JSONArray>read(body(uploaded),
        "$.rows[?(@.description=='Example School Shop')]").stream()
        .map(entry -> (Map<String, Object>) entry).findFirst().orElseThrow();

    convert(f, "alice", (String) uniform.get("fingerprint"), uniform, f.aliceId(), 4200)
        .andExpect(status().isCreated());

    // Bob uploads the same joint account statement: his rows warn that it is already an expense.
    final MvcResult bobs = upload(f, "bob", "starling.csv").andReturn();
    final List<String> matches = JsonPath.read(body(bobs),
        "$.rows[?(@.description=='Example School Shop')].match.title");
    assertThat(matches).containsExactly("School shoes");
  }

  @Test
  void failedExpenseLeavesTheSuggestionWaiting() throws Exception {
    final Fixture f = family();
    uploadAndCheck(f, "amex.csv");
    final MvcResult listed = mockMvc.perform(get(STATEMENTS + "/transactions", f.familyId())
        .with(user("alice"))).andReturn();
    final String fingerprint = read(listed, "$[0].fingerprint");

    mockMvc.perform(post(STATEMENTS + "/expenses", f.familyId()).with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(convertBody(fingerprint, null, f, f.aliceId(), 3000)
                .replace(f.childId(), "ffffffffffffffffffffffff")))
        .andExpect(status().isBadRequest());

    overview(f, "alice").andExpect(jsonPath("$.toReview", is(1)))
        .andExpect(jsonPath("$.logged", is(0)));
  }

  @Test
  void dismissingKeepsOnlyTheMerchantAndForgettingRemovesIt() throws Exception {
    final Fixture f = family();
    uploadAndCheck(f, "amex.csv");
    final String id = suggestions(f, "alice").getFirst();

    mockMvc.perform(post(STATEMENTS + "/transactions/{id}/dismiss", f.familyId(), id)
            .with(user("alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("dismissed")))
        .andExpect(jsonPath("$.amountPence", nullValue()))
        .andExpect(jsonPath("$.merchant", is("Collctiv Class Gift Example")));
    mockMvc.perform(get(STATEMENTS + "/transactions", f.familyId()).param("status", "dismissed")
        .with(user("alice"))).andExpect(jsonPath("$", hasSize(1)));

    mockMvc.perform(delete(STATEMENTS + "/transactions/{id}", f.familyId(), id)
        .with(user("alice"))).andExpect(status().isNoContent());
    overview(f, "alice").andExpect(jsonPath("$.dismissed", is(0)));
    // Forgotten, so the next upload of that statement checks it afresh.
    upload(f, "alice", "amex.csv").andExpect(jsonPath("$.upload.newCount", is(1)));
  }

  @Test
  void refusesFilesThatAreNotStatements() throws Exception {
    final Fixture f = family();
    mockMvc.perform(multipart(STATEMENTS, f.familyId())
            .file(new MockMultipartFile("file", "notes.csv", "text/csv",
                "name,email\nRobin,robin@example.com\n".getBytes()))
            .with(user("alice")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("Starling, Monzo")));
  }

  // ---- helpers -----------------------------------------------------------------------------

  private String uploadAndCheck(final Fixture f, final String file) throws Exception {
    final MvcResult uploaded = upload(f, "alice", file).andReturn();
    final String uploadId = read(uploaded, "$.upload.id");
    check(f, "alice", uploadId, JsonPath.read(body(uploaded), "$.rows"))
        .andExpect(status().isOk());
    clearInvocations(classifier);
    return uploadId;
  }

  private List<String> suggestions(final Fixture f, final String who) throws Exception {
    return JsonPath.read(body(mockMvc.perform(get(STATEMENTS + "/transactions", f.familyId())
        .with(user(who))).andReturn()), "$[*].id");
  }

  private ResultActions upload(final Fixture f, final String who, final String file)
      throws Exception {
    try (InputStream input = getClass().getResourceAsStream("/coparent/statements/" + file)) {
      return mockMvc.perform(multipart(STATEMENTS, f.familyId())
          .file(new MockMultipartFile("file", file, "text/plain", input.readAllBytes()))
          .with(user(who)));
    }
  }

  private ResultActions check(final Fixture f, final String who, final String uploadId,
      final List<Map<String, Object>> rows) throws Exception {
    return mockMvc.perform(post(STATEMENTS + "/uploads/{uploadId}/check", f.familyId(), uploadId)
        .with(user(who)).contentType(MediaType.APPLICATION_JSON)
        .content(new tools.jackson.databind.ObjectMapper()
            .writeValueAsString(Map.of("rows", rows))));
  }

  private ResultActions convert(final Fixture f, final String who, final String fingerprint,
      final Map<String, Object> row, final String payerId, final long pence) throws Exception {
    return mockMvc.perform(post(STATEMENTS + "/expenses", f.familyId()).with(user(who))
        .contentType(MediaType.APPLICATION_JSON)
        .content(convertBody(fingerprint, row, f, payerId, pence)));
  }

  private static String convertBody(final String fingerprint, final Map<String, Object> row,
      final Fixture f, final String payerId, final long pence) throws Exception {
    final String transaction = row == null ? "null"
        : new tools.jackson.databind.ObjectMapper().writeValueAsString(row);
    return """
        {"fingerprint":"%s","transaction":%s,
         "expense":{"title":"School shoes","category":"education","childIds":["%s"],
          "amountPence":%d,"timing":"paid","date":"%s","payerId":"%s",
          "shares":[{"parentId":"%s","percent":50},{"parentId":"%s","percent":50}]}}
        """.formatted(fingerprint, transaction, f.childId(), pence,
        row == null ? "2026-09-02" : row.get("date"), payerId, f.aliceId(), f.bobId());
  }

  private static Map<String, Object> row(final String fingerprint) {
    return Map.of("fingerprint", fingerprint, "date", "2026-09-02", "description", "SHOP",
        "details", "", "amountPence", 100, "account", "American Express");
  }

  private ResultActions overview(final Fixture f, final String who) throws Exception {
    return mockMvc.perform(get(STATEMENTS, f.familyId()).with(user(who)))
        .andExpect(status().isOk());
  }

  private static String body(final MvcResult result) throws Exception {
    return result.getResponse().getContentAsString();
  }

  private static String read(final MvcResult result, final String path) throws Exception {
    return JsonPath.read(body(result), path);
  }

  private Fixture family() throws Exception {
    mockMvc.perform(get("/api/coparent/me").with(user("alice"))).andExpect(status().isOk());
    final MvcResult familyResult = mockMvc.perform(post("/api/coparent/families")
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Example Family","timeZone":"Europe/London","fullName":"Alice Example"}
                """))
        .andExpect(status().isCreated()).andReturn();
    final String familyId = read(familyResult, "$.id");
    mockMvc.perform(post("/api/coparent/families/{familyId}/invitations", familyId)
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"bob@example.com\",\"role\":\"co-parent\"}"))
        .andExpect(status().isCreated());
    final Invitation invitation = invitations.findAll().getFirst();
    mockMvc.perform(post("/api/coparent/invitations/accept")
            .with(user("bob"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + invitation.token() + "\"}"))
        .andExpect(status().isOk());
    final MvcResult childResult = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/children", familyId)
            .with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"fullName\":\"Robin\",\"dateOfBirth\":\"2018-04-03\"}"))
        .andExpect(status().isCreated()).andReturn();
    return new Fixture(familyId, read(childResult, "$.id"), parentId(familyId, "alice"),
        parentId(familyId, "bob"));
  }

  private String parentId(final String familyId, final String who) throws Exception {
    final List<String> ids = JsonPath.read(body(mockMvc.perform(get("/api/coparent/me")
            .with(user(who))).andExpect(status().isOk()).andReturn()),
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
