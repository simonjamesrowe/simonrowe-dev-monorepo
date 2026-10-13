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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.coparent.model.Invitation;
import com.simonrowe.coparent.persistence.InvitationRepository;
import com.simonrowe.migration.changeunits.V043CreateCoparentCollections;
import com.simonrowe.migration.changeunits.V052CreateCoparentExpenses;
import com.simonrowe.migration.changeunits.V055CreateCoparentSettlements;
import java.time.LocalDate;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
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

/** Settling up as bank transfers: payments, the expenses they paid back, and confirming. */
@TestPropertySource(properties = "coparent.enabled=true")
class ExpenseSettlementApiIntegrationTest extends AbstractIntegrationTest {

  private static final String EXPENSES = "/api/coparent/families/{familyId}/expenses";
  private static final String SETTLEMENTS = EXPENSES + "/settlements";

  private static final List<String> COLLECTIONS = List.of(
      V043CreateCoparentCollections.FAMILIES,
      V043CreateCoparentCollections.PARENTS,
      V043CreateCoparentCollections.CHILDREN,
      V043CreateCoparentCollections.INVITATIONS,
      V043CreateCoparentCollections.ONBOARDING,
      V043CreateCoparentCollections.AUDITS,
      V052CreateCoparentExpenses.EXPENSES,
      V055CreateCoparentSettlements.SETTLEMENTS);

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
  void twoBankTransfersPayBackTheChosenExpensesAndThePayeeConfirmsThemTogether()
      throws Exception {
    final Fixture f = family();
    final String shoes = agreedPaidBy(f, "alice", f.aliceId(), 6490);
    final String trip = agreedPaidBy(f, "alice", f.aliceId(), 3000);
    clearInvocations(mailer);

    final MvcResult recorded = record(f, "bob", """
        {"items":[{"id":"%s","version":2},{"id":"%s","version":2}],
         "payments":[
           {"method":"bank_transfer","amountPence":3000,"paidOn":"%s","reference":"KIDS SEPT"},
           {"method":"bank_transfer","amountPence":1745,"paidOn":"%s"}]}
        """.formatted(shoes, trip, yesterday(), yesterday()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settlement.amountPence", is(4745)))
        .andExpect(jsonPath("$.settlement.fromParentId", is(f.bobId())))
        .andExpect(jsonPath("$.settlement.toParentId", is(f.aliceId())))
        .andExpect(jsonPath("$.settlement.payments", hasSize(2)))
        .andExpect(jsonPath("$.settlement.payments[0].reference", is("KIDS SEPT")))
        .andExpect(jsonPath("$.settlement.payments[1].reference", nullValue()))
        .andExpect(jsonPath("$.settlement.expenseIds", hasSize(2)))
        .andExpect(jsonPath("$.results[0].outcome", is("claimed")))
        .andExpect(jsonPath("$.results[1].outcome", is("claimed")))
        .andExpect(jsonPath("$.results[0].expense.reimbursement.note",
            is("Bank transfer (2 payments)")))
        .andReturn();
    final String settlementId = JsonPath.read(recorded.getResponse().getContentAsString(),
        "$.settlement.id");
    mockMvc.perform(get(EXPENSES + "/{id}", f.familyId(), shoes).with(user("alice")))
        .andExpect(jsonPath("$.reimbursement.settlementId", is(settlementId)));
    // One email for the settle-up, not one per expense.
    verify(mailer).settlementRecorded(any(), any(), any(), any());
    verify(mailer, never()).claimed(any(), any(), any());

    mockMvc.perform(get(SETTLEMENTS, f.familyId()).with(user("alice")))
        .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id", is(settlementId)));

    // Only the parent who was paid can say the money arrived.
    confirm(f, "bob", settlementId).andExpect(status().isConflict());
    confirm(f, "alice", settlementId).andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[0].outcome", is("confirmed")))
        .andExpect(jsonPath("$[0].expense.reimbursement.settlementId", is(settlementId)));
    summary(f, "alice").andExpect(jsonPath("$.balance.netPence", is(0)));
  }

  @Test
  void roundNumberSentSettlesTheExpensesAndTheOverpaymentIsOwedBack() throws Exception {
    final Fixture f = family();
    final String shoes = agreedPaidBy(f, "alice", f.aliceId(), 6490);

    final MvcResult recorded = record(f, "bob", """
        {"items":[{"id":"%s","version":2}],
         "payments":[{"method":"bank_transfer","amountPence":3500,"paidOn":"%s"}]}
        """.formatted(shoes, yesterday()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settlement.amountPence", is(3245)))
        .andExpect(jsonPath("$.settlement.paidPence", is(3500)))
        .andExpect(jsonPath("$.results[0].outcome", is("claimed")))
        .andReturn();
    final String settlementId = JsonPath.read(recorded.getResponse().getContentAsString(),
        "$.settlement.id");
    // Until Alice confirms, the expense still counts in full and the extra waits with it.
    summary(f, "alice").andExpect(jsonPath("$.balance.netPence", is(3245)))
        .andExpect(jsonPath("$.balance.carriedPence", is(0)));

    confirm(f, "alice", settlementId).andExpect(status().isOk());
    summary(f, "alice")
        .andExpect(jsonPath("$.balance.netPence", is(255)))
        .andExpect(jsonPath("$.balance.debtorParentId", is(f.aliceId())))
        .andExpect(jsonPath("$.balance.creditorParentId", is(f.bobId())))
        .andExpect(jsonPath("$.balance.carriedPence", is(-255)));
    summary(f, "bob").andExpect(jsonPath("$.balance.carriedPence", is(255)));
  }

  @Test
  void theRecorderCorrectsWhatWasSentAndWhenAndTheBalanceFollows() throws Exception {
    final Fixture f = family();
    final String shoes = agreedPaidBy(f, "alice", f.aliceId(), 6490);
    final MvcResult recorded = record(f, "bob", """
        {"items":[{"id":"%s","version":2}],
         "payments":[{"method":"bank_transfer","amountPence":3245,"paidOn":"%s"}]}
        """.formatted(shoes, yesterday())).andExpect(status().isOk()).andReturn();
    final String settlementId = JsonPath.read(recorded.getResponse().getContentAsString(),
        "$.settlement.id");
    confirm(f, "alice", settlementId).andExpect(status().isOk());
    summary(f, "alice").andExpect(jsonPath("$.balance.netPence", is(0)));
    clearInvocations(mailer);

    final String earlier = LocalDate.now().minusDays(5).toString();
    final String corrected = """
        {"payments":[{"method":"cash","amountPence":3000,"paidOn":"%s","reference":"PART"}]}
        """.formatted(earlier);
    // Only the parent who recorded it can change it.
    update(f, "alice", settlementId, corrected).andExpect(status().isForbidden());
    update(f, "bob", settlementId, corrected)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paidPence", is(3000)))
        .andExpect(jsonPath("$.amountPence", is(3245)))
        .andExpect(jsonPath("$.payments[0].method", is("cash")))
        .andExpect(jsonPath("$.payments[0].paidOn", is(earlier)))
        .andExpect(jsonPath("$.payments[0].reference", is("PART")))
        .andExpect(jsonPath("$.updatedBy", is(f.bobId())))
        .andExpect(jsonPath("$.expenseIds", hasSize(1)));
    verify(mailer).settlementChanged(any(), any(), any(), any());
    // Sent less than the expenses came to: Bob still owes the rest.
    summary(f, "alice")
        .andExpect(jsonPath("$.balance.netPence", is(245)))
        .andExpect(jsonPath("$.balance.creditorParentId", is(f.aliceId())));
    mockMvc.perform(get(EXPENSES + "/{id}", f.familyId(), shoes).with(user("alice")))
        .andExpect(jsonPath("$.reimbursement.status", is("reimbursed")));

    update(f, "bob", settlementId, """
        {"payments":[{"method":"cash","amountPence":3000,"paidOn":"%s"}]}
        """.formatted(LocalDate.now().plusDays(2))).andExpect(status().isBadRequest());
    update(f, "bob", settlementId, "{\"payments\":[]}").andExpect(status().isBadRequest());
    update(f, "bob", new ObjectId().toHexString(), corrected)
        .andExpect(status().isNotFound());
  }

  @Test
  void settleUpThatMovesMoneyNeedsPaymentNotDatedInTheFuture() throws Exception {
    final Fixture f = family();
    final String shoes = agreedPaidBy(f, "alice", f.aliceId(), 6490);

    record(f, "bob", """
        {"items":[{"id":"%s","version":2}],"payments":[]}
        """.formatted(shoes))
        .andExpect(status().isBadRequest());
    record(f, "bob", """
        {"items":[{"id":"%s","version":2}],
         "payments":[{"method":"bank_transfer","amountPence":3245,"paidOn":"%s"}]}
        """.formatted(shoes, LocalDate.now().plusDays(2)))
        .andExpect(status().isBadRequest());

    assertNothingSettled(f, shoes);
  }

  @Test
  void oneStaleExpenseRefusesTheWholeSettleUp() throws Exception {
    final Fixture f = family();
    final String shoes = agreedPaidBy(f, "alice", f.aliceId(), 6490);
    final String trip = agreedPaidBy(f, "alice", f.aliceId(), 3000);

    record(f, "bob", """
        {"items":[{"id":"%s","version":2},{"id":"%s","version":1}],
         "payments":[{"method":"cash","amountPence":4745,"paidOn":"%s"}]}
        """.formatted(shoes, trip, yesterday()))
        .andExpect(status().isConflict());

    assertNothingSettled(f, shoes);
  }

  @Test
  void expensesThatCancelOutAreSettledWithNoPayment() throws Exception {
    final Fixture f = family();
    final String alicePaid = agreedPaidBy(f, "alice", f.aliceId(), 2000);
    final String bobPaid = agreedPaidBy(f, "bob", f.bobId(), 2000);
    final String items = """
        "items":[{"id":"%s","version":2},{"id":"%s","version":2}]"""
        .formatted(alicePaid, bobPaid);

    record(f, "alice", """
        {%s,"payments":[{"method":"cash","amountPence":1,"paidOn":"%s"}]}
        """.formatted(items, yesterday()))
        .andExpect(status().isBadRequest());
    record(f, "alice", "{" + items + "}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settlement.amountPence", is(0)))
        .andExpect(jsonPath("$.settlement.fromParentId", nullValue()))
        .andExpect(jsonPath("$.settlement.payments", hasSize(0)))
        .andExpect(jsonPath("$.results[0].outcome", is("reimbursed")))
        .andExpect(jsonPath("$.results[1].outcome", is("claimed")))
        .andExpect(jsonPath("$.results[0].expense.reimbursement.note",
            is("Settled against each other")));
  }

  @Test
  void refusesPaymentsItCannotRecord() throws Exception {
    final Fixture f = family();
    final String shoes = agreedPaidBy(f, "alice", f.aliceId(), 6490);
    final String item = "{\"id\":\"%s\",\"version\":2}".formatted(shoes);
    final String payment = """
        {"method":"%s","amountPence":%d,"paidOn":"%s","reference":"%s"}""";

    for (final String payments : List.of(
        payment.formatted("cheque", 3245, yesterday(), "x"),
        payment.formatted("cash", 0, yesterday(), "x"),
        payment.formatted("cash", 3245, LocalDate.now().minusYears(3), "x"),
        payment.formatted("cash", 3245, yesterday(), "r".repeat(101)),
        """
            {"method":"cash","amountPence":3245}""",
        String.join(",", java.util.Collections.nCopies(11,
            payment.formatted("cash", 295, yesterday(), "x"))))) {
      record(f, "bob", """
          {"items":[%s],"payments":[%s]}
          """.formatted(item, payments))
          .andExpect(status().isBadRequest());
    }
    record(f, "bob", """
        {"items":[%s,%s],"payments":[%s]}
        """.formatted(item, item, payment.formatted("cash", 3245, yesterday(), "x")))
        .andExpect(status().isBadRequest());
    record(f, "bob", """
        {"items":[{"id":"%s"}],"payments":[]}
        """.formatted(shoes))
        .andExpect(status().isBadRequest());

    assertNothingSettled(f, shoes);
  }

  @Test
  void confirmingRepaymentsAlreadySentRecordsNoNewPayment() throws Exception {
    final Fixture f = family();
    final String shoes = agreedPaidBy(f, "alice", f.aliceId(), 6490);
    mockMvc.perform(post(EXPENSES + "/{id}/reimbursement/claim", f.familyId(), shoes)
            .with(user("bob")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":2,\"note\":\"Bank transfer\"}"))
        .andExpect(status().isOk());

    record(f, "alice", """
        {"items":[{"id":"%s","version":3}],
         "payments":[{"method":"cash","amountPence":3245,"paidOn":"%s"}]}
        """.formatted(shoes, yesterday()))
        .andExpect(status().isBadRequest());
    record(f, "alice", """
        {"items":[{"id":"%s","version":3}]}
        """.formatted(shoes))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settlement", nullValue()))
        .andExpect(jsonPath("$.results[0].outcome", is("confirmed")));
    // Nothing moved now, so nothing in it is waiting on anyone.
    assertThat(mongoTemplate.getCollection(V055CreateCoparentSettlements.SETTLEMENTS)
        .countDocuments()).isZero();
  }

  @Test
  void strangersCannotSeeOrRecordSettleUps() throws Exception {
    final Fixture f = family();
    mockMvc.perform(get(SETTLEMENTS, f.familyId()).with(user("mallory")))
        .andExpect(status().isNotFound());
  }

  // ---- helpers -----------------------------------------------------------------------------

  private void assertNothingSettled(final Fixture f, final String expenseId) throws Exception {
    mockMvc.perform(get(EXPENSES + "/{id}", f.familyId(), expenseId).with(user("alice")))
        .andExpect(jsonPath("$.version", is(2)))
        .andExpect(jsonPath("$.reimbursement.status", is("outstanding")))
        .andExpect(jsonPath("$.reimbursement.settlementId", nullValue()));
    assertThat(mongoTemplate.getCollection(V055CreateCoparentSettlements.SETTLEMENTS)
        .countDocuments()).isZero();
  }

  /** Logged by {@code who}, paid by {@code payerId}, shared 50/50 and agreed: at version 2. */
  private String agreedPaidBy(final Fixture f, final String who, final String payerId,
      final long pence) throws Exception {
    final MvcResult created = mockMvc.perform(post(EXPENSES, f.familyId()).with(user(who))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"title":"School shoes","category":"clothing","childIds":["%s"],
                 "amountPence":%d,"timing":"paid","date":"%s","payerId":"%s",
                 "shares":[{"parentId":"%s","percent":50},{"parentId":"%s","percent":50}]}
                """.formatted(f.childId(), pence, yesterday(), payerId, f.aliceId(),
                f.bobId())))
        .andExpect(status().isCreated()).andReturn();
    final String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    mockMvc.perform(post(EXPENSES + "/{id}/agree", f.familyId(), id)
            .with(user("alice".equals(who) ? "bob" : "alice"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
        .andExpect(status().isOk());
    return id;
  }

  private ResultActions record(final Fixture f, final String who, final String body)
      throws Exception {
    return mockMvc.perform(post(SETTLEMENTS, f.familyId()).with(user(who))
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private ResultActions update(final Fixture f, final String who, final String settlementId,
      final String body) throws Exception {
    return mockMvc.perform(put(SETTLEMENTS + "/{id}", f.familyId(), settlementId)
        .with(user(who)).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private ResultActions confirm(final Fixture f, final String who, final String settlementId)
      throws Exception {
    return mockMvc.perform(post(SETTLEMENTS + "/{id}/confirm", f.familyId(), settlementId)
        .with(user(who)));
  }

  private ResultActions summary(final Fixture f, final String who) throws Exception {
    return mockMvc.perform(get(EXPENSES + "/summary", f.familyId()).with(user(who)))
        .andExpect(status().isOk());
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
