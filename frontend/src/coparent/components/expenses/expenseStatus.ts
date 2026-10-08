import type { Expense } from '../../types/expenses';

import { formatMoney } from './money';

export type StatusTone = 'slate' | 'amber' | 'rose' | 'teal' | 'emerald';

export type ExpenseActionKey =
  | 'agree'
  | 'dispute'
  | 'edit'
  | 'markPaid'
  | 'claim'
  | 'markReimbursed'
  | 'confirm'
  | 'reject';

export interface ExpenseStatus {
  label: string;
  tone: StatusTone;
  /** Waiting on the signed-in parent: shown on the "Needs action" tab and the nav badge. */
  needsAction: boolean;
  /** The next steps open to the signed-in parent, most important last. */
  actions: ExpenseActionKey[];
}

const shortDate = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });

/**
 * What an expense looks like to one parent. The same expense reads "Waiting for Sam" to Alex and
 * "Needs your OK" to Sam; this is the single place that decides which.
 */
export function expenseStatus(
  expense: Expense,
  me: string,
  otherName: string,
  today: string = new Date().toISOString().slice(0, 10),
): ExpenseStatus {
  const { agreement, reimbursement } = expense;
  const requester = agreement.requestedBy === me;
  if (agreement.status === 'pending') {
    return requester
      ? { label: `Waiting for ${otherName}`, tone: 'slate', needsAction: false, actions: [] }
      : { label: 'Needs your OK', tone: 'amber', needsAction: true, actions: ['dispute', 'agree'] };
  }
  if (agreement.status === 'disputed') {
    return requester
      ? { label: `${otherName} disputed`, tone: 'rose', needsAction: true, actions: ['edit'] }
      : { label: 'You disputed', tone: 'rose', needsAction: false, actions: [] };
  }
  if (expense.timing === 'upcoming') {
    const overdue = expense.date < today;
    return {
      label: overdue ? 'Overdue' : `Agreed · due ${shortDate(expense.date)}`,
      tone: overdue ? 'amber' : 'teal',
      needsAction: false,
      actions: ['markPaid'],
    };
  }
  if (reimbursement.status === 'none') {
    return { label: 'No reimbursement needed', tone: 'emerald', needsAction: false, actions: [] };
  }
  if (reimbursement.status === 'reimbursed') {
    return { label: 'Settled', tone: 'emerald', needsAction: false, actions: [] };
  }
  const iOwe = expense.debtorParentId === me;
  const owed = formatMoney(expense.owedPence);
  if (reimbursement.status === 'outstanding') {
    return iOwe
      ? { label: `You owe ${otherName} ${owed}`, tone: 'rose', needsAction: true, actions: ['claim'] }
      : {
          label: `${otherName} owes you ${owed}`,
          tone: 'teal',
          needsAction: false,
          actions: ['markReimbursed'],
        };
  }
  return iOwe
    ? { label: `Paid back · awaiting ${otherName}`, tone: 'slate', needsAction: false, actions: [] }
    : {
        label: `${otherName} says paid · confirm`,
        tone: 'amber',
        needsAction: true,
        actions: ['reject', 'confirm'],
      };
}

export type ExpenseView = 'needs' | 'upcoming' | 'owed' | 'settled' | 'all';

export const EXPENSE_VIEWS: { key: ExpenseView; label: string; counted: boolean }[] = [
  { key: 'needs', label: 'Needs action', counted: true },
  { key: 'upcoming', label: 'Upcoming', counted: true },
  { key: 'owed', label: 'Owed', counted: true },
  { key: 'settled', label: 'Settled', counted: false },
  { key: 'all', label: 'All', counted: false },
];

/** Which tab an expense belongs on, from one parent's side. */
export function inView(view: ExpenseView, expense: Expense, me: string, otherName: string): boolean {
  switch (view) {
    case 'needs':
      return expenseStatus(expense, me, otherName).needsAction;
    case 'upcoming':
      return expense.timing === 'upcoming';
    case 'owed':
      return expense.countsTowardsBalance && expense.owedPence > 0;
    case 'settled':
      return (
        expense.timing === 'paid' &&
        expense.agreement.status === 'agreed' &&
        (expense.reimbursement.status === 'none' || expense.reimbursement.status === 'reimbursed')
      );
    default:
      return true;
  }
}

/** An upcoming expense can go at any time; a paid one only while unagreed, by its requester. */
export function canDelete(expense: Expense, me: string): boolean {
  if (expense.timing === 'upcoming') return true;
  return (
    expense.agreement.requestedBy === me &&
    (expense.agreement.status === 'pending' || expense.agreement.status === 'disputed')
  );
}

/** An expense being paid back, or settled, can no longer change. */
export function canEdit(expense: Expense): boolean {
  return expense.reimbursement.status !== 'claimed' && expense.reimbursement.status !== 'reimbursed';
}
