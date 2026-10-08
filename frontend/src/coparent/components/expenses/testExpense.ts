import type { Expense } from '../../types/expenses';

/** An expense as the API returns it, for tests. Alex is "me" unless a test says otherwise. */
export function testExpense(overrides: Partial<Expense> = {}): Expense {
  return {
    id: 'exp-1',
    familyId: 'fam-1',
    title: 'School shoes',
    category: 'clothing',
    childIds: ['child-1'],
    amountPence: 4500,
    currency: 'GBP',
    timing: 'paid',
    date: '2026-10-03',
    payerId: 'alex',
    shares: [
      { parentId: 'alex', percent: 50, sharePence: 2250 },
      { parentId: 'sam', percent: 50, sharePence: 2250 },
    ],
    agreement: { status: 'pending', requestedBy: 'alex', respondedBy: null, respondedAt: null, note: null },
    reimbursement: { status: 'none', claimedBy: null, claimedAt: null, settledBy: null, settledAt: null, note: null },
    owedPence: 2250,
    debtorParentId: 'sam',
    creditorParentId: 'alex',
    countsTowardsBalance: false,
    receipts: [],
    notes: null,
    history: [{ at: '2026-10-03T17:04:00Z', by: 'alex', action: 'create', note: null }],
    version: 1,
    createdBy: 'alex',
    createdAt: '2026-10-03T17:04:00Z',
    updatedAt: '2026-10-03T17:04:00Z',
    ...overrides,
  };
}
