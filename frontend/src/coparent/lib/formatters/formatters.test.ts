import { describe, expect, it } from 'vitest';

import { formatDateTime, formatLongDate, formatShortDate, formatTime, toDate } from './date';
import { pluralise } from './text';

const NOW = new Date(2026, 9, 10, 9, 0);

describe('formatShortDate', () => {
  it('leaves the year off a date in the current year', () => {
    expect(formatShortDate('2026-10-10', NOW)).toBe('Sat 10 Oct');
  });

  it('adds the year to a date in another year', () => {
    expect(formatShortDate('2025-10-10', NOW)).toBe('Fri 10 Oct 2025');
    expect(formatShortDate('2027-01-04', NOW)).toBe('Mon 4 Jan 2027');
  });

  it('reads an ISO date-time and a Date', () => {
    expect(formatShortDate('2026-10-13T18:00:00', NOW)).toBe('Tue 13 Oct');
    expect(formatShortDate(new Date(2026, 9, 13, 18), NOW)).toBe('Tue 13 Oct');
  });

  it('keeps a bare date on its day, whatever the time zone', () => {
    expect(toDate('2026-03-29')?.getDate()).toBe(29);
  });

  it('returns empty for nothing and unreadable text as it is', () => {
    expect(formatShortDate(undefined, NOW)).toBe('');
    expect(formatShortDate('', NOW)).toBe('');
    expect(formatShortDate('next week', NOW)).toBe('next week');
  });
});

describe('formatLongDate', () => {
  it('writes the whole date, year included, with no commas', () => {
    expect(formatLongDate('2026-10-10')).toBe('Saturday 10 October 2026');
    expect(formatLongDate('2015-04-22')).toBe('Wednesday 22 April 2015');
  });

  it('returns empty for nothing and unreadable text as it is', () => {
    expect(formatLongDate(null)).toBe('');
    expect(formatLongDate('soon')).toBe('soon');
  });
});

describe('formatTime and formatDateTime', () => {
  it('uses the 24-hour clock', () => {
    expect(formatTime(new Date(2026, 9, 10, 14, 5))).toBe('14:05');
    expect(formatTime(new Date(2026, 9, 10, 9, 0))).toBe('09:00');
  });

  it('puts the short date before the time', () => {
    expect(formatDateTime(new Date(2026, 9, 10, 14, 5), NOW)).toBe('Sat 10 Oct, 14:05');
    expect(formatDateTime(new Date(2025, 11, 24, 8, 30), NOW)).toBe('Wed 24 Dec 2025, 08:30');
  });
});

describe('pluralise', () => {
  it('uses the singular for exactly one', () => {
    expect(pluralise(1, 'conversation')).toBe('1 conversation');
  });

  it('uses the plural for none and for many', () => {
    expect(pluralise(0, 'conversation')).toBe('0 conversations');
    expect(pluralise(3, 'day')).toBe('3 days');
  });

  it('takes an irregular plural', () => {
    expect(pluralise(1, 'child', 'children')).toBe('1 child');
    expect(pluralise(2, 'child', 'children')).toBe('2 children');
  });
});
