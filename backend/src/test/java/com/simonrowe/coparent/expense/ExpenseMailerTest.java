package com.simonrowe.coparent.expense;

import static com.simonrowe.coparent.expense.ExpenseFixtures.ALEX;
import static com.simonrowe.coparent.expense.ExpenseFixtures.SAM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.Expense;
import com.simonrowe.coparent.model.ExpenseSettlement;
import com.simonrowe.coparent.model.Parent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class ExpenseMailerTest {

  private static final Parent ALEX_PARENT = parent(ALEX, "Alex Rowe", "alex@example.com");
  private static final Parent SAM_PARENT = parent(SAM, "Sam Taylor", "sam@example.com");

  private final JavaMailSender sender = mock(JavaMailSender.class);

  @Test
  void asksTheOtherParentToAgreeInPoundsWithTheirShare() {
    final Expense expense = ExpenseFixtures.paid(4500, ALEX, 50, Expense.PENDING, Expense.NONE);

    mailer(true).needsAgreement(SAM_PARENT, ALEX_PARENT, expense);

    final SimpleMailMessage message = sent();
    assertThat(message.getTo()).containsExactly("sam@example.com");
    assertThat(message.getSubject()).isEqualTo("Alex added an expense: Item");
    assertThat(message.getText()).contains("£45.00", "Your share is £22.50",
        "https://coparents.example/expenses?expense=" + expense.id().toHexString());
    assertThat(message.getText()).doesNotContain("$");
  }

  @Test
  void severalAddedTogetherGoInOneEmailWithEachLineAndTheTotalShare() {
    mailer(true).needsAgreementToAll(SAM_PARENT, ALEX_PARENT, List.of(
        ExpenseFixtures.paid(1250, ALEX, 50, Expense.PENDING, Expense.NONE),
        ExpenseFixtures.paid(2500, ALEX, 50, Expense.PENDING, Expense.NONE)));

    final SimpleMailMessage message = sent();
    assertThat(message.getSubject()).isEqualTo("Alex added 2 expenses: Item");
    assertThat(message.getText()).contains(
        "- Item, 3 Oct 2026: £12.50 (your share £6.25)",
        "- Item, 3 Oct 2026: £25.00 (your share £12.50)",
        "Your share of them is £18.75.",
        "https://coparents.example/expenses");
  }

  @Test
  void claimTellsThePayerTheAmountOwed() {
    final Expense expense = ExpenseFixtures.paid(6490, ALEX, 50, Expense.AGREED, Expense.CLAIMED);

    mailer(true).claimed(ALEX_PARENT, SAM_PARENT, expense);

    assertThat(sent().getText()).contains("Sam says they paid you back £32.45");
  }

  @Test
  void settleUpIsOneEmailListingEachPaymentAndWhatItPaysBack() {
    final Expense shoes = ExpenseFixtures.paid(6490, ALEX, 50, Expense.AGREED, Expense.CLAIMED);
    final Expense trip = ExpenseFixtures.paid(3000, ALEX, 50, Expense.AGREED, Expense.CLAIMED);
    final ExpenseSettlement settlement = new ExpenseSettlement(new ObjectId(),
        ExpenseFixtures.FAMILY, SAM, ALEX, 4745, Expense.GBP, List.of(
            new ExpenseSettlement.Payment(ExpenseSettlement.BANK_TRANSFER, 3000,
                LocalDate.of(2026, 10, 6), "KIDS SEPT"),
            new ExpenseSettlement.Payment(ExpenseSettlement.CASH, 1000,
                LocalDate.of(2026, 10, 7), null),
            new ExpenseSettlement.Payment(ExpenseSettlement.OTHER, 745,
                LocalDate.of(2026, 10, 8), null)),
        List.of(shoes.id(), trip.id()), SAM, Instant.parse("2026-10-08T09:00:00Z"), null, null);

    mailer(true).settlementRecorded(ALEX_PARENT, SAM_PARENT, settlement, List.of(shoes, trip));

    final SimpleMailMessage message = sent();
    assertThat(message.getTo()).containsExactly("alex@example.com");
    assertThat(message.getSubject()).isEqualTo("Sam says they paid you £47.45");
    assertThat(message.getText()).contains(
        "- Bank transfer, 6 Oct 2026: £30.00 (ref KIDS SEPT)",
        "- Cash, 7 Oct 2026: £10.00",
        "- Other payment, 8 Oct 2026: £7.45",
        "- Item: £32.45",
        "- Item: £15.00",
        "https://coparents.example/expenses?view=transfers");
  }

  @Test
  void settleUpThatPaidMoreSaysTheDifferenceIsOwedBack() {
    final Expense shoes = ExpenseFixtures.paid(6490, ALEX, 50, Expense.AGREED, Expense.CLAIMED);
    final ExpenseSettlement settlement = settlement(3245, 3500, LocalDate.of(2026, 10, 6));

    mailer(true).settlementRecorded(ALEX_PARENT, SAM_PARENT, settlement, List.of(shoes));

    final SimpleMailMessage message = sent();
    assertThat(message.getSubject()).isEqualTo("Sam says they paid you £35.00");
    assertThat(message.getText()).contains(
        "That is £2.55 more than the expenses came to (£32.45), so Alex owes £2.55 back.");
  }

  @Test
  void correctingSettleUpTellsTheOtherParentWhatItNowRecords() {
    final ExpenseSettlement before = settlement(3245, 3245, LocalDate.of(2026, 10, 6));
    final ExpenseSettlement after = before.withPayments(List.of(
        new ExpenseSettlement.Payment(ExpenseSettlement.BANK_TRANSFER, 3000,
            LocalDate.of(2026, 10, 2), null)), SAM, Instant.parse("2026-10-09T09:00:00Z"));

    mailer(true).settlementChanged(ALEX_PARENT, SAM_PARENT, before, after);

    final SimpleMailMessage message = sent();
    assertThat(message.getTo()).containsExactly("alex@example.com");
    assertThat(message.getSubject()).isEqualTo("Sam changed a settle-up to £30.00");
    assertThat(message.getText()).contains(
        "It now records £30.00 paid by Sam to Alex (it was £32.45)",
        "- Bank transfer, 2 Oct 2026: £30.00",
        "That is £2.45 less than the expenses came to (£32.45), so Sam still owes £2.45.");
  }

  private static ExpenseSettlement settlement(final long expensesPence, final long paidPence,
      final LocalDate paidOn) {
    return new ExpenseSettlement(new ObjectId(), ExpenseFixtures.FAMILY, SAM, ALEX,
        expensesPence, Expense.GBP, List.of(new ExpenseSettlement.Payment(
            ExpenseSettlement.BANK_TRANSFER, paidPence, paidOn, null)),
        List.of(), SAM, Instant.parse("2026-10-08T09:00:00Z"), null, null);
  }

  @Test
  void sendsNothingWhileEmailIsSwitchedOff() {
    mailer(false).needsAgreement(SAM_PARENT, ALEX_PARENT,
        ExpenseFixtures.paid(4500, ALEX, 50, Expense.PENDING, Expense.NONE));

    verify(sender, never()).send(any(SimpleMailMessage.class));
  }

  @Test
  void sendsNothingToCoparentsWhoHaveNotJoinedYet() {
    final Parent invited = new Parent(SAM, "invited:x", ExpenseFixtures.FAMILY, "Sam Taylor",
        "sam@example.com", "co-parent", "invited", null, null, null, null, null);

    mailer(true).needsAgreement(invited, ALEX_PARENT,
        ExpenseFixtures.paid(4500, ALEX, 50, Expense.PENDING, Expense.NONE));

    verify(sender, never()).send(any(SimpleMailMessage.class));
  }

  @Test
  void failedSendIsSwallowedSoTheChangeStillSucceeds() {
    doThrow(new MailSendException("smtp down")).when(sender).send(any(SimpleMailMessage.class));

    mailer(true).needsAgreement(SAM_PARENT, ALEX_PARENT,
        ExpenseFixtures.paid(4500, ALEX, 50, Expense.PENDING, Expense.NONE));

    verify(sender).send(any(SimpleMailMessage.class));
  }

  private ExpenseMailer mailer(final boolean enabled) {
    return new ExpenseMailer(sender, new CoparentProperties(true, "coparent",
        "https://coparents.example", "email", "noreply@example.com", enabled, "legacy",
        new CoparentProperties.Assistant(false, "model")));
  }

  private SimpleMailMessage sent() {
    final ArgumentCaptor<SimpleMailMessage> captor =
        ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(sender).send(captor.capture());
    return captor.getValue();
  }

  private static Parent parent(final org.bson.types.ObjectId id, final String name,
      final String email) {
    return new Parent(id, "auth0|" + name, ExpenseFixtures.FAMILY, name, email, "primary",
        "active", null, null, null, null, null);
  }
}
