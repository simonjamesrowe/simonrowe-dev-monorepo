// One plain-English line for a calendar event, e.g.
// "Ethan — Football training · Tue 13 Oct, 18:00–19:00 · every Tuesday".
// Quick add's collapsed cards and the event form's summary both use it, so the two say an event
// the same way.

const DAY = new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });

const WEEKDAY_NAMES: Record<string, string> = {
  monday: 'Monday',
  tuesday: 'Tuesday',
  wednesday: 'Wednesday',
  thursday: 'Thursday',
  friday: 'Friday',
  saturday: 'Saturday',
  sunday: 'Sunday',
};

/** "Tue 13 Oct" from "2026-10-13" or an ISO date-time; anything unreadable comes back as is. */
export function formatDay(value: string): string {
  const ymd = value.slice(0, 10);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(ymd)) return value;
  // Noon, so no time zone can move the date to the day before.
  const date = new Date(`${ymd}T12:00:00`);
  return Number.isNaN(date.getTime()) ? value : DAY.format(date);
}

/** "Ethan", "Ethan and Ava", or "Ethan, Ava and Sam". */
export function joinNames(names: string[]): string {
  if (names.length <= 1) return names[0] ?? '';
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}

/** "every Tuesday", "every Mon and Thu", "every day", plus "until Fri 18 Dec" when it stops. */
export function describeRepeat(
  frequency: string | null | undefined,
  days: string[] | null | undefined,
  until?: string | null,
): string | null {
  if (frequency !== 'daily' && frequency !== 'weekly') return null;
  let text = 'every day';
  if (frequency === 'weekly') {
    const named = (days ?? []).map((day) => WEEKDAY_NAMES[day.toLowerCase()]).filter(Boolean);
    text = named.length === 0
      ? 'every week'
      : named.length === 1
        ? `every ${named[0]}`
        : `every ${joinNames(named.map((name) => name.slice(0, 3)))}`;
  }
  return until ? `${text} until ${formatDay(until)}` : text;
}

export interface EventDescription {
  title?: string | null;
  /** First names of the children it is for. */
  childNames?: string[];
  startDate?: string | null;
  /** For a repeating event, the day the series stops. */
  endDate?: string | null;
  startTime?: string | null;
  endTime?: string | null;
  allDay?: boolean | null;
  frequency?: string | null;
  days?: string[] | null;
}

/** When it happens: "Tue 13 Oct, 18:00–19:00", "Tue 13 – Thu 15 Oct, all day" and so on. */
export function describeWhen(event: EventDescription): string | null {
  if (!event.startDate) return null;
  const start = formatDay(event.startDate);
  const repeats = event.frequency === 'daily' || event.frequency === 'weekly';
  const end = !repeats && event.endDate && event.endDate.slice(0, 10) !== event.startDate.slice(0, 10)
    ? formatDay(event.endDate)
    : null;
  const days = end ? `${start} – ${end}` : start;
  if (event.allDay) return `${days}, all day`;
  if (event.startTime) {
    return `${days}, ${event.startTime}${event.endTime ? `–${event.endTime}` : ''}`;
  }
  return days;
}

/** The whole line, with each part left out when the event does not have it. */
export function describeEvent(event: EventDescription): string {
  const names = joinNames(event.childNames ?? []);
  const title = event.title?.trim();
  const what = names && title ? `${names} — ${title}` : title || names;
  const repeats = event.frequency === 'daily' || event.frequency === 'weekly';
  return [what, describeWhen(event), describeRepeat(event.frequency, event.days, repeats ? event.endDate : null)]
    .filter(Boolean)
    .join(' · ');
}
