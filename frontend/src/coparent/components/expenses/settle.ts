import type { Expense } from '../../types/expenses';

/**
 * What settling up can touch: every agreed debt still outstanding, in either direction, and
 * repayments the other parent has already sent the signed-in parent, which only need confirming.
 */
export function settleable(expenses: Expense[], me: string): Expense[] {
  return expenses.filter(
    (expense) =>
      expense.countsTowardsBalance &&
      expense.owedPence > 0 &&
      (expense.reimbursement.status === 'outstanding' || expense.payerId === me),
  );
}

/** The money that changes hands if the ticked items are settled: positive is paid to me. */
export function netOf(items: Expense[], me: string): number {
  return items.reduce((sum, expense) => {
    // Already sent by the other parent: ticking it confirms receipt and moves no money now.
    if (expense.reimbursement.status === 'claimed') return sum;
    return sum + (expense.payerId === me ? expense.owedPence : -expense.owedPence);
  }, 0);
}
