import { describe, expect, it } from 'vitest';

import type { Event } from '../../types/calendar';

import { eventBox, gridHoursFor, nowOffset } from './timeGrid';

const timed = (startTime: string, endTime?: string): Event => ({
  id: startTime,
  type: 'activity',
  title: 'Session',
  startDate: '2026-10-06',
  startTime,
  endTime,
  allDay: false,
  parentId: null,
  childIds: ['child'],
  notes: null,
  recurring: null,
});

const hours = { startHour: 7, endHour: 21 };

describe('timeGrid', () => {
  it('places an event by its own start time', () => {
    // 18:00 is eleven rows below a 7 AM start: at 64px an hour, 704px.
    expect(eventBox(timed('18:00', '19:00'), hours, 64, 24)).toEqual({ top: 704, height: 64 });
  });

  it('widens the grid so early and late events are drawn rather than clipped', () => {
    expect(gridHoursFor([timed('06:30', '07:00'), timed('21:00', '22:15')], hours)).toEqual({
      startHour: 6,
      endHour: 23,
    });
    expect(gridHoursFor([timed('18:00', '19:00')], hours)).toEqual(hours);
  });

  it('keeps a box inside the grid even when it would overflow', () => {
    const box = eventBox(timed('20:50', '20:55'), hours, 64, 24);
    expect(box).not.toBeNull();
    expect((box?.top ?? 0) + (box?.height ?? 0)).toBeLessThanOrEqual((21 - 7) * 64);
  });

  it('gives an event that ends before it starts a visible box', () => {
    expect(eventBox(timed('18:00', '17:00'), hours, 64, 24)?.height).toBe(32);
  });

  it('draws the current-time line only inside the grid hours', () => {
    expect(nowOffset(new Date(2026, 9, 6, 9, 30), hours, 64)).toBe(160);
    expect(nowOffset(new Date(2026, 9, 6, 22, 0), hours, 64)).toBeNull();
  });
});
