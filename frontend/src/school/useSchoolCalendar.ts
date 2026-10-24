import { useEffect, useState } from 'react'

import {
  CalendarUnavailableError,
  fetchSchoolCalendar,
  type SchoolCalendar,
} from './schoolCalendarApi'

/**
 * How old the calendar may be before a returning tab reloads it.
 *
 * The page is often left open, and a tab opened yesterday would otherwise pin yesterday's events
 * as today's. Checked when the tab becomes visible again rather than on a timer, so a tab nobody
 * is looking at makes no requests.
 */
const STALE_AFTER_MS = 30 * 60 * 1000

export type CalendarLoad =
  | { status: 'loading' }
  | { status: 'ready'; calendar: SchoolCalendar; checkedAt: Date }
  | { status: 'error' }
  | { status: 'off' }

export interface SchoolCalendarState {
  load: CalendarLoad
  /** Fetches again, for the retry button. */
  reload: () => void
}

/**
 * The calendar for the visitor's year groups, shared by every part of the page that shows it.
 *
 * One fetch serves the heads-up strip and the Coming up card, so the two can never disagree
 * about what is on and the page asks the server once rather than once per card.
 *
 * @param yearGroups the visitor's year groups; empty means every year
 */
export function useSchoolCalendar(yearGroups: string[]): SchoolCalendarState {
  const [load, setLoad] = useState<CalendarLoad>({ status: 'loading' })
  const [reloads, setReloads] = useState(0)
  // A string key, so a new array with the same years does not refetch.
  const yearsKey = yearGroups.join('|')

  useEffect(() => {
    const controller = new AbortController()
    setLoad((prior) => (prior.status === 'ready' ? prior : { status: 'loading' }))
    fetchSchoolCalendar(yearsKey ? yearsKey.split('|') : [], controller.signal)
      .then((calendar) => setLoad({ status: 'ready', calendar, checkedAt: new Date() }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setLoad({ status: error instanceof CalendarUnavailableError ? 'off' : 'error' })
      })
    return () => controller.abort()
  }, [yearsKey, reloads])

  const checkedAt = load.status === 'ready' ? load.checkedAt : null
  useEffect(() => {
    if (!checkedAt) return
    const onVisible = () => {
      if (
        document.visibilityState === 'visible' &&
        Date.now() - checkedAt.getTime() > STALE_AFTER_MS
      ) {
        setReloads((n) => n + 1)
      }
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [checkedAt])

  return { load, reload: () => setReloads((n) => n + 1) }
}
