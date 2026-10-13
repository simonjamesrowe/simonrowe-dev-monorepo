package com.simonrowe.coparent.expense;

import static com.simonrowe.coparent.expense.ExpenseFixtures.ALEX;
import static com.simonrowe.coparent.expense.ExpenseFixtures.SAM;
import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.coparent.model.Expense;
import com.simonrowe.coparent.model.ExpenseSettlement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;
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
  void settleUpThatPaidMoreThanItsExpensesIsOwedBackOnceConfirmed() {
    final ObjectId transfer = new ObjectId();
    // Alex paid Sam a round 1,500.00 against 1,478.68 of expenses Sam had paid.
    final List<ExpenseSettlement> settlements = List.of(
        settlement(transfer, ALEX, SAM, 147868, 150000));
    final List<Expense> settled = List.of(
        ExpenseFixtures.paidBackIn(295736, SAM, Expense.REIMBURSED, transfer));

    final ExpenseSummary alex = ExpenseSummary.of(settled, settlements, ALEX, TODAY, true);
    assertThat(alex.balance().netPence()).isEqualTo(2132);
    assertThat(alex.balance().creditorParentId()).isEqualTo(ALEX);
    assertThat(alex.balance().debtorParentId()).isEqualTo(SAM);
    assertThat(alex.balance().carriedPence()).isEqualTo(2132);
    assertThat(alex.balance().expenseCount()).isZero();

    final ExpenseSummary sam = ExpenseSummary.of(settled, settlements, SAM, TODAY, true);
    assertThat(sam.balance().netPence()).isEqualTo(2132);
    assertThat(sam.balance().debtorParentId()).isEqualTo(SAM);
    assertThat(sam.balance().carriedPence()).isEqualTo(-2132);

    // While Sam has yet to confirm, the expenses still count in full and the extra waits.
    final List<Expense> claimed = List.of(
        ExpenseFixtures.paidBackIn(295736, SAM, Expense.CLAIMED, transfer));
    final ExpenseSummary waiting = ExpenseSummary.of(claimed, settlements, ALEX, TODAY, true);
    assertThat(waiting.balance().netPence()).isEqualTo(147868);
    assertThat(waiting.balance().debtorParentId()).isEqualTo(ALEX);
    assertThat(waiting.balance().carriedPence()).isZero();
  }

  @Test
  void shortfallIsStillOwedAndOffsetsAnEarlierOverpayment() {
    final ExpenseSettlement over = settlement(new ObjectId(), ALEX, SAM, 147868, 150000);
    final ExpenseSettlement under = settlement(new ObjectId(), ALEX, SAM, 10000, 7868);

    final ExpenseSummary square = ExpenseSummary.of(List.of(), List.of(over, under), ALEX,
        TODAY, true);
    assertThat(square.balance().netPence()).isZero();
    assertThat(square.balance().debtorParentId()).isNull();

    final ExpenseSummary owing = ExpenseSummary.of(List.of(), List.of(under), SAM, TODAY, true);
    assertThat(owing.balance().netPence()).isEqualTo(2132);
    assertThat(owing.balance().creditorParentId()).isEqualTo(SAM);
    assertThat(owing.balance().debtorParentId()).isEqualTo(ALEX);
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
  void untilTheCoparentJoinsTheParentsOwnPendingExpensesNeedTheirOk() {
    final List<Expense> expenses = List.of(
        ExpenseFixtures.expense(100, Expense.PAID, TODAY, ALEX, 50, Expense.PENDING,
            Expense.NONE, ALEX, "food"),
        ExpenseFixtures.expense(250, Expense.PAID, TODAY, SAM, 50, Expense.PENDING,
            Expense.NONE, ALEX, "food"));

    final ExpenseSummary waiting = ExpenseSummary.of(expenses, List.of(), ALEX, TODAY, false);
    assertThat(waiting.needsYourAgreement().count()).isEqualTo(2);
    assertThat(waiting.needsYourAgreement().totalPence()).isEqualTo(350);
    assertThat(waiting.needsYourAction()).isEqualTo(2);
    assertThat(waiting.awaitingOther()).isZero();

    final ExpenseSummary joined = ExpenseSummary.of(expenses, List.of(), ALEX, TODAY, true);
    assertThat(joined.needsYourAgreement().count()).isZero();
    assertThat(joined.awaitingOther()).isEqualTo(2);
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

  private static ExpenseSettlement settlement(final ObjectId id, final ObjectId from,
      final ObjectId to, final long expensesPence, final long paidPence) {
    return new ExpenseSettlement(id, ExpenseFixtures.FAMILY, from, to, expensesPence,
        Expense.GBP, List.of(new ExpenseSettlement.Payment(ExpenseSettlement.BANK_TRANSFER,
            paidPence, LocalDate.of(2026, 10, 8), null)),
        List.of(), from, Instant.parse("2026-10-13T09:00:00Z"), null, null);
  }
}
