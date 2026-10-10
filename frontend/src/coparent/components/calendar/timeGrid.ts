import { useEffect, useState } from 'react';

import type { Event } from '../../types/calendar';

/** The hours a day or week grid shows, end exclusive (`endHour` 21 means the last row is 8 PM). */
export interface GridHours {
  startHour: number;
  endHour: number;
}

const minutesOf = (time: string) => {
  const [hours = 0, minutes = 0] = time.split(':').map(Number);
  return (Number.isFinite(hours) ? hours : 0) * 60 + (Number.isFinite(minutes) ? minutes : 0);
};

const endMinutesOf = (event: Event) => {
  const start = minutesOf(event.startTime ?? '00:00');
  const end = event.endTime ? minutesOf(event.endTime) : start + 60;
  // An end at or before the start (a typo, or a session past midnight) still gets a visible box.
  return end > start ? end : start + 30;
};

/**
 * Widens the default range until every timed event on screen fits. A fixed 7 AM–8 PM grid put a
 * 6:30 AM swim or a 9 PM call outside the drawn area, where it was rendered and never seen.
 */
export const gridHoursFor = (events: Event[], defaults: GridHours): GridHours =>
  events.reduce<GridHours>(
    (range, event) => {
      if (!event.startTime) return range;
      return {
        startHour: Math.min(range.startHour, Math.floor(minutesOf(event.startTime) / 60)),
        endHour: Math.max(range.endHour, Math.min(24, Math.ceil(endMinutesOf(event) / 60))),
      };
    },
    { ...defaults },
  );

/**
 * Where a timed event sits, in pixels from the top of the grid. Rows, labels and boxes all take
 * their size from the same `pxPerHour`, so the three cannot drift apart.
 */
export const eventBox = (
  event: Event,
  hours: GridHours,
  pxPerHour: number,
  minHeight: number,
): { top: number; height: number } | null => {
  if (!event.startTime) return null;
  const gridStart = hours.startHour * 60;
  const gridEnd = hours.endHour * 60;
  const start = Math.min(Math.max(minutesOf(event.startTime), gridStart), gridEnd);
  const end = Math.min(Math.max(endMinutesOf(event), start), gridEnd);
  const top = ((start - gridStart) / 60) * pxPerHour;
  const height = Math.max(((end - start) / 60) * pxPerHour, minHeight);
  // Clamp the box itself too, so a minimum height cannot push it below the last row.
  const gridHeight = (hours.endHour - hours.startHour) * pxPerHour;
  return { top: Math.min(top, Math.max(0, gridHeight - height)), height };
};

/** Which side-by-side lane a timed event takes, out of how many its group of overlaps needs. */
export interface Lane {
  lane: number;
  lanes: number;
}

/**
 * Lays overlapping timed events side by side instead of on top of each other. Events that
 * overlap, directly or through a chain, share a group; each takes the first lane free when it
 * starts, and every event in the group is as wide as the group's widest moment allows.
 * `minMinutes` is the shortest box the grid draws, so two events whose boxes touch on screen
 * are treated as overlapping even when their times do not.
 */
export const overlapLanes = (events: Event[], minMinutes = 0): Map<string, Lane> => {
  const timed = events
    .filter((event) => event.startTime)
    .map((event) => {
      const start = minutesOf(event.startTime ?? '00:00');
      return { id: event.id, start, end: Math.max(endMinutesOf(event), start + minMinutes) };
    })
    .sort((left, right) => left.start - right.start || right.end - left.end);

  const lanes = new Map<string, Lane>();
  let group: { id: string; lane: number }[] = [];
  let laneEnds: number[] = [];
  let groupEnd = -1;

  const closeGroup = () => {
    group.forEach(({ id, lane }) => lanes.set(id, { lane, lanes: laneEnds.length }));
    group = [];
    laneEnds = [];
    groupEnd = -1;
  };

  timed.forEach((event) => {
    if (event.start >= groupEnd) closeGroup();
    let lane = laneEnds.findIndex((end) => end <= event.start);
    if (lane === -1) {
      lane = laneEnds.length;
      laneEnds.push(event.end);
    } else {
      laneEnds[lane] = event.end;
    }
    group.push({ id: event.id, lane });
    groupEnd = Math.max(groupEnd, event.end);
  });
  closeGroup();

  return lanes;
};

/**
 * The left offset and width of a box in its lane, inside a column that keeps `insetStart` and
 * `insetEnd` pixels clear on either side.
 */
export const laneStyle = (
  lane: Lane | undefined,
  insetStart: number,
  insetEnd: number,
): { left: string; width: string } => {
  const { lane: index, lanes } = lane ?? { lane: 0, lanes: 1 };
  const available = `(100% - ${insetStart + insetEnd}px)`;
  const gap = lanes > 1 ? 2 : 0;
  return {
    left: `calc(${insetStart}px + ${available} * ${index / lanes})`,
    width: `calc(${available} / ${lanes} - ${gap}px)`,
  };
};

/** The current-time line's offset, or null when now falls outside the grid's hours. */
export const nowOffset = (now: Date, hours: GridHours, pxPerHour: number): number | null => {
  const minutes = now.getHours() * 60 + now.getMinutes();
  if (minutes < hours.startHour * 60 || minutes >= hours.endHour * 60) return null;
  return ((minutes - hours.startHour * 60) / 60) * pxPerHour;
};

export const isSameDay = (left: Date, right: Date) =>
  left.getFullYear() === right.getFullYear() &&
  left.getMonth() === right.getMonth() &&
  left.getDate() === right.getDate();

/** The current time, refreshed every minute. The interval is cleared when the view unmounts. */
export function useNow(intervalMs = 60_000): Date {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs]);
  return now;
}
