import { describe, expect, it } from 'vitest';

import type { Event } from '../../types/calendar';

import {
  briefingChildren,
  custodyParentOn,
  headlinePredicate,
  isFinished,
  itemsByDay,
  joinNames,
  nextHandover,
  stripChildPrefix,
} from './briefing';

const [ethan, ava] = briefingChildren([
  { id: 'ethan', fullName: 'Ethan Rowe' },
  { id: 'ava', fullName: 'Ava Rowe' },
]);

const event = (overrides: Partial<Event>): Event => ({
  id: 'event',
  type: 'activity',
  title: 'Ethan - Football training',
  startDate: '2026-10-10T00:00:00Z',
  endDate: '2026-10-10T00:00:00Z',
  startTime: '09:00',
  endTime: '10:15',
  allDay: false,
  parentId: null,
  childIds: ['ethan'],
  notes: null,
  recurring: null,
  ...overrides,
});

describe('briefing', () => {
  it('gives each child their own colour, in family order', () => {
    expect([ethan.firstName, ethan.tone, ava.firstName, ava.tone]).toEqual(['Ethan', 0, 'Ava', 1]);
  });

  it.each([
    ['Ethan - Football training', [ethan], 'Football training'],
    ['Ethan: Cricket', [ethan], 'Cricket'],
    ['Ethan Football', [ethan], 'Ethan Football'],
    ['Ethanol safety talk', [ethan], 'Ethanol safety talk'],
    ['Ethan - Football training', [ethan, ava], 'Ethan - Football training'],
    ['Ava - Swimming', [ethan], 'Ava - Swimming'],
  ])('reads "%s" as its activity only when the prefix is the child it is for', (title, kids, expected) => {
    expect(stripChildPrefix(title, kids)).toBe(expected);
  });

  it('lists each day from first to last, all-day items first and then by time', () => {
    const days = itemsByDay(
      [
        event({ id: 'swim', title: 'Ava - Swimming', childIds: ['ava'], startTime: '18:30', endTime: '19:00' }),
        event({ id: 'inset', title: 'INSET day', childIds: [], allDay: true, startTime: undefined }),
        event({ id: 'football' }),
        event({ id: 'handover', type: 'Custody', title: 'With Simon' }),
      ],
      [ethan, ava],
      '2026-10-10',
      '2026-10-12',
    );

    expect(Array.from(days.keys())).toEqual(['2026-10-10', '2026-10-11', '2026-10-12']);
    expect(days.get('2026-10-10')?.map((item) => item.title)).toEqual([
      'INSET day',
      'Football training',
      'Swimming',
    ]);
    expect(days.get('2026-10-11')).toEqual([]);
  });

  it('expands a weekly series and keeps the date each occurrence opens', () => {
    const days = itemsByDay(
      [event({ id: 'swim', startDate: '2026-09-28T00:00:00Z', endDate: undefined, recurring: { frequency: 'weekly', days: ['monday'] } })],
      [ethan],
      '2026-10-10',
      '2026-10-16',
    );

    expect(days.get('2026-10-12')?.[0]).toMatchObject({ eventId: 'swim', occurrence: '2026-10-12' });
  });

  it('counts an item as done once its end time has passed', () => {
    const [football] = itemsByDay([event({})], [ethan], '2026-10-10', '2026-10-10').get('2026-10-10') ?? [];

    expect(isFinished(football, new Date(2026, 9, 10, 10, 0))).toBe(false);
    expect(isFinished(football, new Date(2026, 9, 10, 10, 15))).toBe(true);
  });

  it('reads who has the children from custody events, block or weekly', () => {
    const events = [
      event({ id: 'block', type: 'custody', parentId: 'rhian', startDate: '2026-10-12T00:00:00Z', endDate: '2026-10-14T00:00:00Z' }),
      event({
        id: 'weekends',
        type: 'custody',
        parentIds: ['simon'],
        startDate: '2026-10-03T00:00:00Z',
        endDate: '2026-12-19T00:00:00Z',
        recurring: { frequency: 'weekly', days: ['saturday', 'sunday'] },
      }),
    ];

    expect(custodyParentOn(events, '2026-10-10')).toBe('simon');
    expect(custodyParentOn(events, '2026-10-13')).toBe('rhian');
    // The weekly series runs to December, but each occurrence is its own day only.
    expect(custodyParentOn(events, '2026-10-15')).toBeNull();
    expect(nextHandover(events, '2026-10-10')).toEqual({ date: '2026-10-12', parentId: 'rhian' });
  });

  it('finds no handover when nobody has custody days', () => {
    expect(nextHandover([event({})], '2026-10-10')).toBeNull();
  });

  it('says who has the children when custody says, and otherwise what is left of the day', () => {
    expect(headlinePredicate({ childCount: 2, custodyParentName: 'you', remainingToday: 3 })).toBe('are with you.');
    expect(headlinePredicate({ childCount: 1, custodyParentName: 'Rhian', remainingToday: 0 })).toBe('is with Rhian.');
    expect(headlinePredicate({ childCount: 2, custodyParentName: null, remainingToday: 0 })).toBe(
      'have nothing else on today.',
    );
    expect(headlinePredicate({ childCount: 1, custodyParentName: null, remainingToday: 1 })).toBe(
      'has one more thing today.',
    );
    expect(headlinePredicate({ childCount: 2, custodyParentName: null, remainingToday: 2 })).toBe(
      'have 2 more things today.',
    );
  });

  it('joins names the way a sentence would', () => {
    expect([joinNames(['Ethan']), joinNames(['Ethan', 'Ava']), joinNames(['Ethan', 'Ava', 'Mia'])]).toEqual([
      'Ethan',
      'Ethan and Ava',
      'Ethan, Ava and Mia',
    ]);
  });
});
