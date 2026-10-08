package com.simonrowe.coparent.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.model.Expense;
import com.simonrowe.coparent.model.Invitation;
import com.simonrowe.coparent.persistence.InvitationRepository;
import com.simonrowe.migration.changeunits.V043CreateCoparentCollections;
import com.simonrowe.migration.changeunits.V052CreateCoparentExpenses;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

/** The expense lifecycle over HTTP, against real MongoDB, from both parents' sides. */
@TestPropertySource(properties = "coparent.enabled=true")
class ExpenseApiIntegrationTest extends AbstractIntegrationTest {

  private static final String EXPENSES = "/api/coparent/families/{familyId}/expenses";
  private static final String EXPENSE = EXPENSES + "/{expenseId}";

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

  @MockitoBean
  private ExpenseMailer mailer;

  @BeforeEach
  @AfterEach
  void cleanCollections() {
    COLLECTIONS.forEach(collection -> mongoTemplate.getCollection(collection)
        .deleteMany(new Document()));
  }

  @Test
  void agreeingThenPayingBackMovesTheBalanceForBothParents() throws Exception {
    final Fixture f = family();
    final String id = create(f, "alice", body(f, "paid", yesterday(), f.aliceId(), 4500, 50));

    summary(f, "bob").andExpect(jsonPath("$.needsYourAgreement.count", is(1)))
        .andExpect(jsonPath("$.needsYourAgreement.totalPence", is(4500)))
        .andExpect(jsonPath("$.needsYourAction", is(1)))
        .andExpect(jsonPath("$.balance.netPence", is(0)));
    summary(f, "alice").andExpect(jsonPath("$.awaitingOther", is(1)));

    action(f, "alice", id, "agree", 1).andExpect(status().isForbidden());
    action(f, "bob", id, "agree", 1).andExpect(status().isOk())
        .andExpect(jsonPath("$.agreement.status", is("agreed")))
        .andExpect(jsonPath("$.reimbursement.status", is("outstanding")))
        .andExpect(jsonPath("$.owedPence", is(2250)))
        .andExpect(jsonPath("$.debtorParentId", is(f.bobId())))
        .andExpect(jsonPath("$.currency", is("GBP")));

    summary(f, "alice").andExpect(jsonPath("$.balance.netPence", is(2250)))
        .andExpect(jsonPath("$.balance.creditorParentId", is(f.aliceId())))
        .andExpect(jsonPath("$.balance.debtorParentId", is(f.bobId())));

    action(f, "alice", id, "reimbursement/claim", 2).andExpect(status().isForbidden());
    action(f, "bob", id, "reimbursement/claim", 2, Map.of("note", "Bank transfer"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reimbursement.status", is("claimed")));
    summary(f, "alice").andExpect(jsonPath("$.balance.awaitingYourConfirmationPence", is(2250)))
        .andExpect(jsonPath("$.needsYourAction", is(1)));

    action(f, "bob", id, "reimbursement/confirm", 3).andExpect(status().isForbidden());
    action(f, "alice", id, "reimbursement/confirm", 3).andExpect(status().isOk())
        .andExpect(jsonPath("$.reimbursement.status", is("reimbursed")))
        .andExpect(jsonPath("$.history", hasSize(4)));
    summary(f, "alice").andExpect(jsonPath("$.balance.netPence", is(0)))
        .andExpect(jsonPath("$.balance.creditorParentId", nullValue()));

    action(f, "bob", id, "agree", 4).andExpect(status().isConflict());
  }

  @Test
  void agreeingToAnOutdatedVersionIsRefused() throws Exception {
    final Fixture f = family();
    final String id = create(f, "alice", body(f, "paid", yesterday(), f.aliceId(), 4500, 50));
    update(f, "alice", id, body(f, "paid", yesterday(), f.aliceId(), 45000, 50), 1)
        .andExpect(status().isOk()).andExpect(jsonPath("$.version", is(2)));

    action(f, "bob", id, "agree", 1).andExpect(status().isConflict())
        .andExpect(jsonPath("$.message", containsString("changed")));
    action(f, "bob", id, "agree", 2).andExpect(status().isOk())
        .andExpect(jsonPath("$.amountPence", is(45000)));
  }

  @Test
  void changingTheTermsOfAnAgreedExpenseAsksTheOtherParentAgain() throws Exception {
    final Fixture f = family();
    final String id = create(f, "alice", body(f, "paid", yesterday(), f.aliceId(), 4500, 50));
    action(f, "bob", id, "agree", 1).andExpect(status().isOk());

    // Bob changes the split: Bob now set the terms, so Alice must agree.
    update(f, "bob", id, body(f, "paid", yesterday(), f.aliceId(), 4500, 30), 2)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agreement.status", is("pending")))
        .andExpect(jsonPath("$.agreement.requestedBy", is(f.bobId())))
        .andExpect(jsonPath("$.reimbursement.status", is("none")));
    action(f, "bob", id, "agree", 3).andExpect(status().isForbidden());
    action(f, "alice", id, "agree", 3).andExpect(status().isOk())
        .andExpect(jsonPath("$.owedPence", is(3150)));
  }

  @Test
  void markingAnUpcomingExpensePaidKeepsTheAgreementOnlyIfTheTermsHold() throws Exception {
    final Fixture f = family();
    final String sameTerms = create(f, "bob",
        body(f, "upcoming", LocalDate.now().plusDays(10).toString(), null, 24000, 50));
    action(f, "alice", sameTerms, "agree", 1).andExpect(status().isOk());
    markPaid(f, "alice", sameTerms, f.bobId(), 24000, 2).andExpect(status().isOk())
        .andExpect(jsonPath("$.timing", is("paid")))
        .andExpect(jsonPath("$.agreement.status", is("agreed")))
        .andExpect(jsonPath("$.reimbursement.status", is("outstanding")))
        .andExpect(jsonPath("$.debtorParentId", is(f.aliceId())));

    final String newTerms = create(f, "bob",
        body(f, "upcoming", LocalDate.now().plusDays(10).toString(), f.bobId(), 24000, 50));
    action(f, "alice", newTerms, "agree", 1).andExpect(status().isOk());
    markPaid(f, "bob", newTerms, f.bobId(), 25000, 2).andExpect(status().isOk())
        .andExpect(jsonPath("$.agreement.status", is("pending")))
        .andExpect(jsonPath("$.agreement.requestedBy", is(f.bobId())));
  }

  @Test
  void anAgreedDebtCannotBeDeletedButUnagreedAndUpcomingOnesCan() throws Exception {
    final Fixture f = family();
    final String agreed = create(f, "alice", body(f, "paid", yesterday(), f.aliceId(), 4500, 50));
    action(f, "bob", agreed, "agree", 1).andExpect(status().isOk());
    remove(f, "alice", agreed, 2).andExpect(status().isConflict());

    final String pending = create(f, "alice", body(f, "paid", yesterday(), f.aliceId(), 900, 50));
    remove(f, "bob", pending, 1).andExpect(status().isForbidden());
    remove(f, "alice", pending, 1).andExpect(status().isNoContent());

    final String upcoming = create(f, "alice",
        body(f, "upcoming", LocalDate.now().plusDays(5).toString(), null, 1800, 50));
    remove(f, "bob", upcoming, 1).andExpect(status().isNoContent());

    mockMvc.perform(get(EXPENSES, f.familyId()).with(user("alice")))
        .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
  }

  @Test
  void disputeNeedsReasonAndTheRequesterCanResendIt() throws Exception {
    final Fixture f = family();
    final String id = create(f, "alice", body(f, "paid", yesterday(), f.aliceId(), 5499, 50));
    action(f, "bob", id, "dispute", 1, Map.of("note", " ")).andExpect(status().isBadRequest());
    action(f, "bob", id, "dispute", 1, Map.of("note", "We said we'd wait"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agreement.status", is("disputed")))
        .andExpect(jsonPath("$.agreement.note", is("We said we'd wait")));
    summary(f, "alice").andExpect(jsonPath("$.needsYourAction", is(1)));

    update(f, "alice", id, body(f, "paid", yesterday(), f.aliceId(), 5499, 50), 2)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agreement.status", is("pending")));
  }

  @Test
  void settleUpPaysBackMarksAndConfirmsEachExpenseIndividually() throws Exception {
    final Fixture f = family();
    final String owedToAlice = create(f, "alice",
        body(f, "paid", yesterday(), f.aliceId(), 6490, 50));
    final String owedToBob = create(f, "bob", body(f, "paid", yesterday(), f.bobId(), 6000, 50));
    action(f, "bob", owedToAlice, "agree", 1).andExpect(status().isOk());
    action(f, "alice", owedToBob, "agree", 1).andExpect(status().isOk());

    mockMvc.perform(post(EXPENSES + "/settle", f.familyId()).with(user("alice"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"items":[{"id":"%s","version":2},{"id":"%s","version":2},
                          {"id":"%s","version":1}]}
                """.formatted(owedToAlice, owedToBob, owedToBob)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].outcome", is("reimbursed")))
        .andExpect(jsonPath("$[1].outcome", is("claimed")))
        // The stale repeat fails on its own without undoing the others.
        .andExpect(jsonPath("$[2].outcome", is("failed")));

    summary(f, "bob").andExpect(jsonPath("$.balance.netPence", is(3000)))
        .andExpect(jsonPath("$.balance.awaitingYourConfirmationPence", is(3000)));
  }

  @Test
  void onlyTheThreeMomentsThatNeedTheOtherParentSendEmail() throws Exception {
    final Fixture f = family();
    final String id = create(f, "alice", body(f, "paid", yesterday(), f.aliceId(), 4500, 50));
    verify(mailer).needsAgreement(any(), any(), any());
    action(f, "bob", id, "agree", 1).andExpect(status().isOk());
    action(f, "bob", id, "reimbursement/claim", 2).andExpect(status().isOk());
    verify(mailer).claimed(any(), any(), any());
    action(f, "alice", id, "reimbursement/confirm", 3).andExpect(status().isOk());

    final String disputed = create(f, "alice",
        body(f, "paid", yesterday(), f.aliceId(), 1000, 50));
    clearInvocations(mailer);
    action(f, "bob", disputed, "dispute", 1, Map.of("note", "Not ours"))
        .andExpect(status().isOk());
    verify(mailer).disputed(any(), any(), any());
    verify(mailer, never()).needsAgreement(any(), any(), any());
    verifyNoMoreInteractions(mailer);
  }

  @Test
  void updateRoundTripsEveryField() throws Exception {
    final Fixture f = family();
    final String id = create(f, "alice", body(f, "paid", yesterday(), f.aliceId(), 4500, 50));
    final String date = LocalDate.now().plusDays(20).toString();
    update(f, "alice", id, """
        {"title":"Ski trip deposit","category":"activities","childIds":["%s"],
         "amountPence":24000,"timing":"upcoming","date":"%s","payerId":"%s",
         "shares":[{"parentId":"%s","percent":70},{"parentId":"%s","percent":30}],
         "notes":"Due before half term"}
        """.formatted(f.childId(), date, f.bobId(), f.aliceId(), f.bobId()), 1)
        .andExpect(status().isOk());

    mockMvc.perform(get(EXPENSE, f.familyId(), id).with(user("bob")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title", is("Ski trip deposit")))
        .andExpect(jsonPath("$.category", is("activities")))
        .andExpect(jsonPath("$.childIds[0]", is(f.childId())))
        .andExpect(jsonPath("$.amountPence", is(24000)))
        .andExpect(jsonPath("$.currency", is("GBP")))
        .andExpect(jsonPath("$.timing", is("upcoming")))
        .andExpect(jsonPath("$.date", is(date)))
        .andExpect(jsonPath("$.payerId", is(f.bobId())))
        .andExpect(jsonPath("$.shares[?(@.parentId=='" + f.aliceId() + "')].percent")
            .value(70))
        .andExpect(jsonPath("$.shares[?(@.parentId=='" + f.bobId() + "')].percent")
            .value(30))
        .andExpect(jsonPath("$.notes", is("Due before half term")))
        .andExpect(jsonPath("$.createdBy", is(f.aliceId())));
  }

  @Test
  void refusesAnythingButPoundsSterlingAndBadTerms() throws Exception {
    final Fixture f = family();
    final String gbp = body(f, "paid", yesterday(), f.aliceId(), 4500, 50);
    send(f, "alice", gbp.replace("\"timing\"", "\"currency\":\"USD\",\"timing\""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message", containsString("pounds sterling")));
    send(f, "alice", body(f, "paid", LocalDate.now().plusDays(3).toString(), f.aliceId(), 4500,
        50)).andExpect(status().isBadRequest());
    send(f, "alice", body(f, "paid", yesterday(), null, 4500, 50))
        .andExpect(status().isBadRequest());
    send(f, "alice", body(f, "paid", yesterday(), f.aliceId(), 4500, 60)
        .replace("\"percent\":40", "\"percent\":50")).andExpect(status().isBadRequest());
    send(f, "alice", body(f, "paid", yesterday(), f.aliceId(), 0, 50))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code", is("validation_failed")));
    send(f, "alice", gbp.replace("\"timing\"", "\"currency\":\"GBP\",\"timing\""))
        .andExpect(status().isCreated());
    assertThat(mongoTemplate.getCollection(Expense.COLLECTION).countDocuments()).isEqualTo(1);
  }

  @Test
  void strangersSeeNothingAndLoneParentsCannotShareCosts() throws Exception {
    final Fixture f = family();
    mockMvc.perform(get(EXPENSES, f.familyId()).with(user("mallory")))
        .andExpect(status().isNotFound());

    mockMvc.perform(get("/api/coparent/me").with(user("carol"))).andExpect(status().isOk());
    final MvcResult solo = mockMvc.perform(post("/api/coparent/families").with(user("carol"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Solo","timeZone":"Europe/London","fullName":"Carol Example"}
                """))
        .andExpect(status().isCreated()).andReturn();
    final String soloId = JsonPath.read(solo.getResponse().getContentAsString(), "$.id");
    mockMvc.perform(post(EXPENSES, soloId).with(user("carol"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(f, "paid", yesterday(), f.aliceId(), 100, 50)))
        .andExpect(status().is4xxClientError());
  }

  // ---- helpers -----------------------------------------------------------------------------

  private String create(final Fixture f, final String who, final String body) throws Exception {
    final MvcResult result = send(f, who, body).andExpect(status().isCreated())
        .andExpect(jsonPath("$.agreement.status", is("pending")))
        .andExpect(jsonPath("$.version", is(1))).andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private ResultActions send(final Fixture f, final String who, final String body)
      throws Exception {
    return mockMvc.perform(post(EXPENSES, f.familyId()).with(user(who))
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private ResultActions update(final Fixture f, final String who, final String id,
      final String body, final long version) throws Exception {
    final String withVersion = body.trim().replaceFirst("\\}$", ",\"version\":" + version + "}");
    return mockMvc.perform(put(EXPENSE, f.familyId(), id).with(user(who))
        .contentType(MediaType.APPLICATION_JSON).content(withVersion));
  }

  private ResultActions action(final Fixture f, final String who, final String id,
      final String path, final long version) throws Exception {
    return action(f, who, id, path, version, Map.of());
  }

  private ResultActions action(final Fixture f, final String who, final String id,
      final String path, final long version, final Map<String, String> extra) throws Exception {
    final StringBuilder json = new StringBuilder("{\"version\":" + version);
    extra.forEach((key, value) -> json.append(",\"").append(key).append("\":\"")
        .append(value).append('"'));
    json.append('}');
    return mockMvc.perform(post(EXPENSE + "/" + path, f.familyId(), id).with(user(who))
        .contentType(MediaType.APPLICATION_JSON).content(json.toString()));
  }

  private ResultActions markPaid(final Fixture f, final String who, final String id,
      final String payerId, final long pence, final long version) throws Exception {
    return mockMvc.perform(post(EXPENSE + "/mark-paid", f.familyId(), id).with(user(who))
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"payerId":"%s","paidOn":"%s","amountPence":%d,"version":%d}
            """.formatted(payerId, yesterday(), pence, version)));
  }

  private ResultActions remove(final Fixture f, final String who, final String id,
      final long version) throws Exception {
    return mockMvc.perform(delete(EXPENSE, f.familyId(), id).param("version",
        String.valueOf(version)).with(user(who)));
  }

  private ResultActions summary(final Fixture f, final String who) throws Exception {
    return mockMvc.perform(get(EXPENSES + "/summary", f.familyId()).with(user(who)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.currency", is("GBP")));
  }

  private static String body(final Fixture f, final String timing, final String date,
      final String payerId, final long pence, final int alicePercent) {
    return """
        {"title":"School shoes","category":"clothing","childIds":["%s"],"amountPence":%d,
         "timing":"%s","date":"%s",%s
         "shares":[{"parentId":"%s","percent":%d},{"parentId":"%s","percent":%d}],
         "notes":"Clarks"}
        """.formatted(f.childId(), pence, timing, date,
        payerId == null ? "" : "\"payerId\":\"" + payerId + "\",",
        f.aliceId(), alicePercent, f.bobId(), 100 - alicePercent);
  }

  private static String yesterday() {
    return LocalDate.now().minusDays(1).toString();
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
    final String familyId = JsonPath.read(familyResult.getResponse().getContentAsString(), "$.id");
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
    clearInvocations(mailer);
    return new Fixture(familyId,
        JsonPath.read(childResult.getResponse().getContentAsString(), "$.id"),
        parentId(familyId, "alice"), parentId(familyId, "bob"));
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
