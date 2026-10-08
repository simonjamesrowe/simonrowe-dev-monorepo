package com.simonrowe.coparent.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.model.Invitation;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.InvitationRepository;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.migration.changeunits.V043CreateCoparentCollections;
import com.simonrowe.migration.changeunits.V052CreateCoparentExpenses;
import com.simonrowe.migration.changeunits.V053ReserveInvitedCoparents;
import java.time.LocalDate;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Shared expenses before the co-parent accepts their invitation: logged against the parent the
 * invitation reserved, waiting for them, and theirs, under the same ids, once they join.
 */
@TestPropertySource(properties = "coparent.enabled=true")
class InvitedCoparentExpenseIntegrationTest extends AbstractIntegrationTest {

  private static final String EXPENSES = "/api/coparent/families/{familyId}/expenses";
  private static final String PARENTS = "/api/coparent/families/{familyId}/parents";

  private static final List<String> COLLECTIONS = List.of(
      V043CreateCoparentCollections.FAMILIES,
      V043CreateCoparentCollections.PARENTS,
      V043CreateCoparentCollections.CHILDREN,
      V043CreateCoparentCollections.INVITATIONS,
      V043CreateCoparentCollections.ONBOARDING,
      V043CreateCoparentCollections.AUDITS,
      V043CreateCoparentCollections.CONVERSATIONS,
      V052CreateCoparentExpenses.EXPENSES);

  @Autowired
  @Qualifier("coparentMongoTemplate")
  private MongoTemplate mongoTemplate;

  @Autowired
  private InvitationRepository invitations;

  @Autowired
  private ParentRepository parents;

  @MockitoBean
  private ExpenseMailer mailer;

  @BeforeEach
  @AfterEach
  void cleanCollections() {
    COLLECTIONS.forEach(collection -> mongoTemplate.getCollection(collection)
        .deleteMany(new Document()));
    V053ReserveInvitedCoparents.createIndexes(mongoTemplate);
  }

  @Test
  void expensesLoggedBeforeTheInviteIsAcceptedBecomeTheCoparentsWhenTheyJoin() throws Exception {
    final Fixture f = familyWithInvite("Rhian");

    mockMvc.perform(get(PARENTS, f.familyId()).with(user("alice")))
        .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    mockMvc.perform(get(PARENTS, f.familyId()).param("includeInvited", "true")
            .with(user("alice")))
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[?(@.id=='" + f.invitedId() + "')].status").value("invited"))
        .andExpect(jsonPath("$[?(@.id=='" + f.invitedId() + "')].fullName").value("Rhian"));

    final String alicePaid = create(f, body(f, f.aliceId(), 4500));
    final String rhianPaid = create(f, body(f, f.invitedId(), 3000));
    summary(f, "alice").andExpect(jsonPath("$.awaitingOther", is(2)))
        .andExpect(jsonPath("$.balance.netPence", is(0)));

    // Rhian signs in before accepting, so she has a profile of her own to merge.
    mockMvc.perform(post("/api/coparent/me").with(user("rhian"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Rhian Jones\"}"))
        .andExpect(status().is2xxSuccessful());
    accept("rhian");

    final MvcResult me = mockMvc.perform(get("/api/coparent/me").with(user("rhian")))
        .andExpect(status().isOk()).andReturn();
    final String meJson = me.getResponse().getContentAsString();
    assertThat(JsonPath.<List<Object>>read(meJson, "$.profiles")).hasSize(1);
    assertThat(JsonPath.<String>read(meJson, "$.profiles[0].id")).isEqualTo(f.invitedId());
    assertThat(JsonPath.<String>read(meJson, "$.profiles[0].fullName")).isEqualTo("Rhian Jones");
    assertThat(JsonPath.<String>read(meJson, "$.profiles[0].status")).isEqualTo("active");

    summary(f, "rhian").andExpect(jsonPath("$.needsYourAgreement.count", is(2)));
    agree(f, "rhian", alicePaid).andExpect(status().isOk())
        .andExpect(jsonPath("$.debtorParentId", is(f.invitedId())));
    agree(f, "rhian", rhianPaid).andExpect(status().isOk());
    summary(f, "alice").andExpect(jsonPath("$.balance.netPence", is(750)))
        .andExpect(jsonPath("$.balance.creditorParentId", is(f.aliceId())));
  }

  @Test
  void theInvitedParentCannotAgreeToAnything() throws Exception {
    final Fixture f = familyWithInvite(null);
    final String id = create(f, body(f, f.aliceId(), 4500));

    // Alice set the terms, so she cannot agree; nobody else can sign in as the invited parent.
    agree(f, "alice", id).andExpect(status().isForbidden());
    agree(f, "rhian", id).andExpect(status().isNotFound());
  }

  @Test
  void namelessInvitesAreNamedFromTheEmailAndRenamedOnlyUntilTheyJoin() throws Exception {
    final Fixture f = familyWithInvite(null);
    assertThat(parents.findById(new org.bson.types.ObjectId(f.invitedId())).orElseThrow()
        .fullName()).isEqualTo("Rhian");

    rename(f.invitedId(), "bob", "Nope").andExpect(status().isNotFound());
    rename(f.invitedId(), "alice", "Rhian J").andExpect(status().isOk())
        .andExpect(jsonPath("$.fullName", is("Rhian J")));

    accept("rhian");
    rename(f.invitedId(), "alice", "Too late").andExpect(status().isConflict());
  }

  @Test
  void cancellingKeepsTheExpensesAndInvitingTheSameEmailAgainReusesTheParent() throws Exception {
    final Fixture f = familyWithInvite("Rhian");
    final String id = create(f, body(f, f.aliceId(), 4500));

    mockMvc.perform(post("/api/coparent/invitations/{id}/cancel", invitation().id().toHexString())
            .with(user("alice")))
        .andExpect(status().isOk());
    mockMvc.perform(get(PARENTS, f.familyId()).param("includeInvited", "true")
            .with(user("alice")))
        .andExpect(jsonPath("$", hasSize(1)));
    send(f, body(f, f.aliceId(), 1000)).andExpect(status().isConflict())
        .andExpect(jsonPath("$.message", containsString("Invite your co-parent")));
    mockMvc.perform(get(EXPENSES + "/{id}", f.familyId(), id).with(user("alice")))
        .andExpect(status().isOk());

    invite(f.familyId(), null);
    final List<Parent> reserved = parents.findByFamilyIdAndStatusIn(
        new org.bson.types.ObjectId(f.familyId()), List.of("invited", "uninvited"));
    assertThat(reserved).singleElement()
        .satisfies(parent -> {
          assertThat(parent.id().toHexString()).isEqualTo(f.invitedId());
          assertThat(parent.status()).isEqualTo("invited");
          assertThat(parent.fullName()).isEqualTo("Rhian");
        });

    accept("rhian");
    agree(f, "rhian", id).andExpect(status().isOk());
  }

  @Test
  void familiesWithTwoActiveParentsIgnoreOpenInvitations() throws Exception {
    final Fixture f = familyWithInvite("Rhian");
    accept("rhian");
    invite(f.familyId(), "carol@example.com", "Carol");

    final String id = create(f, body(f, f.aliceId(), 4500));
    agree(f, "rhian", id).andExpect(status().isOk());
  }

  @Test
  void invitedParentsAreNotMembersOfTheFamily() throws Exception {
    final Fixture f = familyWithInvite("Rhian");

    mockMvc.perform(get("/api/coparent/me").with(user("alice")))
        .andExpect(jsonPath("$.profiles", hasSize(1)));
    mockMvc.perform(get("/api/coparent/families/{familyId}/conversations", f.familyId())
            .with(user("rhian")))
        .andExpect(status().isNotFound());
  }

  // ---- helpers -----------------------------------------------------------------------------

  private Fixture familyWithInvite(final String name) throws Exception {
    mockMvc.perform(get("/api/coparent/me").with(user("alice"))).andExpect(status().isOk());
    final MvcResult familyResult = mockMvc.perform(post("/api/coparent/families")
            .with(user("alice")).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Example Family","timeZone":"Europe/London","fullName":"Alice Example"}
                """))
        .andExpect(status().isCreated()).andReturn();
    final String familyId = JsonPath.read(familyResult.getResponse().getContentAsString(), "$.id");
    invite(familyId, name);
    final MvcResult childResult = mockMvc.perform(post(
            "/api/coparent/families/{familyId}/children", familyId)
            .with(user("alice")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"fullName\":\"Robin\",\"dateOfBirth\":\"2018-04-03\"}"))
        .andExpect(status().isCreated()).andReturn();
    final MvcResult listed = mockMvc.perform(get(PARENTS, familyId)
            .param("includeInvited", "true").with(user("alice")))
        .andExpect(status().isOk()).andReturn();
    final String json = listed.getResponse().getContentAsString();
    final String aliceId = JsonPath.<List<String>>read(json, "$[?(@.status=='active')].id")
        .getFirst();
    final String invitedId = JsonPath.<List<String>>read(json, "$[?(@.status=='invited')].id")
        .getFirst();
    return new Fixture(familyId,
        JsonPath.read(childResult.getResponse().getContentAsString(), "$.id"), aliceId,
        invitedId);
  }

  private void invite(final String familyId, final String name) throws Exception {
    invite(familyId, "rhian@example.com", name);
  }

  private void invite(final String familyId, final String email, final String name)
      throws Exception {
    mockMvc.perform(post("/api/coparent/families/{familyId}/invitations", familyId)
            .with(user("alice")).contentType(MediaType.APPLICATION_JSON)
            .content(name == null
                ? "{\"email\":\"%s\",\"role\":\"co-parent\"}".formatted(email)
                : "{\"email\":\"%s\",\"role\":\"co-parent\",\"name\":\"%s\"}"
                    .formatted(email, name)))
        .andExpect(status().isCreated());
  }

  private Invitation invitation() {
    return invitations.findAll().stream()
        .filter(candidate -> "rhian@example.com".equals(candidate.email()))
        .filter(candidate -> !"canceled".equals(candidate.status()))
        .findFirst().orElseThrow();
  }

  private void accept(final String who) throws Exception {
    mockMvc.perform(post("/api/coparent/invitations/accept").with(user(who))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + invitation().token() + "\"}"))
        .andExpect(status().isOk());
  }

  private ResultActions rename(final String parentId, final String who, final String name)
      throws Exception {
    return mockMvc.perform(patch("/api/coparent/parents/{id}/invited-name", parentId)
        .with(user(who)).contentType(MediaType.APPLICATION_JSON)
        .content("{\"fullName\":\"" + name + "\"}"));
  }

  private String create(final Fixture f, final String body) throws Exception {
    final MvcResult result = send(f, body).andExpect(status().isCreated())
        .andExpect(jsonPath("$.agreement.status", is("pending")))
        .andExpect(jsonPath("$.agreement.requestedBy", is(f.aliceId()))).andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private ResultActions send(final Fixture f, final String body) throws Exception {
    return mockMvc.perform(post(EXPENSES, f.familyId()).with(user("alice"))
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private ResultActions agree(final Fixture f, final String who, final String id)
      throws Exception {
    final MvcResult current = mockMvc.perform(get(EXPENSES + "/{id}", f.familyId(), id)
        .with(user("alice"))).andReturn();
    final int version = JsonPath.read(current.getResponse().getContentAsString(), "$.version");
    return mockMvc.perform(post(EXPENSES + "/{id}/agree", f.familyId(), id).with(user(who))
        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"));
  }

  private ResultActions summary(final Fixture f, final String who) throws Exception {
    return mockMvc.perform(get(EXPENSES + "/summary", f.familyId()).with(user(who)))
        .andExpect(status().isOk());
  }

  private static String body(final Fixture f, final String payerId, final long pence) {
    return """
        {"title":"School shoes","category":"clothing","childIds":["%s"],"amountPence":%d,
         "timing":"paid","date":"%s","payerId":"%s",
         "shares":[{"parentId":"%s","percent":50},{"parentId":"%s","percent":50}]}
        """.formatted(f.childId(), pence, LocalDate.now().minusDays(1), payerId,
        f.aliceId(), f.invitedId());
  }

  private JwtRequestPostProcessor user(final String subject) {
    return jwt().jwt(token -> token.subject("auth0|" + subject)
        .claim("https://coparents.simonrowe.dev/email", subject + "@example.com"));
  }

  private record Fixture(String familyId, String childId, String aliceId, String invitedId) {
  }
}
