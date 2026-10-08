package com.simonrowe.coparent.expense;

import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.Expense;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Tells the other parent when an expense needs them. Best-effort: it runs after the expense is
 * saved, and a failed send is logged rather than thrown, so mail can never undo or block a
 * change. Only the three moments that need the other parent to act send anything: new terms to
 * agree, a dispute, and a claimed repayment to confirm.
 */
@Component
public class ExpenseMailer {

  private static final Logger LOG = LoggerFactory.getLogger(ExpenseMailer.class);

  private final JavaMailSender mailSender;
  private final CoparentProperties properties;

  public ExpenseMailer(final JavaMailSender mailSender, final CoparentProperties properties) {
    this.mailSender = mailSender;
    this.properties = properties;
  }

  /** The recipient is asked to agree to new or changed terms. */
  public void needsAgreement(final Parent to, final Parent from, final Expense expense) {
    send(to, "%s added an expense: %s".formatted(firstName(from), expense.title()), """
        %s added "%s" for %s.

        Your share is %s.

        Review it: %s
        """.formatted(firstName(from), expense.title(), ExpenseMoney.format(expense.amountPence()),
        ExpenseMoney.format(ExpenseMath.shareOf(expense, to.id())), link(expense)));
  }

  /** The requester learns the other parent disputed the expense, and why. */
  public void disputed(final Parent to, final Parent from, final Expense expense) {
    send(to, "%s disputed an expense: %s".formatted(firstName(from), expense.title()), """
        %s disputed "%s" (%s):

        "%s"

        You can edit it and send it again, or withdraw it: %s
        """.formatted(firstName(from), expense.title(), ExpenseMoney.format(expense.amountPence()),
        expense.agreement().note(), link(expense)));
  }

  /** The payer is asked to confirm that the money owed has arrived. */
  public void claimed(final Parent to, final Parent from, final Expense expense) {
    send(to, "%s says they paid you back for %s".formatted(firstName(from), expense.title()), """
        %s says they paid you back %s for "%s".

        Confirm it when it arrives: %s
        """.formatted(firstName(from), ExpenseMoney.format(ExpenseMath.owedPence(expense)),
        expense.title(), link(expense)));
  }

  private void send(final Parent to, final String subject, final String body) {
    if (!properties.emailEnabled() || to.email() == null || to.email().isBlank()) {
      return;
    }
    // A co-parent who has not accepted their invitation has no account to review anything in.
    // They find everything waiting for them under Needs action when they join.
    if (!CoparentAccessPolicy.ACTIVE.equals(to.status())) {
      return;
    }
    final SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(properties.emailFrom());
    message.setTo(to.email());
    message.setSubject(subject);
    message.setText(body);
    try {
      mailSender.send(message);
    } catch (MailException exception) {
      LOG.warn("Could not send expense email: {}", exception.getMessage());
    }
  }

  private String link(final Expense expense) {
    return properties.appUrl() + "/expenses?expense=" + expense.id().toHexString();
  }

  private static String firstName(final Parent parent) {
    final String name = parent.fullName() == null ? "" : parent.fullName().trim();
    final int space = name.indexOf(' ');
    return name.isEmpty() ? "Your co-parent" : space < 0 ? name : name.substring(0, space);
  }
}
