import { describe, expect, it } from 'vitest';

import { netOf, settleable } from './settle';
import { testExpense } from './testExpense';

const agreed = { status: 'agreed' as const, requestedBy: 'alex', respondedBy: 'sam', respondedAt: null, note: null };
const status = (value: 'outstanding' | 'claimed' | 'reimbursed') => ({
  status: value,
  claimedBy: null,
  claimedAt: null,
  settledBy: null,
  settledAt: null,
  note: null,
});

describe('settle up', () => {
  const owedToAlex = testExpense({ id: 'a', agreement: agreed, reimbursement: status('outstanding'), countsTowardsBalance: true, owedPence: 3245 });
  const owedToSam = testExpense({
    id: 'b',
    payerId: 'sam',
    debtorParentId: 'alex',
    agreement: agreed,
    reimbursement: status('outstanding'),
    countsTowardsBalance: true,
    owedPence: 3000,
  });
  const alreadySent = testExpense({ id: 'c', agreement: agreed, reimbursement: status('claimed'), countsTowardsBalance: true, owedPence: 4000 });
  const sentByAlex = testExpense({
    id: 'd',
    payerId: 'sam',
    debtorParentId: 'alex',
    agreement: agreed,
    reimbursement: status('claimed'),
    countsTowardsBalance: true,
  });

  it('offers outstanding debts both ways and repayments waiting on my confirmation', () => {
    expect(settleable([owedToAlex, owedToSam, alreadySent, sentByAlex], 'alex').map((expense) => expense.id)).toEqual([
      'a',
      'b',
      'c',
    ]);
  });

  it('nets the debts and never counts money that has already been sent', () => {
    expect(netOf([owedToAlex, owedToSam, alreadySent], 'alex')).toBe(245);
    expect(netOf([owedToSam], 'alex')).toBe(-3000);
    expect(netOf([alreadySent], 'alex')).toBe(0);
  });
});
