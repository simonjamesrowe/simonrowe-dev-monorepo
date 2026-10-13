package com.simonrowe.coparent.expense;

import com.simonrowe.coparent.model.Expense;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;

/** Builds expenses for unit tests without a database. */
final class ExpenseFixtures {

  static final ObjectId ALEX = new ObjectId("aaaaaaaaaaaaaaaaaaaaaaaa");
  static final ObjectId SAM = new ObjectId("bbbbbbbbbbbbbbbbbbbbbbbb");
  static final ObjectId FAMILY = new ObjectId("cccccccccccccccccccccccc");

  private ExpenseFixtures() {
  }

  /** A paid expense with the given payer and Alex's percentage of the cost. */
  static Expense paid(final long pence, final ObjectId payer, final int alexPercent,
      final String agreement, final String reimbursement) {
    return expense(pence, Expense.PAID, LocalDate.of(2026, 10, 3), payer, alexPercent,
        agreement, reimbursement, ALEX, "clothing");
  }

  /** An agreed, paid expense being paid back by the given settle-up. */
  static Expense paidBackIn(final long pence, final ObjectId payer, final String reimbursement,
      final ObjectId settlementId) {
    return expense(pence, Expense.PAID, LocalDate.of(2026, 10, 3), payer, 50, Expense.AGREED,
        reimbursement, ALEX, "clothing", settlementId);
  }

  static Expense expense(final long pence, final String timing, final LocalDate date,
      final ObjectId payer, final int alexPercent, final String agreement,
      final String reimbursement, final ObjectId requestedBy, final String category) {
    return expense(pence, timing, date, payer, alexPercent, agreement, reimbursement,
        requestedBy, category, null);
  }

  private static Expense expense(final long pence, final String timing, final LocalDate date,
      final ObjectId payer, final int alexPercent, final String agreement,
      final String reimbursement, final ObjectId requestedBy, final String category,
      final ObjectId settlementId) {
    final Instant now = Instant.parse("2026-10-08T09:00:00Z");
    return new Expense(new ObjectId(), FAMILY, "Item", category, List.of(new ObjectId()), pence,
        Expense.GBP, timing, date, payer,
        List.of(new Expense.Share(ALEX, alexPercent), new Expense.Share(SAM, 100 - alexPercent)),
        new Expense.Agreement(agreement, requestedBy, null, null, null),
        new Expense.Reimbursement(reimbursement, null, null, null, null, null, settlementId),
        List.of(), null, List.of(), 1, requestedBy, null, null, now, now);
  }
}
