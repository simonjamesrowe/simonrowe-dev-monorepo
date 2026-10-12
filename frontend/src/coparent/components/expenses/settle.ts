import type { Expense, ExpenseSettlement, PaymentMethod } from '../../types/expenses';

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

/** What "Mark paid back" can act on: an agreed debt, in either direction, nobody has paid back yet. */
export function markable(expense: Expense): boolean {
  return expense.countsTowardsBalance && expense.owedPence > 0 && expense.reimbursement.status === 'outstanding';
}

/** The money that changes hands if the ticked items are settled: positive is paid to me. */
export function netOf(items: Expense[], me: string): number {
  return items.reduce((sum, expense) => {
    // Already sent by the other parent: ticking it confirms receipt and moves no money now.
    if (expense.reimbursement.status === 'claimed') return sum;
    return sum + (expense.payerId === me ? expense.owedPence : -expense.owedPence);
  }, 0);
}

export const PAYMENT_METHODS: { key: PaymentMethod; label: string }[] = [
  { key: 'bank_transfer', label: 'Bank transfer' },
  { key: 'cash', label: 'Cash' },
  { key: 'other', label: 'Other' },
];

export function methodLabel(method: PaymentMethod): string {
  return PAYMENT_METHODS.find((entry) => entry.key === method)?.label ?? 'Other';
}

export type SettlementState = 'awaitingMe' | 'awaitingThem' | 'settled';

/**
 * Where a settle-up stands, read from its expenses: any still claimed wait on the parent who
 * was paid to confirm the money arrived. Mirrors what ExpenseSettlementService.confirm settles.
 */
export function settlementState(settlement: ExpenseSettlement, expenses: Expense[], me: string): SettlementState {
  const waiting = expenses.filter(
    (expense) =>
      expense.reimbursement.settlementId === settlement.id && expense.reimbursement.status === 'claimed',
  );
  if (waiting.length === 0) return 'settled';
  return waiting.some((expense) => expense.payerId === me) ? 'awaitingMe' : 'awaitingThem';
}
