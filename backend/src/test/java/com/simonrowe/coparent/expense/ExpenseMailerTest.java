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
import com.simonrowe.coparent.model.Parent;
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
  void claimTellsThePayerTheAmountOwed() {
    final Expense expense = ExpenseFixtures.paid(6490, ALEX, 50, Expense.AGREED, Expense.CLAIMED);

    mailer(true).claimed(ALEX_PARENT, SAM_PARENT, expense);

    assertThat(sent().getText()).contains("Sam says they paid you back £32.45");
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
