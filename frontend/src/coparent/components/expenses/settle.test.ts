import { describe, expect, it } from 'vitest';

import type { ExpenseSettlement } from '../../types/expenses';

import { byDate, methodLabel, netOf, settleable, settleMessage, settlementState, shareLine } from './settle';
import { testExpense } from './testExpense';

const agreed = { status: 'agreed' as const, requestedBy: 'alex', respondedBy: 'sam', respondedAt: null, note: null };
const status = (value: 'outstanding' | 'claimed' | 'reimbursed') => ({
  status: value,
  claimedBy: null,
  claimedAt: null,
  settledBy: null,
  settledAt: null,
  note: null,
  settlementId: null,
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

  it('reads where a settle-up stands from the expenses it paid back', () => {
    const settlement: ExpenseSettlement = {
      id: 'set-1',
      familyId: 'fam-1',
      fromParentId: 'sam',
      toParentId: 'alex',
      amountPence: 4000,
      currency: 'GBP',
      payments: [{ method: 'bank_transfer', amountPence: 4000, paidOn: '2026-10-07', reference: null }],
      expenseIds: ['c'],
      recordedBy: 'sam',
      createdAt: '2026-10-07T10:00:00Z',
    };
    const linked = { ...alreadySent, reimbursement: { ...status('claimed'), settlementId: 'set-1' } };
    expect(settlementState(settlement, [linked], 'alex')).toBe('awaitingMe');
    expect(settlementState(settlement, [linked], 'sam')).toBe('awaitingThem');
    const done = { ...linked, reimbursement: { ...status('reimbursed'), settlementId: 'set-1' } };
    expect(settlementState(settlement, [done], 'alex')).toBe('settled');
    expect(methodLabel('bank_transfer')).toBe('Bank transfer');
  });

  describe('the breakdown', () => {
    const now = new Date('2026-10-12T12:00:00');
    const tutor = testExpense({
      ...owedToSam,
      id: 'tutor',
      title: 'Tutor',
      date: '2026-10-01',
      amountPence: 6000,
      creditorParentId: 'sam',
      owedPence: 3000,
      shares: [
        { parentId: 'alex', percent: 50, sharePence: 3000 },
        { parentId: 'sam', percent: 50, sharePence: 3000 },
      ],
    });
    const party = testExpense({
      ...owedToAlex,
      id: 'party',
      title: 'Party',
      date: '2026-09-28',
      amountPence: 15000,
      owedPence: 6000,
      shares: [
        { parentId: 'alex', percent: 60, sharePence: 9000 },
        { parentId: 'sam', percent: 40, sharePence: 6000 },
      ],
    });
    const shoes = { ...alreadySent, id: 'shoes', title: 'Shoes', date: '2026-10-05' };

    it('describes the whole bill and how it was shared, mine first', () => {
      expect(shareLine(party, 'alex', 'Sam')).toBe('£150.00 total · you 60%, Sam 40%');
      expect(shareLine({ ...party, shares: [...party.shares].reverse() }, 'alex', 'Sam')).toBe('£150.00 total · you 60%, Sam 40%');
    });

    it('lists oldest first', () => {
      expect(byDate([tutor, shoes, party]).map((expense) => expense.id)).toEqual(['party', 'tutor', 'shoes']);
    });

    it('shows the working: each side, its subtotal, and the subtraction', () => {
      expect(settleMessage([tutor, party, shoes], 'alex', 'Sam', now)).toBe(
        [
          "Hi Sam, here's where we are with the shared expenses.",
          '',
          'What I owe you (my share of what you paid):',
          '• Thu 1 Oct, Tutor: £60.00 total, my 50% is £30.00',
          'Subtotal: £30.00',
          '',
          'What you owe me (your share of what I paid):',
          '• Mon 28 Sept, Party: £150.00 total, your 40% is £60.00',
          'Subtotal: £60.00',
          '',
          'Already paid back by you, so not counted here:',
          '• Mon 5 Oct, Shoes: £40.00',
          '',
          '£60.00 − £30.00 = £30.00, so you owe me £30.00.',
        ].join('\n'),
      );
    });

    it('agrees with the net the drawer settles', () => {
      const items = [tutor, party, shoes];
      expect(netOf(items, 'alex')).toBe(3000);
      expect(settleMessage(items, 'alex', 'Sam', now)).toContain('you owe me £30.00');
    });

    it('skips the subtraction when only one side owes, and says when nothing moves', () => {
      const lastLine = (text: string) => text.split('\n').pop();
      expect(lastLine(settleMessage([tutor], 'alex', 'Sam', now))).toBe('In total, I owe you £30.00.');
      expect(lastLine(settleMessage([shoes], 'alex', 'Sam', now))).toBe('Nothing needs to change hands.');
      const even = { ...party, owedPence: 3000 };
      expect(lastLine(settleMessage([tutor, even], 'alex', 'Sam', now))).toBe(
        "£30.00 − £30.00 = £0.00, so we're square.",
      );
      expect(settleMessage([], 'alex', 'Sam', now)).toBe('');
    });
  });
});
