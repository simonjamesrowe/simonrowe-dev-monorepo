package com.simonrowe.coparent.expense;

import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.Expense;
import com.simonrowe.coparent.model.ExpenseSettlement;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Tells the other parent when an expense needs them. Best-effort: it runs after the expense is
 * saved, and a failed send is logged rather than thrown, so mail can never undo or block a
 * change. Only the moments that need the other parent to act send anything: new terms to agree,
 * a dispute, and a claimed repayment or settle-up to confirm.
 */
@Component
public class ExpenseMailer {

  private static final Logger LOG = LoggerFactory.getLogger(ExpenseMailer.class);
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);

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

  /**
   * The recipient is asked to agree to several expenses added together, such as every payment
   * to one merchant on a statement, in one email rather than one each.
   */
  public void needsAgreementToAll(final Parent to, final Parent from,
      final List<Expense> expenses) {
    final Set<String> titles = expenses.stream().map(Expense::title)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    final String lines = expenses.stream().map(expense -> "- %s, %s: %s (your share %s)".formatted(
            expense.title(), expense.date() == null ? "no date" : DAY.format(expense.date()),
            ExpenseMoney.format(expense.amountPence()),
            ExpenseMoney.format(ExpenseMath.shareOf(expense, to.id()))))
        .collect(Collectors.joining("\n"));
    final long share = expenses.stream()
        .mapToLong(expense -> ExpenseMath.shareOf(expense, to.id())).sum();
    send(to, "%s added %d expenses%s".formatted(firstName(from), expenses.size(),
        titles.size() == 1 ? ": " + titles.iterator().next() : ""), """
        %s added %d expenses:

        %s

        Your share of them is %s.

        Review them: %s/expenses
        """.formatted(firstName(from), expenses.size(), lines, ExpenseMoney.format(share),
        properties.appUrl()));
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

  /**
   * The parent who was paid is asked to confirm a settle-up's payments arrived, in one email for
   * every expense it paid back rather than one each.
   */
  public void settlementRecorded(final Parent to, final Parent from,
      final ExpenseSettlement settlement, final List<Expense> claimed) {
    final String expenseLines = claimed.stream()
        .map(expense -> "- %s: %s".formatted(expense.title(),
            ExpenseMoney.format(ExpenseMath.owedPence(expense))))
        .collect(Collectors.joining("\n"));
    send(to, "%s says they paid you %s".formatted(firstName(from),
        ExpenseMoney.format(settlement.paidPence())), """
        %s says they paid you %s:

        %s

        It pays back:

        %s
        %s
        Confirm it arrived: %s/expenses?view=transfers
        """.formatted(firstName(from), ExpenseMoney.format(settlement.paidPence()),
        paymentLines(settlement), expenseLines, differenceLine(settlement, to, from),
        properties.appUrl()));
  }

  /**
   * The other parent is told when a settle-up's payments are corrected, since a new amount moves
   * the balance they see.
   */
  public void settlementChanged(final Parent to, final Parent by,
      final ExpenseSettlement before, final ExpenseSettlement after) {
    final Parent payer = by.id().equals(after.fromParentId()) ? by : to;
    final Parent payee = payer == by ? to : by;
    send(to, "%s changed a settle-up to %s".formatted(firstName(by),
        ExpenseMoney.format(after.paidPence())), """
        %s changed the payments on a settle-up. It now records %s paid by %s to %s \
        (it was %s):

        %s
        %s
        See it: %s/expenses?view=transfers
        """.formatted(firstName(by), ExpenseMoney.format(after.paidPence()),
        firstName(payer), firstName(payee), ExpenseMoney.format(before.paidPence()),
        paymentLines(after), differenceLine(after, payee, payer), properties.appUrl()));
  }

  private static String paymentLines(final ExpenseSettlement settlement) {
    return settlement.payments().stream()
        .map(payment -> "- %s, %s: %s%s".formatted(methodLabel(payment.method()),
            DAY.format(payment.paidOn()), ExpenseMoney.format(payment.amountPence()),
            payment.reference() == null ? "" : " (ref " + payment.reference() + ")"))
        .collect(Collectors.joining("\n"));
  }

  /**
   * What happens to a payment that did not match its expenses, addressed to the parent who was
   * paid, or an empty string when it matched. Ends with a blank line when present.
   */
  private static String differenceLine(final ExpenseSettlement settlement, final Parent payee,
      final Parent payer) {
    final long difference = settlement.differencePence();
    if (difference == 0) {
      return "";
    }
    final String amount = ExpenseMoney.format(Math.abs(difference));
    final String expenses = ExpenseMoney.format(settlement.amountPence());
    return difference > 0
        ? "\nThat is %s more than the expenses came to (%s), so %s owes %s back.\n".formatted(
            amount, expenses, firstName(payee), amount)
        : "\nThat is %s less than the expenses came to (%s), so %s still owes %s.\n".formatted(
            amount, expenses, firstName(payer), amount);
  }

  private static String methodLabel(final String method) {
    return switch (method) {
      case ExpenseSettlement.BANK_TRANSFER -> "Bank transfer";
      case ExpenseSettlement.CASH -> "Cash";
      default -> "Other payment";
    };
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
