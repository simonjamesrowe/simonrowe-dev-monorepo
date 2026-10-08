package com.simonrowe.coparent.expense;

import com.simonrowe.coparent.model.Expense;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;

/**
 * The Expenses page's cards and the dashboard's balance, from one parent's point of view.
 *
 * @param balance net of every paid, agreed, unsettled expense, in either direction
 * @param needsYourAgreement expenses the other parent sent that wait on this parent's OK
 * @param needsYourAction everything waiting on this parent: agreeing, fixing a dispute, paying
 *     back, or confirming money arrived (the Expenses nav badge)
 * @param awaitingOther expenses this parent sent that wait on the other parent
 * @param upcoming coming-up costs due within the next 30 days, overdue ones included
 * @param thisMonth what was paid this calendar month, by category
 */
public record ExpenseSummary(
    String currency,
    Balance balance,
    Total needsYourAgreement,
    int needsYourAction,
    int awaitingOther,
    Upcoming upcoming,
    ThisMonth thisMonth
) {
  static final int UPCOMING_DAYS = 30;
  static final int UPCOMING_SHOWN = 3;

  /** Who owes whom overall. Both ids are null when the parents are square. */
  public record Balance(
      long netPence,
      ObjectId debtorParentId,
      ObjectId creditorParentId,
      int expenseCount,
      long awaitingYourConfirmationPence,
      long awaitingTheirConfirmationPence
  ) {
  }

  /** A count and the sum of the amounts counted. */
  public record Total(int count, long totalPence) {
  }

  /** Costs coming up, and the first few due. */
  public record Upcoming(int count, long totalPence, long yourSharePence, List<Expense> next) {
  }

  /** This month's paid expenses. */
  public record ThisMonth(long totalPence, long yourSharePence, List<CategoryTotal> byCategory) {
  }

  /** Spend in one category. */
  public record CategoryTotal(String category, long totalPence) {
  }

  /** Folds the family's live expenses into the summary for {@code me}. */
  static ExpenseSummary of(final List<Expense> expenses, final ObjectId me, final LocalDate today) {
    long net = 0;
    int counted = 0;
    long awaitingMine = 0;
    long awaitingTheirs = 0;
    ObjectId other = null;
    for (final Expense expense : expenses) {
      if (!ExpenseMath.countsTowardsBalance(expense)) {
        continue;
      }
      final long owed = ExpenseMath.owedPence(expense);
      if (owed == 0) {
        continue;
      }
      counted++;
      final boolean claimed = Expense.CLAIMED.equals(expense.reimbursement().status());
      if (me.equals(expense.payerId())) {
        net += owed;
        other = ExpenseMath.debtor(expense);
        awaitingMine += claimed ? owed : 0;
      } else {
        net -= owed;
        other = expense.payerId();
        awaitingTheirs += claimed ? owed : 0;
      }
    }
    final Balance balance = new Balance(Math.abs(net),
        net < 0 ? me : net > 0 ? other : null,
        net > 0 ? me : net < 0 ? other : null,
        counted, awaitingMine, awaitingTheirs);

    final List<Expense> toAgree = expenses.stream()
        .filter(expense -> Expense.PENDING.equals(expense.agreement().status())
            && !me.equals(expense.agreement().requestedBy()))
        .toList();
    final int awaitingOther = (int) expenses.stream()
        .filter(expense -> Expense.PENDING.equals(expense.agreement().status())
            && me.equals(expense.agreement().requestedBy()))
        .count();
    final int needsAction = (int) expenses.stream()
        .filter(expense -> needsAction(expense, me)).count();

    final LocalDate horizon = today.plusDays(UPCOMING_DAYS);
    final List<Expense> upcoming = expenses.stream()
        .filter(expense -> Expense.UPCOMING.equals(expense.timing())
            && !Expense.DISPUTED.equals(expense.agreement().status())
            && !expense.date().isAfter(horizon))
        .sorted(Comparator.comparing(Expense::date))
        .toList();

    final YearMonth month = YearMonth.from(today);
    final List<Expense> paidThisMonth = expenses.stream()
        .filter(expense -> Expense.PAID.equals(expense.timing())
            && !Expense.DISPUTED.equals(expense.agreement().status())
            && YearMonth.from(expense.date()).equals(month))
        .toList();
    final Map<String, Long> byCategory = new LinkedHashMap<>();
    paidThisMonth.forEach(expense ->
        byCategory.merge(expense.category(), expense.amountPence(), Long::sum));

    return new ExpenseSummary(Expense.GBP, balance,
        new Total(toAgree.size(), sum(toAgree)),
        needsAction, awaitingOther,
        new Upcoming(upcoming.size(), sum(upcoming), shareSum(upcoming, me),
            upcoming.stream().limit(UPCOMING_SHOWN).toList()),
        new ThisMonth(sum(paidThisMonth), shareSum(paidThisMonth, me),
            byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> new CategoryTotal(entry.getKey(), entry.getValue()))
                .toList()));
  }

  /** The same test the Expenses page's "Needs action" tab applies. */
  static boolean needsAction(final Expense expense, final ObjectId me) {
    final String agreement = expense.agreement().status();
    final boolean requester = me.equals(expense.agreement().requestedBy());
    if (Expense.PENDING.equals(agreement)) {
      return !requester;
    }
    if (Expense.DISPUTED.equals(agreement)) {
      return requester;
    }
    final String reimbursement = expense.reimbursement().status();
    return Expense.OUTSTANDING.equals(reimbursement) && me.equals(ExpenseMath.debtor(expense))
        || Expense.CLAIMED.equals(reimbursement) && me.equals(expense.payerId());
  }

  private static long sum(final List<Expense> expenses) {
    return expenses.stream().mapToLong(Expense::amountPence).sum();
  }

  private static long shareSum(final List<Expense> expenses, final ObjectId me) {
    return expenses.stream().mapToLong(expense -> ExpenseMath.shareOf(expense, me)).sum();
  }
}
