import { describe, expect, it } from 'vitest';

import type { Event } from '../../types/calendar';

import { eventBox, gridHoursFor, laneStyle, nowOffset, overlapLanes } from './timeGrid';

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

  describe('overlapLanes', () => {
    it('puts overlapping events side by side and leaves a lone event full width', () => {
      const lanes = overlapLanes([
        timed('15:30', '16:30'),
        timed('16:00', '17:00'),
        timed('16:15', '16:45'),
        timed('18:00', '19:00'),
      ]);

      expect(lanes.get('15:30')).toEqual({ lane: 0, lanes: 3 });
      expect(lanes.get('16:00')).toEqual({ lane: 1, lanes: 3 });
      expect(lanes.get('16:15')).toEqual({ lane: 2, lanes: 3 });
      expect(lanes.get('18:00')).toEqual({ lane: 0, lanes: 1 });
    });

    it('reuses a lane once the event in it has finished', () => {
      const lanes = overlapLanes([
        timed('09:00', '10:00'),
        timed('09:30', '11:00'),
        timed('10:00', '10:30'),
      ]);

      expect(lanes.get('09:00')).toEqual({ lane: 0, lanes: 2 });
      expect(lanes.get('09:30')).toEqual({ lane: 1, lanes: 2 });
      expect(lanes.get('10:00')).toEqual({ lane: 0, lanes: 2 });
    });

    it('treats back-to-back events as overlapping when their drawn boxes would touch', () => {
      const short = [timed('18:00', '18:10'), timed('18:10', '18:40')];

      expect(overlapLanes(short).get('18:10')).toEqual({ lane: 0, lanes: 1 });
      expect(overlapLanes(short, 30).get('18:10')).toEqual({ lane: 1, lanes: 2 });
    });
  });

  it('sizes a box to its lane inside the column insets', () => {
    expect(laneStyle(undefined, 4, 4)).toEqual({
      left: 'calc(4px + (100% - 8px) * 0)',
      width: 'calc((100% - 8px) / 1 - 0px)',
    });
    expect(laneStyle({ lane: 1, lanes: 2 }, 80, 16)).toEqual({
      left: 'calc(80px + (100% - 96px) * 0.5)',
      width: 'calc((100% - 96px) / 2 - 2px)',
    });
  });
});
