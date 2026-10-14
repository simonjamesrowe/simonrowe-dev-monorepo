import type { Event } from '../../types/calendar';

/** Which day of a multi-day event a piece covers. */
export type SpanPart = 'first' | 'middle' | 'last';

/** The whole of a multi-day event, carried on each day's piece so it can be labelled. */
export interface EventSpan {
  part: SpanPart;
  startDate: string;
  endDate: string;
  startTime?: string;
  endTime?: string;
}

/** An event as it appears on one day. `span` is set only on a piece of a multi-day event. */
export type DayEvent = Event & { span?: EventSpan };

const toYmd = (value: string) => (value.includes('T') ? value.slice(0, 10) : value);

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const weekdayOf = (ymd: string) => WEEKDAYS[new Date(`${ymd}T12:00:00`).getDay()] ?? '';

/**
 * Whether a stored event runs over more than one day. A series' end date is when it stops
 * repeating, not how long each occurrence lasts, so a repeating event never counts.
 */
export const isMultiDay = (event: Event): boolean =>
  !event.recurring && Boolean(event.endDate) && toYmd(event.endDate ?? '') > toYmd(event.startDate);

/**
 * The piece of an event that falls on `ymd`, or null when it does not touch that day. A timed
 * event over several days fills from its start time to midnight on the first day, the whole of
 * every day between, and from midnight to its end time on the last; an all-day or untimed one is
 * returned unchanged for each day it covers. A school trip from Monday 09:30 to Friday 15:30 was
 * drawn on Monday alone, because every view matched events on their start date.
 */
export const eventOnDay = (event: Event, ymd: string): DayEvent | null => {
  const start = toYmd(event.startDate);
  if (!isMultiDay(event)) return start === ymd ? event : null;

  const end = toYmd(event.endDate ?? event.startDate);
  if (ymd < start || ymd > end) return null;

  const part: SpanPart = ymd === start ? 'first' : ymd === end ? 'last' : 'middle';
  const span: EventSpan = {
    part,
    startDate: start,
    endDate: end,
    startTime: event.startTime,
    endTime: event.endTime,
  };
  if (event.allDay || !event.startTime) return { ...event, span };
  // Ending at midnight means the event finished as the day before ended.
  if (part === 'last' && event.endTime?.startsWith('00:00')) return null;

  return {
    ...event,
    startTime: part === 'first' ? event.startTime : '00:00',
    endTime: part === 'last' ? (event.endTime ?? '24:00') : '24:00',
    span,
  };
};

/** Every piece of `events` on `ymd`, in the order given. */
export const eventsOnDay = (events: Event[], ymd: string): DayEvent[] =>
  events.flatMap((event) => {
    const piece = eventOnDay(event, ymd);
    return piece ? [piece] : [];
  });

/**
 * The times to print on an event. A multi-day piece names both ends, "Mon 09:30 – Fri 15:30",
 * rather than the midnight edges it is drawn to on that day.
 */
export const timeLabel = (event: DayEvent): string => {
  if (event.span) {
    const { startDate, endDate, startTime, endTime } = event.span;
    const from = [weekdayOf(startDate), startTime].filter(Boolean).join(' ');
    const to = [weekdayOf(endDate), endTime].filter(Boolean).join(' ');
    return `${from} – ${to}`;
  }
  if (!event.startTime) return '';
  return event.endTime ? `${event.startTime} – ${event.endTime}` : event.startTime;
};
