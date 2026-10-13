import { API_BASE_URL } from '../config/api'

/** Mirrors `SchoolEvent.EventType` on the backend. */
export type SchoolEventType = 'TERM_BOUNDARY' | 'HALF_TERM' | 'INSET' | 'CLUB' | 'TRIP' | 'OTHER'

export interface CalendarEvent {
  id: string
  title: string
  /** ISO date, `yyyy-MM-dd`. */
  startDate: string
  /** ISO date, inclusive. Equal to `startDate` for a one-day event. */
  endDate: string
  allDay: boolean
  /** The source's own wording, e.g. `6:00pm` or `All Day`. */
  time: string | null
  type: SchoolEventType
  /** Empty means the whole school. */
  yearGroups: string[]
  location: string | null
}

/**
 * The calendar pane's window. The dates come from the server, in the school's time zone, so the
 * pane never has to decide which day it is from the visitor's clock.
 */
export interface SchoolCalendar {
  today: string
  weekEnd: string
  until: string
  events: CalendarEvent[]
}

/** Term Time is switched off. Distinct from a failure: there is nothing to retry. */
export class CalendarUnavailableError extends Error {
  constructor() {
    super('Term Time is not switched on.')
    this.name = 'CalendarUnavailableError'
  }
}

/**
 * Today's and the next few weeks' public events, narrowed to the visitor's year groups.
 *
 * Year groups go as a repeated parameter. No year group name contains a comma, and the server
 * drops any value it does not recognise, so the comma-splitting Spring does on a single value
 * cannot turn one into two.
 */
export async function fetchSchoolCalendar(
  yearGroups: string[],
  signal?: AbortSignal,
): Promise<SchoolCalendar> {
  const params = new URLSearchParams()
  yearGroups.forEach((year) => params.append('yearGroups', year))
  const query = params.toString()
  const response = await fetch(`${API_BASE_URL}/api/school/calendar${query ? `?${query}` : ''}`, {
    signal,
  })
  if (response.status === 503) {
    throw new CalendarUnavailableError()
  }
  if (!response.ok) {
    throw new Error(`Calendar request failed: ${response.status}`)
  }
  return (await response.json()) as SchoolCalendar
}
