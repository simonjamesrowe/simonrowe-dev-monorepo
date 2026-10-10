import { dateToYmd, expandRecurringEvents } from '../calendar/recurrence';
import type { Event } from '../../types/calendar';

/** The parts of a child the briefing needs: who they are and the colour that marks them. */
export interface BriefingChild {
  id: string;
  firstName: string;
  tone: number;
}

/** One occurrence on one day, as the briefing lists it. */
export interface BriefingItem {
  /** What a click opens: the stored event, and the date when it is one week of a series. */
  eventId: string;
  occurrence?: string;
  date: string;
  title: string;
  startTime?: string;
  endTime?: string;
  allDay: boolean;
  location?: string;
  children: BriefingChild[];
}

/** How many child colours the stylesheet defines; a fifth child reuses the first. */
export const CHILD_TONES = 4;

export const briefingChildren = (children: { id: string; fullName: string }[]): BriefingChild[] =>
  children.map((child, index) => ({
    id: child.id,
    firstName: child.fullName.trim().split(/\s+/)[0] || child.fullName,
    tone: index % CHILD_TONES,
  }));

const toYmd = (value: string) => (value.includes('T') ? value.slice(0, 10) : value);

/** The local date `offset` calendar days after a YYYY-MM-DD date, at midday so a clock change cannot move it. */
export const addDaysTo = (ymd: string, offset: number): Date => {
  const date = new Date(`${ymd}T12:00:00`);
  date.setDate(date.getDate() + offset);
  return date;
};
const isCustody = (event: Event) => event.type.trim().toLowerCase() === 'custody';

/**
 * Titles are often written "Ethan - Football training". The child is already named beside the
 * title, so the prefix is dropped, but only when it is exactly the one child the event is for.
 */
export const stripChildPrefix = (title: string, children: BriefingChild[]): string => {
  if (children.length !== 1) return title;
  const name = children[0].firstName.toLowerCase();
  const lower = title.toLowerCase();
  if (!lower.startsWith(name)) return title;
  const rest = title.slice(name.length).replace(/^\s*[-–—:]\s*/, '');
  return rest.length > 0 && rest.length < title.length - name.length ? rest : title;
};

/** Every non-custody occurrence from `from` to `to` inclusive, grouped by day in order. */
export const itemsByDay = (
  events: Event[],
  children: BriefingChild[],
  from: string,
  to: string,
): Map<string, BriefingItem[]> => {
  const byId = new Map(children.map((child) => [child.id, child]));
  const days = new Map<string, BriefingItem[]>();
  for (let day = from; day <= to; day = dateToYmd(addDaysTo(day, 1))) days.set(day, []);

  expandRecurringEvents(
    events.map((event) => ({ ...event, startDate: toYmd(event.startDate) })),
    from,
    to,
  )
    .filter((event) => !isCustody(event))
    .forEach((event) => {
      const date = toYmd(event.startDate);
      const list = days.get(date);
      if (!list) return;
      const eventChildren = event.childIds.flatMap((id) => {
        const child = byId.get(id);
        return child ? [child] : [];
      });
      list.push({
        eventId: event.sourceId ?? event.id,
        occurrence: event.sourceId ? date : undefined,
        date,
        title: stripChildPrefix(event.title, eventChildren),
        startTime: event.allDay ? undefined : event.startTime,
        endTime: event.allDay ? undefined : event.endTime,
        allDay: event.allDay || !event.startTime,
        location: event.location ?? undefined,
        children: eventChildren,
      });
    });

  days.forEach((list) =>
    list.sort((left, right) => {
      if (left.allDay !== right.allDay) return left.allDay ? -1 : 1;
      return (left.startTime ?? '').localeCompare(right.startTime ?? '');
    }),
  );
  return days;
};

const minutesOf = (time: string) => {
  const [hours = 0, minutes = 0] = time.split(':').map(Number);
  return hours * 60 + minutes;
};

/** Whether an item is over by `now`. An all-day item lasts the whole day. */
export const isFinished = (item: BriefingItem, now: Date): boolean => {
  if (item.allDay || !item.startTime) return false;
  const end = item.endTime ? minutesOf(item.endTime) : minutesOf(item.startTime) + 60;
  return now.getHours() * 60 + now.getMinutes() >= end;
};

const custodyParentOf = (event: Event) => event.parentIds?.[0] ?? event.parentId ?? null;

/** Which parent has the children on a day, read from custody events, or null if none says. */
export const custodyParentOn = (events: Event[], ymd: string): string | null => {
  const day = expandRecurringEvents(
    events.filter(isCustody).map((event) => ({ ...event, startDate: toYmd(event.startDate) })),
    ymd,
    ymd,
  ).find((event) => {
    const start = toYmd(event.startDate);
    // A repeating custody day is that day alone; a one-off block runs to its end date.
    const end = event.recurring ? start : toYmd(event.endDate ?? event.startDate);
    return start <= ymd && ymd <= end;
  });
  return day ? custodyParentOf(day) : null;
};

/** The next day within a fortnight on which the children move to a different parent. */
export const nextHandover = (
  events: Event[],
  today: string,
): { date: string; parentId: string } | null => {
  const current = custodyParentOn(events, today);
  for (let offset = 1; offset <= 14; offset += 1) {
    const date = dateToYmd(addDaysTo(today, offset));
    const parentId = custodyParentOn(events, date);
    if (parentId && parentId !== current) return { date, parentId };
  }
  return null;
};

/** "Ethan", "Ethan and Ava", "Ethan, Ava and Mia". */
export const joinNames = (names: string[]): string => {
  if (names.length <= 1) return names[0] ?? '';
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
};

/** What the headline says after the children's names. */
export const headlinePredicate = ({
  childCount,
  custodyParentName,
  remainingToday,
}: {
  childCount: number;
  /** "you", a parent's first name, or null when no custody event covers today. */
  custodyParentName: string | null;
  remainingToday: number;
}): string => {
  const plural = childCount !== 1;
  if (custodyParentName) {
    return `${plural ? 'are' : 'is'} with ${custodyParentName}.`;
  }
  if (remainingToday === 0) return `${plural ? 'have' : 'has'} nothing else on today.`;
  if (remainingToday === 1) return `${plural ? 'have' : 'has'} one more thing today.`;
  return `${plural ? 'have' : 'has'} ${remainingToday} more things today.`;
};

const LONG_DATE = new Intl.DateTimeFormat('en-GB', { weekday: 'long', day: 'numeric', month: 'long' });
const SHORT_WEEKDAY = new Intl.DateTimeFormat('en-GB', { weekday: 'short' });

/** "Saturday 10 October". */
export const longDate = (date: Date): string => LONG_DATE.format(date).replace(',', '');

/** "Mon" for a YYYY-MM-DD date. */
export const shortWeekday = (ymd: string): string => SHORT_WEEKDAY.format(addDaysTo(ymd, 0));
