import type { Event } from '../../types/calendar';

const WEEKDAY_KEYS = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];

const toYmd = (value: string) => (value.includes('T') ? value.slice(0, 10) : value);
const toLocalDay = (value: string) => new Date(`${toYmd(value)}T12:00:00`);

const addDays = (date: Date, amount: number) => {
  const next = new Date(date);
  next.setDate(next.getDate() + amount);
  return next;
};

const dateToYmd = (date: Date) => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
};

export const expandRecurringEvents = (events: Event[], rangeStart: string, rangeEnd: string) => {
  const rangeStartDay = toLocalDay(rangeStart);
  const rangeEndDay = toLocalDay(rangeEnd);

  return events.flatMap((event) => {
    if (!event.recurring) return [event];

    const eventStart = toLocalDay(event.startDate);
    const eventEnd = toLocalDay(event.endDate ?? event.startDate);
    const effectiveStart = eventStart > rangeStartDay ? eventStart : rangeStartDay;
    const effectiveEnd = eventEnd < rangeEndDay ? eventEnd : rangeEndDay;

    if (effectiveStart > effectiveEnd) return [];

    const durationDays = Math.max(
      0,
      Math.round((eventEnd.getTime() - eventStart.getTime()) / (1000 * 60 * 60 * 24)),
    );

    const frequency = event.recurring.frequency;

    if (frequency === 'daily') {
      const days: Event[] = [];
      for (let day = new Date(effectiveStart); day <= effectiveEnd; day = addDays(day, 1)) {
        const startYmd = dateToYmd(day);
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
      const allowedDays =
        event.recurring.days && event.recurring.days.length > 0
          ? event.recurring.days
          : [WEEKDAY_KEYS[eventStart.getDay()]];

      const days: Event[] = [];
      for (let day = new Date(effectiveStart); day <= effectiveEnd; day = addDays(day, 1)) {
        const weekday = WEEKDAY_KEYS[day.getDay()];
        if (!allowedDays.includes(weekday)) continue;

        const startYmd = dateToYmd(day);
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
