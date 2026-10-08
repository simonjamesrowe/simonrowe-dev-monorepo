package com.simonrowe.coparent.expense;

import static com.simonrowe.coparent.expense.ExpenseFixtures.ALEX;
import static com.simonrowe.coparent.expense.ExpenseFixtures.SAM;
import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.coparent.model.Expense;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpenseSummaryTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

  @Test
  void balanceNetsBothDirectionsAndCountsClaimedRepaymentsUntilConfirmed() {
    final List<Expense> expenses = List.of(
        // Sam owes Alex 40.00, already claimed as paid back.
        ExpenseFixtures.paid(8000, ALEX, 50, Expense.AGREED, Expense.CLAIMED),
        // Sam owes Alex 32.45.
        ExpenseFixtures.paid(6490, ALEX, 50, Expense.AGREED, Expense.OUTSTANDING),
        // Alex owes Sam 30.00.
        ExpenseFixtures.paid(6000, SAM, 50, Expense.AGREED, Expense.OUTSTANDING),
        // Neither pending nor settled expenses count.
        ExpenseFixtures.paid(4500, ALEX, 50, Expense.PENDING, Expense.NONE),
        ExpenseFixtures.paid(15000, ALEX, 60, Expense.AGREED, Expense.REIMBURSED));

    final ExpenseSummary alex = ExpenseSummary.of(expenses, ALEX, TODAY);
    assertThat(alex.balance().netPence()).isEqualTo(4245);
    assertThat(alex.balance().creditorParentId()).isEqualTo(ALEX);
    assertThat(alex.balance().debtorParentId()).isEqualTo(SAM);
    assertThat(alex.balance().expenseCount()).isEqualTo(3);
    assertThat(alex.balance().awaitingYourConfirmationPence()).isEqualTo(4000);

    final ExpenseSummary sam = ExpenseSummary.of(expenses, SAM, TODAY);
    assertThat(sam.balance().netPence()).isEqualTo(4245);
    assertThat(sam.balance().debtorParentId()).isEqualTo(SAM);
    assertThat(sam.balance().awaitingTheirConfirmationPence()).isEqualTo(4000);
  }

  @Test
  void squareParentsHaveNoDebtorOrCreditor() {
    final ExpenseSummary summary = ExpenseSummary.of(List.of(
        ExpenseFixtures.paid(4000, ALEX, 50, Expense.AGREED, Expense.OUTSTANDING),
        ExpenseFixtures.paid(4000, SAM, 50, Expense.AGREED, Expense.OUTSTANDING)), ALEX, TODAY);

    assertThat(summary.balance().netPence()).isZero();
    assertThat(summary.balance().debtorParentId()).isNull();
    assertThat(summary.balance().creditorParentId()).isNull();
  }

  @Test
  void needsActionCoversAgreeingFixingPayingBackAndConfirming() {
    final List<Expense> expenses = List.of(
        // Sam sent it: Alex must agree.
        ExpenseFixtures.expense(100, Expense.PAID, TODAY, SAM, 50, Expense.PENDING,
            Expense.NONE, SAM, "food"),
        // Alex sent it and Sam disputed it: Alex must fix it.
        ExpenseFixtures.expense(100, Expense.PAID, TODAY, ALEX, 50, Expense.DISPUTED,
            Expense.NONE, ALEX, "food"),
        // Alex owes Sam: Alex must pay back.
        ExpenseFixtures.paid(100, SAM, 50, Expense.AGREED, Expense.OUTSTANDING),
        // Sam says paid: Alex must confirm.
        ExpenseFixtures.paid(100, ALEX, 50, Expense.AGREED, Expense.CLAIMED),
        // Waiting on Sam, and owed to Alex: nothing for Alex to do.
        ExpenseFixtures.expense(100, Expense.PAID, TODAY, ALEX, 50, Expense.PENDING,
            Expense.NONE, ALEX, "food"),
        ExpenseFixtures.paid(100, ALEX, 50, Expense.AGREED, Expense.OUTSTANDING));

    final ExpenseSummary summary = ExpenseSummary.of(expenses, ALEX, TODAY);
    assertThat(summary.needsYourAction()).isEqualTo(4);
    assertThat(summary.needsYourAgreement().count()).isEqualTo(1);
    assertThat(summary.awaitingOther()).isEqualTo(1);
  }

  @Test
  void upcomingIncludesOverdueAndTheNextThirtyDaysButNotDisputesOrLater() {
    final List<Expense> expenses = List.of(
        upcoming(24000, LocalDate.of(2026, 10, 20), Expense.PENDING),
        upcoming(1800, LocalDate.of(2026, 10, 1), Expense.AGREED),
        upcoming(5000, LocalDate.of(2026, 12, 25), Expense.AGREED),
        upcoming(7500, LocalDate.of(2026, 10, 30), Expense.DISPUTED));

    final ExpenseSummary.Upcoming upcoming = ExpenseSummary.of(expenses, ALEX, TODAY).upcoming();
    assertThat(upcoming.count()).isEqualTo(2);
    assertThat(upcoming.totalPence()).isEqualTo(25800);
    assertThat(upcoming.yourSharePence()).isEqualTo(12900);
    assertThat(upcoming.next()).extracting(Expense::date)
        .containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20));
  }

  @Test
  void thisMonthTotalsPaidExpensesByCategoryLargestFirst() {
    final List<Expense> expenses = List.of(
        ExpenseFixtures.expense(4500, Expense.PAID, LocalDate.of(2026, 10, 3), ALEX, 50,
            Expense.PENDING, Expense.NONE, ALEX, "clothing"),
        ExpenseFixtures.expense(6000, Expense.PAID, LocalDate.of(2026, 10, 1), SAM, 50,
            Expense.AGREED, Expense.OUTSTANDING, SAM, "medical"),
        ExpenseFixtures.expense(9999, Expense.PAID, LocalDate.of(2026, 9, 30), SAM, 50,
            Expense.AGREED, Expense.OUTSTANDING, SAM, "medical"),
        ExpenseFixtures.expense(5499, Expense.PAID, LocalDate.of(2026, 10, 6), ALEX, 50,
            Expense.DISPUTED, Expense.NONE, ALEX, "activities"));

    final ExpenseSummary.ThisMonth month = ExpenseSummary.of(expenses, ALEX, TODAY).thisMonth();
    assertThat(month.totalPence()).isEqualTo(10500);
    assertThat(month.yourSharePence()).isEqualTo(5250);
    assertThat(month.byCategory()).containsExactly(
        new ExpenseSummary.CategoryTotal("medical", 6000),
        new ExpenseSummary.CategoryTotal("clothing", 4500));
  }

  private static Expense upcoming(final long pence, final LocalDate due, final String agreement) {
    return ExpenseFixtures.expense(pence, Expense.UPCOMING, due, SAM, 50, agreement,
        Expense.NONE, SAM, "activities");
  }
}
