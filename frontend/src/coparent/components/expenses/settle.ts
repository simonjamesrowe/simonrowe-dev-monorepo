import { formatShortDate } from '../../lib/formatters/date';
import type { Expense, ExpenseSettlement, PaymentMethod } from '../../types/expenses';

import { formatMoney } from './money';

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

/** Oldest first, so a settle-up reads like a statement. Ties keep the order they came in. */
export function byDate(expenses: Expense[]): Expense[] {
  return [...expenses].sort((a, b) => a.date.localeCompare(b.date));
}

/**
 * What the whole expense came to and how it was shared, e.g. "£45.00 total · you 50%, Sam 50%",
 * so a row's own amount (one parent's share) is never mistaken for the bill.
 */
export function shareLine(expense: Expense, me: string, otherName: string): string {
  const shares = [...expense.shares]
    .sort((a, b) => (a.parentId === me ? -1 : b.parentId === me ? 1 : 0))
    .map((share) => `${share.parentId === me ? 'you' : otherName} ${share.percent}%`);
  return [`${formatMoney(expense.amountPence)} total`, shares.join(', ')].filter(Boolean).join(' · ');
}

/**
 * A plain-text summary of the ticked expenses to send the other parent, written in the signed-in
 * parent's voice. It shows the working, not just the answer: each side's expenses with the whole
 * bill and the debtor's percentage, a subtotal per side, then the subtraction that gives the net.
 * It goes out through WhatsApp or similar, so it carries no markup. Repayments the other parent
 * has already sent are listed apart, because ticking them moves no money (see netOf).
 */
export function settleMessage(items: Expense[], me: string, otherName: string, now: Date = new Date()): string {
  if (items.length === 0) return '';
  const sorted = byDate(items);
  const iOwe = sorted.filter((expense) => expense.payerId !== me);
  const theyOwe = sorted.filter((expense) => expense.payerId === me && expense.reimbursement.status !== 'claimed');
  const sent = sorted.filter((expense) => expense.payerId === me && expense.reimbursement.status === 'claimed');
  const percentOf = (expense: Expense) =>
    expense.shares.find((share) => share.parentId === expense.debtorParentId)?.percent;
  const line = (expense: Expense, whose: 'my' | 'your') => {
    const percent = percentOf(expense);
    const part = percent === undefined ? `${whose} share` : `${whose} ${percent}%`;
    return `• ${formatShortDate(expense.date, now)}, ${expense.title}: ${formatMoney(expense.amountPence)} total, ${part} is ${formatMoney(expense.owedPence)}`;
  };
  const sum = (list: Expense[]) => list.reduce((total, expense) => total + expense.owedPence, 0);
  const iOweTotal = sum(iOwe);
  const theyOweTotal = sum(theyOwe);

  const sections: string[][] = [[`Hi ${otherName}, here's where we are with the shared expenses.`]];
  if (iOwe.length > 0) {
    sections.push([
      'What I owe you (my share of what you paid):',
      ...iOwe.map((expense) => line(expense, 'my')),
      `Subtotal: ${formatMoney(iOweTotal)}`,
    ]);
  }
  if (theyOwe.length > 0) {
    sections.push([
      'What you owe me (your share of what I paid):',
      ...theyOwe.map((expense) => line(expense, 'your')),
      `Subtotal: ${formatMoney(theyOweTotal)}`,
    ]);
  }
  if (sent.length > 0) {
    sections.push([
      "Already paid back by you, so not counted here:",
      ...sent.map((expense) => `• ${formatShortDate(expense.date, now)}, ${expense.title}: ${formatMoney(expense.owedPence)}`),
    ]);
  }

  const net = theyOweTotal - iOweTotal;
  const owing = net > 0 ? `you owe me ${formatMoney(net)}` : `I owe you ${formatMoney(-net)}`;
  if (iOwe.length > 0 && theyOwe.length > 0) {
    const [larger, smaller] = net >= 0 ? [theyOweTotal, iOweTotal] : [iOweTotal, theyOweTotal];
    const outcome = net === 0 ? "so we're square." : `so ${owing}.`;
    sections.push([`${formatMoney(larger)} − ${formatMoney(smaller)} = ${formatMoney(Math.abs(net))}, ${outcome}`]);
  } else if (net !== 0) {
    sections.push([`In total, ${owing}.`]);
  } else {
    sections.push(['Nothing needs to change hands.']);
  }
  return sections.map((section) => section.join('\n')).join('\n\n');
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
