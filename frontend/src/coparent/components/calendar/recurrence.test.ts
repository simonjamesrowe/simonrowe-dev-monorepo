import { describe, expect, it } from 'vitest';

import type { Event } from '../../types/calendar';

import { expandRecurringEvents, occurrenceTarget } from './recurrence';

const weekly = (overrides: Partial<Event>): Event => ({
  id: 'series',
  type: 'activity',
  title: 'Football training',
  startDate: '2026-09-29T00:00:00Z',
  startTime: '18:00',
  endTime: '19:00',
  allDay: false,
  parentId: null,
  childIds: ['ethan'],
  notes: null,
  recurring: { frequency: 'weekly', days: ['tuesday'] },
  ...overrides,
});

const startDates = (events: Event[]) => events.map((event) => event.startDate);

describe('expandRecurringEvents', () => {
  it('repeats a series with no end date instead of showing only its first occurrence', () => {
    const expanded = expandRecurringEvents([weekly({})], '2026-10-04', '2026-10-31');

    expect(startDates(expanded)).toEqual(['2026-10-06', '2026-10-13', '2026-10-20', '2026-10-27']);
    expect(expanded.every((event) => event.endDate === event.startDate)).toBe(true);
  });

  it('stops a bounded series at its end date', () => {
    const expanded = expandRecurringEvents(
      [weekly({ endDate: '2026-10-13T00:00:00Z' })],
      '2026-09-27',
      '2026-10-31',
    );

    expect(startDates(expanded)).toEqual(['2026-09-29', '2026-10-06', '2026-10-13']);
  });

  it.each([['TUE'], ['TU'], ['Tuesday'], ['tues']])(
    'renders a series whose day is spelled %s',
    (day) => {
      const expanded = expandRecurringEvents(
        [weekly({ recurring: { frequency: 'weekly', days: [day] } })],
        '2026-10-04',
        '2026-10-10',
      );

      expect(startDates(expanded)).toEqual(['2026-10-06']);
    },
  );

  it('falls back to the start weekday when no day is recognisable', () => {
    const expanded = expandRecurringEvents(
      [weekly({ recurring: { frequency: 'weekly', days: ['someday'] } })],
      '2026-10-04',
      '2026-10-10',
    );

    expect(startDates(expanded)).toEqual(['2026-10-06']);
  });

  it('shows nothing before an open-ended series starts', () => {
    expect(expandRecurringEvents([weekly({})], '2026-09-01', '2026-09-28')).toEqual([]);
  });

  it('leaves out skipped occurrences and keeps the rest of the series', () => {
    const expanded = expandRecurringEvents(
      [
        weekly({
          recurring: { frequency: 'weekly', days: ['tuesday'], excludedDates: ['2026-10-13'] },
        }),
      ],
      '2026-10-04',
      '2026-10-24',
    );

    expect(startDates(expanded)).toEqual(['2026-10-06', '2026-10-20']);
  });

  it('points a click on an occurrence at the stored series and the occurrence date', () => {
    const [occurrence] = expandRecurringEvents([weekly({})], '2026-10-04', '2026-10-10');

    expect(occurrenceTarget(occurrence)).toEqual(['series', '2026-10-06']);
    expect(occurrenceTarget(weekly({ recurring: null }))).toEqual(['series', undefined]);
  });
});
