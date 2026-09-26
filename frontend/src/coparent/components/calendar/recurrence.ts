import type { Event } from '../../types/calendar';

const WEEKDAY_KEYS = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];

const toYmd = (value: string) => (value.includes('T') ? value.slice(0, 10) : value);
const toLocalDay = (value: string) => new Date(`${toYmd(value)}T12:00:00`);

const addDays = (date: Date, amount: number) => {
  const next = new Date(date);
  next.setDate(next.getDate() + amount);
  return next;
};

export const dateToYmd = (date: Date) => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
};

// Two letters identify every weekday, so any spelling from "TU" to "Tuesday" resolves to the
// one key the expansion compares against. The backend stores canonical names; this keeps a
// restored or older series from being saved yet silently rendering no occurrences.
const weekdayKey = (value: string) => {
  const lower = value.trim().toLowerCase();
  return lower.length >= 2 ? WEEKDAY_KEYS.find((key) => key.startsWith(lower)) : undefined;
};

export const expandRecurringEvents = (events: Event[], rangeStart: string, rangeEnd: string) => {
  const rangeStartDay = toLocalDay(rangeStart);
  const rangeEndDay = toLocalDay(rangeEnd);

  return events.flatMap((event) => {
    if (!event.recurring) return [event];

    const eventStart = toLocalDay(event.startDate);
    // A series with no end date repeats indefinitely; treating the start as its end would
    // show one occurrence and nothing after it.
    const eventEnd = event.endDate ? toLocalDay(event.endDate) : rangeEndDay;
    const effectiveStart = eventStart > rangeStartDay ? eventStart : rangeStartDay;
    const effectiveEnd = eventEnd < rangeEndDay ? eventEnd : rangeEndDay;

    if (effectiveStart > effectiveEnd) return [];

    const durationDays = event.endDate
      ? Math.max(
          0,
          Math.round((eventEnd.getTime() - eventStart.getTime()) / (1000 * 60 * 60 * 24)),
        )
      : 0;

    const frequency = event.recurring.frequency;
    const skipped = new Set(event.recurring.excludedDates ?? []);

    if (frequency === 'daily') {
      const days: Event[] = [];
      for (let day = new Date(effectiveStart); day <= effectiveEnd; day = addDays(day, 1)) {
        const startYmd = dateToYmd(day);
        if (skipped.has(startYmd)) continue;
        const endYmd = dateToYmd(addDays(day, durationDays));
        days.push({
          ...event,
          id: `${event.id}:${startYmd}`,
          sourceId: event.id,
          startDate: startYmd,
          endDate: endYmd,
        });
      }
      return days;
    }

    if (frequency === 'weekly') {
      const recognisedDays = (event.recurring.days ?? []).flatMap((day) => {
        const key = weekdayKey(day);
        return key ? [key] : [];
      });
      const allowedDays =
        recognisedDays.length > 0 ? recognisedDays : [WEEKDAY_KEYS[eventStart.getDay()]];

      const days: Event[] = [];
      for (let day = new Date(effectiveStart); day <= effectiveEnd; day = addDays(day, 1)) {
        const weekday = WEEKDAY_KEYS[day.getDay()];
        if (!allowedDays.includes(weekday)) continue;

        const startYmd = dateToYmd(day);
        if (skipped.has(startYmd)) continue;
        const endYmd = dateToYmd(addDays(day, durationDays));
        days.push({
          ...event,
          id: `${event.id}:${startYmd}`,
          sourceId: event.id,
          startDate: startYmd,
          endDate: endYmd,
        });
      }
      return days;
    }

    return [event];
  });
};

/**
 * What a click on a rendered event refers to: the stored event, plus the date of the
 * occurrence when it came from a repeating series, so that one week can be skipped.
 */
export const occurrenceTarget = (event: Event): [string, string | undefined] =>
  event.sourceId ? [event.sourceId, event.startDate] : [event.id, undefined];
