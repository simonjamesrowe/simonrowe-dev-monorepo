import { describe, expect, it } from 'vitest';

import { formatBatchDate } from './batchDate';

const now = new Date('2026-10-10T12:00:00');

describe('formatBatchDate', () => {
  it('reads like the rest of the app, with no year this year', () => {
    expect(formatBatchDate('2026-10-10T09:15:00', now)).toBe('Sat 10 Oct');
    expect(formatBatchDate('2026-01-05T09:15:00', now)).toBe('Mon 5 Jan');
  });

  it('adds the year, without a comma, for another year', () => {
    expect(formatBatchDate('2025-10-10T09:15:00', now)).toBe('Fri 10 Oct 2025');
  });

  it('gives back what it cannot read', () => {
    expect(formatBatchDate('soon', now)).toBe('soon');
  });
});
