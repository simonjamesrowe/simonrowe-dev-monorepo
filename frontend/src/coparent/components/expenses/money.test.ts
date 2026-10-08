import { describe, expect, it } from 'vitest';

import { describeExpense, formatMoney, parsePounds, percentOf, previewShares } from './money';

describe('money', () => {
  it('formats pence as pounds sterling, never dollars', () => {
    expect(formatMoney(4500)).toBe('£45.00');
    expect(formatMoney(123450)).toBe('£1,234.50');
    expect(formatMoney(1)).toBe('£0.01');
    expect(formatMoney(4500)).not.toContain('$');
  });

  it.each([
    ['45', 4500],
    ['45.5', 4550],
    ['45.50', 4550],
    ['£12.80', 1280],
    [' 0.01 ', 1],
  ])('parses %s as %d pence', (typed, pence) => {
    expect(parsePounds(typed)).toBe(pence);
  });

  it.each(['', '0', '0.00', '45.505', '-3', '1e3', '$40', 'abc', '1234567'])(
    'refuses %j',
    (typed) => {
      expect(parsePounds(typed)).toBeNull();
    },
  );

  it('rounds the owed share half up, exactly like the server', () => {
    expect(percentOf(8999, 50)).toBe(4500);
    expect(percentOf(12345, 40)).toBe(4938);
  });

  it('keeps both shares summing to the amount', () => {
    for (const payer of ['me', 'them', 'undecided'] as const) {
      const preview = previewShares(8999, 50, payer);
      expect(preview.mine + preview.theirs).toBe(8999);
    }
    expect(previewShares(4500, 50, 'me').owedToMe).toBe(2250);
    expect(previewShares(6000, 50, 'them').owedToMe).toBe(-3000);
    expect(previewShares(1800, 50, 'undecided').owedToMe).toBe(0);
  });

  it('spells out who owes whom for every combination', () => {
    const base = { otherName: 'Sam', myPercent: 50 };
    expect(describeExpense({ ...base, amountPence: 4500, payer: 'me', timing: 'paid' })).toEqual({
      tone: 'owed',
      lead: 'You paid £45.00. Your share is £22.50.',
      outcome: 'Sam will owe you £22.50 once Sam agrees.',
    });
    expect(describeExpense({ ...base, amountPence: 6000, payer: 'them', timing: 'paid' }).outcome).toBe(
      "You'll owe Sam £30.00 once Sam agrees.",
    );
    expect(describeExpense({ ...base, myPercent: 100, amountPence: 4500, payer: 'me', timing: 'paid' }).outcome).toBe(
      'Nothing is owed. Sam will still be asked to agree.',
    );
    expect(
      describeExpense({ ...base, amountPence: 24000, payer: 'them', timing: 'upcoming', dueLabel: '20 Oct' }).lead,
    ).toBe("Sam will pay £240.00 when it's due on 20 Oct. Your share will be £120.00.");
    expect(describeExpense({ ...base, amountPence: 1800, payer: 'undecided', timing: 'upcoming' }).outcome).toBe(
      "Your share will be £9.00, Sam's £9.00.",
    );
    expect(describeExpense({ ...base, amountPence: null, payer: 'me', timing: 'paid' }).lead).toBe(
      'Enter an amount to see who owes what.',
    );
  });
});
