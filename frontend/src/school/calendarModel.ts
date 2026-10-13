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
