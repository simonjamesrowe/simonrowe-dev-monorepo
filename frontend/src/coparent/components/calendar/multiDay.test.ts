import { describe, expect, it } from 'vitest';

import type { Event } from '../../types/calendar';

import { eventOnDay, eventsOnDay, timeLabel } from './multiDay';
import { gridHoursFor } from './timeGrid';

const trip: Event = {
  id: 'pgl',
  type: 'school',
  title: 'Ethan - PGL',
  startDate: '2026-10-19',
  endDate: '2026-10-23',
  startTime: '09:30',
  endTime: '15:30',
  allDay: false,
  parentId: null,
  childIds: ['ethan'],
  location: 'Kilmorie School',
  notes: null,
  recurring: null,
};

describe('multiDay', () => {
  it('puts a Monday 09:30 to Friday 15:30 trip on every day from Monday to Friday', () => {
    const week = [
      '2026-10-18',
      '2026-10-19',
      '2026-10-20',
      '2026-10-21',
      '2026-10-22',
      '2026-10-23',
      '2026-10-24',
    ];
    const pieces = week.map((day) => eventOnDay(trip, day));

    expect(pieces.map((piece) => piece && [piece.startTime, piece.endTime])).toEqual([
      null,
      ['09:30', '24:00'],
      ['00:00', '24:00'],
      ['00:00', '24:00'],
      ['00:00', '24:00'],
      ['00:00', '15:30'],
      null,
    ]);
    expect(pieces.map((piece) => piece?.span?.part ?? null)).toEqual([
      null,
      'first',
      'middle',
      'middle',
      'middle',
      'last',
      null,
    ]);
  });

  it('keeps the id, so a click on any day opens the one stored event', () => {
    expect(eventOnDay(trip, '2026-10-21')?.id).toBe('pgl');
    expect(eventOnDay(trip, '2026-10-21')?.sourceId).toBeUndefined();
  });

  it('labels every day with both ends rather than the midnight edges it is drawn to', () => {
    const wednesday = eventOnDay(trip, '2026-10-21');
    expect(wednesday && timeLabel(wednesday)).toBe('Mon 09:30 – Fri 15:30');
  });

  it('shows an all-day multi-day event on each day it covers, unchanged', () => {
    const halfTerm = { ...trip, allDay: true, startTime: undefined, endTime: undefined };
    expect(eventOnDay(halfTerm, '2026-10-22')).toMatchObject({
      allDay: true,
      startTime: undefined,
    });
    expect(eventOnDay(halfTerm, '2026-10-24')).toBeNull();
  });

  it('does not draw a last day for an event that ends at midnight', () => {
    expect(eventOnDay({ ...trip, endTime: '00:00' }, '2026-10-23')).toBeNull();
  });

  it('reads ISO date strings as their calendar day', () => {
    const iso = { ...trip, startDate: '2026-10-19T00:00:00Z', endDate: '2026-10-23T00:00:00Z' };
    expect(eventOnDay(iso, '2026-10-20')?.span?.part).toBe('middle');
  });

  it('leaves single-day and repeating events on their own day', () => {
    const single = { ...trip, endDate: '2026-10-19' };
    expect(eventsOnDay([single], '2026-10-19')).toEqual([single]);
    expect(eventsOnDay([single], '2026-10-20')).toEqual([]);

    // A series' end date is when it stops repeating, not how long one occurrence lasts.
    const series = { ...trip, recurring: { frequency: 'weekly' as const, days: ['monday'] } };
    expect(eventOnDay(series, '2026-10-20')).toBeNull();
  });

  it('does not stretch the grid to midnight for the days in the middle of a trip', () => {
    const pieces = ['2026-10-19', '2026-10-20', '2026-10-23'].map((day) => eventOnDay(trip, day)!);
    expect(gridHoursFor(pieces, { startHour: 7, endHour: 21 })).toEqual({
      startHour: 7,
      endHour: 21,
    });
  });

  it('still widens the grid for a trip that really starts early or ends late', () => {
    const early = { ...trip, startTime: '05:30', endTime: '22:15' };
    const pieces = ['2026-10-19', '2026-10-23'].map((day) => eventOnDay(early, day)!);
    expect(gridHoursFor(pieces, { startHour: 7, endHour: 21 })).toEqual({
      startHour: 5,
      endHour: 23,
    });
  });
});
