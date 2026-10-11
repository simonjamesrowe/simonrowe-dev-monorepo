import { describe, expect, it } from 'vitest';

import { canDelete, canEdit, expenseStatus, inDateRange, inView } from './expenseStatus';
import { testExpense } from './testExpense';

const TODAY = '2026-10-08';
const asAlex = (overrides = {}) => expenseStatus(testExpense(overrides), 'alex', 'Sam', { today: TODAY });
const asSam = (overrides = {}) => expenseStatus(testExpense(overrides), 'sam', 'Alex', { today: TODAY });

describe('expenseStatus', () => {
  it('shows a pending expense as waiting to its sender and needing an OK to the other parent', () => {
    expect(asAlex()).toMatchObject({ label: 'Waiting for Sam', needsAction: false, actions: [] });
    expect(asSam()).toMatchObject({ label: 'Needs your OK', tone: 'amber', needsAction: true, actions: ['dispute', 'agree'] });
  });

  it('asks the parent here to agree to everything until the co-parent joins', () => {
    const beforeJoining = (overrides = {}) =>
      expenseStatus(testExpense(overrides), 'alex', 'Sam', { today: TODAY, coparentJoined: false });
    expect(beforeJoining()).toMatchObject({ label: 'Needs your OK', needsAction: true, actions: ['agree'] });
    expect(beforeJoining({ payerId: 'sam' })).toMatchObject({ needsAction: true, actions: ['agree'] });
    expect(inView('needs', testExpense(), 'alex', 'Sam', false)).toBe(true);
    expect(inView('needs', testExpense(), 'alex', 'Sam')).toBe(false);
  });

  it('lets either parent agree to what one logged for the other, until the other has acted on it', () => {
    const forSam = { payerId: 'sam', debtorParentId: 'alex', creditorParentId: 'sam' };
    expect(asAlex(forSam)).toMatchObject({ label: 'Waiting for Sam', needsAction: false, actions: ['agree'] });
    expect(asSam(forSam)).toMatchObject({ label: 'Needs your OK', actions: ['dispute', 'agree'] });
    const touched = {
      ...forSam,
      history: [
        { at: '2026-10-03T17:04:00Z', by: 'alex', action: 'create', note: null },
        { at: '2026-10-04T09:00:00Z', by: 'sam', action: 'dispute', note: 'More' },
        { at: '2026-10-04T10:00:00Z', by: 'alex', action: 'update', note: null },
      ],
    };
    expect(asAlex(touched)).toMatchObject({ actions: [] });
  });

  it('asks the sender of a disputed expense to fix it', () => {
    const disputed = {
      agreement: { status: 'disputed' as const, requestedBy: 'alex', respondedBy: 'sam', respondedAt: null, note: 'No' },
    };
    expect(asAlex(disputed)).toMatchObject({ label: 'Sam disputed', needsAction: true, actions: ['edit'] });
    expect(asSam(disputed)).toMatchObject({ label: 'You disputed', needsAction: false });
  });

  const agreed = { status: 'agreed' as const, requestedBy: 'alex', respondedBy: 'sam', respondedAt: null, note: null };
  const reimbursement = (status: 'outstanding' | 'claimed' | 'reimbursed' | 'none') => ({
    status,
    claimedBy: null,
    claimedAt: null,
    settledBy: null,
    settledAt: null,
    note: null,
    settlementId: null,
  });

  it('shows an outstanding debt from both sides', () => {
    const owed = { agreement: agreed, reimbursement: reimbursement('outstanding'), countsTowardsBalance: true };
    expect(asAlex(owed)).toMatchObject({ label: 'Sam owes you £22.50', tone: 'teal', needsAction: false, actions: ['markReimbursed'] });
    expect(asSam(owed)).toMatchObject({ label: 'You owe Alex £22.50', tone: 'rose', needsAction: true, actions: ['claim'] });
  });

  it('asks the payer to confirm a claimed repayment', () => {
    const claimed = { agreement: agreed, reimbursement: reimbursement('claimed'), countsTowardsBalance: true };
    expect(asAlex(claimed)).toMatchObject({ label: 'Sam says paid · confirm', needsAction: true, actions: ['reject', 'confirm'] });
    expect(asSam(claimed)).toMatchObject({ label: 'Paid back · awaiting Alex', needsAction: false });
  });

  it('settles, and says when nothing was owed', () => {
    expect(asAlex({ agreement: agreed, reimbursement: reimbursement('reimbursed') }).label).toBe('Settled');
    expect(asAlex({ agreement: agreed, reimbursement: reimbursement('none'), owedPence: 0 }).label).toBe(
      'No reimbursement needed',
    );
  });

  it('marks an agreed upcoming expense due or overdue', () => {
    expect(asAlex({ agreement: agreed, timing: 'upcoming', date: '2026-10-20' })).toMatchObject({
      label: 'Agreed · due 20 Oct',
      actions: ['markPaid'],
    });
    expect(asAlex({ agreement: agreed, timing: 'upcoming', date: '2026-10-01' }).label).toBe('Overdue');
  });

  it('puts each expense on the right tabs', () => {
    const outstanding = testExpense({ agreement: agreed, reimbursement: reimbursement('outstanding'), countsTowardsBalance: true });
    expect(inView('owed', outstanding, 'alex', 'Sam')).toBe(true);
    expect(inView('needs', outstanding, 'alex', 'Sam')).toBe(false);
    expect(inView('needs', outstanding, 'sam', 'Alex')).toBe(true);
    expect(inView('upcoming', testExpense({ timing: 'upcoming' }), 'alex', 'Sam')).toBe(true);
    expect(inView('settled', testExpense({ agreement: agreed, reimbursement: reimbursement('reimbursed') }), 'alex', 'Sam')).toBe(true);
  });

  it('never lets an agreed debt be deleted, or a repayment in progress be edited', () => {
    expect(canDelete(testExpense(), 'alex')).toBe(true);
    expect(canDelete(testExpense(), 'sam')).toBe(false);
    expect(canDelete(testExpense({ agreement: agreed }), 'alex')).toBe(false);
    expect(canDelete(testExpense({ agreement: agreed, timing: 'upcoming' }), 'sam')).toBe(true);
    expect(canEdit(testExpense({ reimbursement: reimbursement('claimed') }))).toBe(false);
    expect(canEdit(testExpense({ reimbursement: reimbursement('outstanding') }))).toBe(true);
  });
});

describe('inDateRange', () => {
  it('includes both end dates, and either bound can be left open', () => {
    expect(inDateRange('2026-10-01', '2026-10-01', '2026-10-31')).toBe(true);
    expect(inDateRange('2026-10-31', '2026-10-01', '2026-10-31')).toBe(true);
    expect(inDateRange('2026-09-30', '2026-10-01', '2026-10-31')).toBe(false);
    expect(inDateRange('2026-11-01', '2026-10-01', '2026-10-31')).toBe(false);
    expect(inDateRange('2020-01-01', '', '2026-10-31')).toBe(true);
    expect(inDateRange('2030-01-01', '2026-10-01', '')).toBe(true);
    expect(inDateRange('2026-10-08', '', '')).toBe(true);
  });

  it('reads bounds entered the wrong way round the right way round', () => {
    expect(inDateRange('2026-10-15', '2026-10-31', '2026-10-01')).toBe(true);
    expect(inDateRange('2026-11-15', '2026-10-31', '2026-10-01')).toBe(false);
  });
});
