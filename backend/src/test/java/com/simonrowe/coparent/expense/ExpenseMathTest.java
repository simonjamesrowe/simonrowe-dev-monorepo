package com.simonrowe.coparent.expense;

import static com.simonrowe.coparent.expense.ExpenseFixtures.ALEX;
import static com.simonrowe.coparent.expense.ExpenseFixtures.SAM;
import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.coparent.model.Expense;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExpenseMathTest {

  @ParameterizedTest(name = "{0}p, Alex pays, Alex {1}% -> Sam owes {2}p")
  @CsvSource({
      "4500, 50, 2250",
      // Odd pence: Sam's half rounds up and Alex keeps the rest, so nothing is lost.
      "8999, 50, 4500",
      "1, 50, 1",
      "12345, 60, 4938",
      "4500, 100, 0",
      "4500, 0, 4500",
      "10000000, 33, 6700000"
  })
  void theParentWhoDidNotPayOwesTheirPercentageRoundedHalfUp(
      final long pence, final int alexPercent, final long owed) {
    final Expense expense = ExpenseFixtures.paid(pence, ALEX, alexPercent, Expense.AGREED,
        Expense.OUTSTANDING);

    assertThat(ExpenseMath.debtor(expense)).isEqualTo(SAM);
    assertThat(ExpenseMath.owedPence(expense)).isEqualTo(owed);
    assertThat(ExpenseMath.shareOf(expense, SAM)).isEqualTo(owed);
    assertThat(ExpenseMath.shareOf(expense, ALEX) + ExpenseMath.shareOf(expense, SAM))
        .isEqualTo(pence);
  }

  @Test
  void whenSamPaysAlexOwesAlexsShare() {
    final Expense expense = ExpenseFixtures.paid(6000, SAM, 50, Expense.AGREED,
        Expense.OUTSTANDING);

    assertThat(ExpenseMath.debtor(expense)).isEqualTo(ALEX);
    assertThat(ExpenseMath.owedPence(expense)).isEqualTo(3000);
  }

  @Test
  void anUndecidedPayerOwesNothingButSharesStillSumToTheAmount() {
    final Expense expense = ExpenseFixtures.expense(1801, Expense.UPCOMING,
        LocalDate.of(2026, 10, 30), null, 50, Expense.AGREED, Expense.NONE, ALEX, "education");

    assertThat(ExpenseMath.debtor(expense)).isNull();
    assertThat(ExpenseMath.owedPence(expense)).isZero();
    assertThat(ExpenseMath.shareOf(expense, ALEX) + ExpenseMath.shareOf(expense, SAM))
        .isEqualTo(1801);
  }

  @Test
  void onlyPaidAgreedUnsettledExpensesCountTowardsTheBalance() {
    assertThat(ExpenseMath.countsTowardsBalance(ExpenseFixtures.paid(100, ALEX, 50,
        Expense.AGREED, Expense.OUTSTANDING))).isTrue();
    assertThat(ExpenseMath.countsTowardsBalance(ExpenseFixtures.paid(100, ALEX, 50,
        Expense.AGREED, Expense.CLAIMED))).isTrue();
    assertThat(ExpenseMath.countsTowardsBalance(ExpenseFixtures.paid(100, ALEX, 50,
        Expense.PENDING, Expense.NONE))).isFalse();
    assertThat(ExpenseMath.countsTowardsBalance(ExpenseFixtures.paid(100, ALEX, 50,
        Expense.DISPUTED, Expense.NONE))).isFalse();
    assertThat(ExpenseMath.countsTowardsBalance(ExpenseFixtures.paid(100, ALEX, 50,
        Expense.AGREED, Expense.REIMBURSED))).isFalse();
  }

  @Test
  void anAgreedPaidExpenseIsOutstandingOnlyWhenSomethingIsOwed() {
    assertThat(ExpenseMath.reimbursementOnceAgreed(ExpenseFixtures.paid(100, ALEX, 50,
        Expense.AGREED, Expense.NONE))).isEqualTo(Expense.OUTSTANDING);
    assertThat(ExpenseMath.reimbursementOnceAgreed(ExpenseFixtures.paid(100, ALEX, 100,
        Expense.AGREED, Expense.NONE))).isEqualTo(Expense.NONE);
  }
}
