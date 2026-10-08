package com.simonrowe.coparent.expense;

import com.simonrowe.coparent.model.Expense;
import java.util.List;
import org.bson.types.ObjectId;

/**
 * Who owes whom, in whole pence. The parent who did not pay owes their percentage of the
 * amount, rounded half up; the payer's share is whatever remains, so the two shares always add
 * up to the amount exactly. The frontend's {@code money.ts} mirrors this for the form preview,
 * and every API response carries the server's figure so the page never has to work it out.
 */
public final class ExpenseMath {

  private ExpenseMath() {
  }

  /** Rounds {@code amountPence * percent / 100} half up. Bounded: 10,000,000 x 100 fits a long. */
  static long percentOf(final long amountPence, final int percent) {
    return (amountPence * percent + 50) / 100;
  }

  /** The parent who did not pay, or null while nobody is down to pay. */
  static ObjectId debtor(final Expense expense) {
    if (expense.payerId() == null) {
      return null;
    }
    return expense.shares().stream().map(Expense.Share::parentId)
        .filter(id -> !id.equals(expense.payerId())).findFirst().orElse(null);
  }

  /** What the non-paying parent owes the payer under the current terms, agreed or not. */
  static long owedPence(final Expense expense) {
    final ObjectId debtor = debtor(expense);
    return debtor == null ? 0 : percentOf(expense.amountPence(), percentFor(expense, debtor));
  }

  /** One parent's share of the cost. Shares always sum to the amount. */
  static long shareOf(final Expense expense, final ObjectId parentId) {
    if (expense.payerId() != null) {
      final long owed = owedPence(expense);
      return parentId.equals(expense.payerId()) ? expense.amountPence() - owed : owed;
    }
    final List<Expense.Share> shares = expense.shares();
    if (shares.isEmpty()) {
      return 0;
    }
    final Expense.Share first = shares.getFirst();
    final long firstShare = percentOf(expense.amountPence(), first.percent());
    return parentId.equals(first.parentId()) ? firstShare : expense.amountPence() - firstShare;
  }

  /** Paid, agreed, and not yet settled: the only expenses the balance is made of. */
  static boolean countsTowardsBalance(final Expense expense) {
    return Expense.PAID.equals(expense.timing())
        && Expense.AGREED.equals(expense.agreement().status())
        && (Expense.OUTSTANDING.equals(expense.reimbursement().status())
            || Expense.CLAIMED.equals(expense.reimbursement().status()));
  }

  /** The reimbursement state an expense starts in once it is paid and agreed. */
  static String reimbursementOnceAgreed(final Expense expense) {
    return Expense.PAID.equals(expense.timing()) && owedPence(expense) > 0
        ? Expense.OUTSTANDING : Expense.NONE;
  }

  private static int percentFor(final Expense expense, final ObjectId parentId) {
    return expense.shares().stream().filter(share -> share.parentId().equals(parentId))
        .mapToInt(Expense.Share::percent).findFirst().orElse(0);
  }
}
