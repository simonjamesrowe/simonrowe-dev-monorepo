import type { CalendarEvent, SchoolCalendar, SchoolEventType } from './schoolCalendarApi'

/** How far ahead the list reaches. The Today card and the heads-up strip ignore it. */
export type CalendarRange = 'today' | 'week' | 'weeks'

export const CALENDAR_RANGES: { value: CalendarRange; label: string }[] = [
  { value: 'today', label: 'Today' },
  { value: 'week', label: 'This week' },
  { value: 'weeks', label: '3 weeks' },
]

/**
 * The kinds of event that change a family's day rather than add to it.
 *
 * Pulled out of the day-by-day list into one strip, and that strip covers the whole window
 * whatever range is chosen: an INSET day ten days away is exactly the thing that is missed in a
 * long list, and exactly the thing that needs childcare arranging now.
 */
const HEADS_UP_TYPES: ReadonlySet<SchoolEventType> = new Set([
  'INSET',
  'HALF_TERM',
  'TERM_BOUNDARY',
])

export function isHeadsUp(event: CalendarEvent): boolean {
  return HEADS_UP_TYPES.has(event.type)
}

/**
 * The days that change, for the heads-up strip: one item per kind per day, and no term spans.
 *
 * Sources describe the same day in different words: on 2026-10-24 the strip showed "School
 * reopens after half-term" and "Kilmorie Primary School return to school after half-term" side
 * by side for Tuesday 3 November, which the server's duplicate merging does not catch because the
 * titles share too few words. Rather than chase every wording, the strip keeps the first item of
 * each kind for each start date, which the server has already ordered most authoritative first.
 *
 * A term boundary that runs over several days is a term's whole span ("Autumn term 2, Mon 2 Nov
 * – Fri 18 Dec"). That is context, not a day that changes, and the days it starts and ends are
 * already the ones that matter, so the strip leaves it out. The chat can still answer about it.
 */
export function headsUpItems(events: CalendarEvent[]): CalendarEvent[] {
  const seen = new Set<string>()
  return events.filter((event) => {
    if (!isHeadsUp(event)) return false
    if (event.type === 'TERM_BOUNDARY' && event.endDate !== event.startDate) return false
    const key = `${event.type}|${event.startDate}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

/**
 * The strip's label for an event. A term boundary is not "no school": the last day of term is a
 * school day, often with an early finish, so it gets its own word rather than a wrong one.
 */
export function headsUpLabel(type: SchoolEventType): string {
  switch (type) {
    case 'INSET':
      return 'No school'
    case 'HALF_TERM':
      return 'Half term'
    case 'TERM_BOUNDARY':
      return 'Term dates'
    default:
      return ''
  }
}

export interface CalendarDay {
  date: string
  events: CalendarEvent[]
}

export interface CalendarView {
  /** Everything running today, the day-changing kinds first. */
  today: CalendarEvent[]
  /** INSET days, half terms and term dates still to come in the window. */
  headsUp: CalendarEvent[]
  /** Everything else starting after today and within the chosen range, by day. */
  days: CalendarDay[]
  /** The last day the list covers, or null when the range is today only. */
  listUntil: string | null
}

// ISO dates compare correctly as strings, so no Date objects and no time-zone arithmetic.
function runsOn(event: CalendarEvent, date: string): boolean {
  return event.startDate <= date && event.endDate >= date
}

export function buildCalendarView(calendar: SchoolCalendar, range: CalendarRange): CalendarView {
  const { today, events } = calendar

  const runningToday = events.filter((event) => runsOn(event, today))
  const todayEvents = [
    ...runningToday.filter(isHeadsUp),
    ...runningToday.filter((event) => !isHeadsUp(event)),
  ]

  const later = events.filter((event) => event.startDate > today)
  const headsUp = later.filter(isHeadsUp)

  const listUntil =
    range === 'today' ? null : range === 'week' ? calendar.weekEnd : calendar.until
  const days: CalendarDay[] = []
  if (listUntil !== null) {
    for (const event of later) {
      if (isHeadsUp(event) || event.startDate > listUntil) continue
      const last = days[days.length - 1]
      if (last?.date === event.startDate) {
        last.events.push(event)
      } else {
        days.push({ date: event.startDate, events: [event] })
      }
    }
  }

  return { today: todayEvents, headsUp, days, listUntil }
}

function toUtcDate(iso: string): Date {
  const [year, month, day] = iso.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, day))
}

/** `Wed 14 Oct`. Formatted in UTC because the date has no time and must not shift a day. */
export function formatDay(iso: string): string {
  return toUtcDate(iso).toLocaleDateString('en-GB', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  })
}

/** `Tuesday 13 October`. */
export function formatLongDay(iso: string): string {
  return toUtcDate(iso).toLocaleDateString('en-GB', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    timeZone: 'UTC',
  })
}

/** `Fri 23 Oct`, or `Mon 26 Oct – Fri 30 Oct` for an event spanning several days. */
export function formatSpan(event: CalendarEvent): string {
  return event.startDate === event.endDate
    ? formatDay(event.startDate)
    : `${formatDay(event.startDate)} – ${formatDay(event.endDate)}`
}

/**
 * What goes in the time column. The source's own wording when it gave a time, because a parsed
 * and reformatted time is one more place to be wrong; "All day" when it said so or gave none.
 */
export function timeLabel(event: CalendarEvent): string {
  const time = event.time?.trim()
  if (time && !/^all[\s-]?day$/i.test(time)) return time
  return event.allDay ? 'All day' : ''
}

/**
 * Year groups in as few words as are clear: `Year 3`, `Years 3–6`, `Reception, Year 1`.
 *
 * A run of consecutive years collapses to a range, because a club open to Years 3, 4, 5 and 6
 * otherwise takes two lines of a narrow pane to say so.
 */
export function yearsLabel(yearGroups: string[]): string {
  const numbers: number[] = []
  const others: string[] = []
  for (const group of yearGroups) {
    const match = /^Year (\d)$/.exec(group)
    if (match) numbers.push(Number(match[1]))
    else others.push(group)
  }
  numbers.sort((a, b) => a - b)

  const runs: string[] = []
  let start = 0
  for (let i = 1; i <= numbers.length; i++) {
    if (i === numbers.length || numbers[i] !== numbers[i - 1] + 1) {
      const first = numbers[start]
      const last = numbers[i - 1]
      runs.push(last - first >= 2 ? `${first}–${last}` : numbers.slice(start, i).join(', '))
      start = i
    }
  }
  const years =
    numbers.length === 0 ? [] : [`${numbers.length === 1 ? 'Year' : 'Years'} ${runs.join(', ')}`]
  return [...others, ...years].join(', ')
}

/** The question clicking an event asks the chat. */
export function questionAbout(event: CalendarEvent): string {
  return `Tell me more about "${event.title}" on ${formatLongDay(event.startDate)}.`
}

/**
 * The date of something the school published, such as a newsletter or an email, read in the
 * school's time zone.
 *
 * The server can record a newsletter's date as midnight in London, and in British Summer Time
 * that is 23:00 the day before in UTC, so formatting in UTC or the visitor's own zone could put
 * a Friday newsletter on Thursday.
 *
 * @param iso an ISO instant
 * @param style `date` for "23 Oct", `short` for "Fri 23 Oct", `long` for "Friday 23 October"
 */
export function formatPublished(iso: string, style: 'date' | 'short' | 'long' = 'short'): string {
  return new Date(iso).toLocaleDateString('en-GB', {
    ...(style === 'date' ? {} : { weekday: style === 'long' ? 'long' : 'short' }),
    day: 'numeric',
    month: style === 'long' ? 'long' : 'short',
    timeZone: 'Europe/London',
  })
}
