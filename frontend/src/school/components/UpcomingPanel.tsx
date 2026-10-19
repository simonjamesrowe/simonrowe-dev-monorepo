import { CalendarDays, MessageCircleQuestion, RefreshCw } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'

import {
  buildCalendarView,
  CALENDAR_RANGES,
  formatDay,
  formatLongDay,
  formatSpan,
  headsUpLabel,
  isHeadsUp,
  questionAbout,
  timeLabel,
  yearsLabel,
  type CalendarRange,
} from '../calendarModel'
import { track } from '../../analytics/analytics'
import {
  CalendarUnavailableError,
  fetchSchoolCalendar,
  type CalendarEvent,
  type SchoolCalendar,
} from '../schoolCalendarApi'

const RANGE_STORAGE_KEY = 'term-time-calendar-range'

/** Which part of the pane an event was picked from, for analytics. */
type PaneSection = 'today' | 'heads-up' | 'day'

/**
 * How old the calendar may be before a returning tab reloads it.
 *
 * The page is often left open, and a tab opened yesterday would otherwise pin yesterday's events
 * as today's. Checked when the tab becomes visible again rather than on a timer, so a tab nobody
 * is looking at makes no requests.
 */
const STALE_AFTER_MS = 30 * 60 * 1000

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; calendar: SchoolCalendar; checkedAt: Date }
  | { status: 'error' }
  | { status: 'off' }

function readRange(): CalendarRange {
  try {
    const stored = window.localStorage?.getItem?.(RANGE_STORAGE_KEY)
    return CALENDAR_RANGES.some((r) => r.value === stored) ? (stored as CalendarRange) : 'week'
  } catch {
    return 'week'
  }
}

function writeRange(range: CalendarRange): void {
  try {
    window.localStorage?.setItem?.(RANGE_STORAGE_KEY, range)
  } catch {
    // Preference not saved; nothing else is affected.
  }
}

interface UpcomingPanelProps {
  /** The visitor's year groups; empty means every year. Shared with the chat. */
  yearGroups: string[]
  /** Asks the chat a question about an event the visitor picked. */
  onAsk: (question: string) => void
}

/**
 * What is on today and over the next three weeks, beside the chat.
 *
 * Today is pinned at the top, and INSET days, half terms and term dates are pulled into one
 * strip for the whole window whatever range is chosen, because a day with no school is the thing
 * a long list hides. Every event is a button that asks the chat about it: the pane says what is
 * on, and the chat has the letter with the times, the kit and the booking link.
 */
export function UpcomingPanel({ yearGroups, onAsk }: UpcomingPanelProps) {
  const [range, setRange] = useState<CalendarRange>(readRange)
  const [state, setState] = useState<LoadState>({ status: 'loading' })
  const [reloads, setReloads] = useState(0)
  // A string key, so a new array with the same years does not refetch.
  const yearsKey = yearGroups.join('|')

  useEffect(() => {
    const controller = new AbortController()
    setState((prior) => (prior.status === 'ready' ? prior : { status: 'loading' }))
    fetchSchoolCalendar(yearsKey ? yearsKey.split('|') : [], controller.signal)
      .then((calendar) => setState({ status: 'ready', calendar, checkedAt: new Date() }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setState({ status: error instanceof CalendarUnavailableError ? 'off' : 'error' })
      })
    return () => controller.abort()
  }, [yearsKey, reloads])

  const checkedAt = state.status === 'ready' ? state.checkedAt : null
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

  const view = useMemo(
    () => (state.status === 'ready' ? buildCalendarView(state.calendar, range) : null),
    [state, range],
  )

  function chooseRange(next: CalendarRange) {
    setRange(next)
    writeRange(next)
  }

  const ask = (event: CalendarEvent, section: PaneSection) => {
    track('Calendar Event Clicked', { eventType: event.type, section })
    onAsk(questionAbout(event))
  }

  return (
    <aside className="school-upcoming" aria-labelledby="school-upcoming-title">
      <div className="school-upcoming__head">
        <h2 id="school-upcoming-title" className="school-upcoming__title">
          <CalendarDays size={17} aria-hidden="true" />
          Coming up
        </h2>
        <div className="school-upcoming__ranges" role="group" aria-label="How far ahead to show">
          {CALENDAR_RANGES.map((option) => (
            <button
              key={option.value}
              type="button"
              aria-pressed={range === option.value}
              className={`school-upcoming__range${
                range === option.value ? ' school-upcoming__range--on' : ''
              }`}
              onClick={() => chooseRange(option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
      </div>

      <div className="school-upcoming__body">
        {state.status === 'loading' && (
          <p className="school-upcoming__status" role="status">
            Loading the school calendar…
          </p>
        )}

        {state.status === 'off' && (
          <p className="school-upcoming__status">The calendar is not switched on.</p>
        )}

        {state.status === 'error' && (
          <div className="school-upcoming__status" role="alert">
            <p>The school calendar could not be loaded.</p>
            <button
              type="button"
              className="school-upcoming__retry"
              onClick={() => setReloads((n) => n + 1)}
            >
              <RefreshCw size={14} aria-hidden="true" />
              Try again
            </button>
          </div>
        )}

        {state.status === 'ready' && view && (
          <>
            <section className="school-upcoming__today" aria-labelledby="school-upcoming-today">
              <p id="school-upcoming-today" className="school-upcoming__today-date">
                Today · {formatLongDay(state.calendar.today)}
              </p>
              {view.today.length === 0 ? (
                <p className="school-upcoming__today-empty">Nothing on at school today.</p>
              ) : (
                <ul className="school-upcoming__list">
                  {view.today.map((event) => (
                    <li key={event.id}>
                      <EventButton
                        event={event}
                        today={state.calendar.today}
                        onAsk={(picked) => ask(picked, 'today')}
                      />
                    </li>
                  ))}
                </ul>
              )}
            </section>

            {view.headsUp.length > 0 && (
              <section className="school-upcoming__heads-up" aria-label="Days that change">
                <ul className="school-upcoming__list">
                  {view.headsUp.map((event) => (
                    <li key={event.id}>
                      <button
                        type="button"
                        className="school-upcoming__heads-up-item"
                        onClick={() => ask(event, 'heads-up')}
                        aria-label={`${headsUpLabel(event.type)}, ${formatSpan(event)}: ${
                          event.title
                        }. Ask about this`}
                      >
                        <strong>{headsUpLabel(event.type)}</strong>
                        <span>
                          {' · '}
                          {formatSpan(event)}
                          {event.type !== 'HALF_TERM' && ` — ${event.title}`}
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              </section>
            )}

            {view.days.map((day) => (
              <section
                key={day.date}
                className="school-upcoming__day"
                aria-labelledby={`school-upcoming-${day.date}`}
              >
                <h3 id={`school-upcoming-${day.date}`} className="school-upcoming__day-name">
                  {formatDay(day.date)}
                </h3>
                <ul className="school-upcoming__list">
                  {day.events.map((event) => (
                    <li key={event.id}>
                      <EventButton
                        event={event}
                        today={state.calendar.today}
                        onAsk={(picked) => ask(picked, 'day')}
                      />
                    </li>
                  ))}
                </ul>
              </section>
            ))}

            {view.listUntil !== null && view.days.length === 0 && (
              <p className="school-upcoming__status">
                Nothing else on the calendar until {formatDay(view.listUntil)}.
              </p>
            )}
          </>
        )}
      </div>

      <p className="school-upcoming__foot">
        <span>From the school&rsquo;s calendar, letters and website</span>
        {checkedAt && (
          <span>
            Checked{' '}
            {checkedAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
          </span>
        )}
      </p>
    </aside>
  )
}

interface EventButtonProps {
  event: CalendarEvent
  today: string
  onAsk: (event: CalendarEvent) => void
}

function EventButton({ event, today, onAsk }: EventButtonProps) {
  const time = timeLabel(event)
  const continues = event.endDate > event.startDate && event.endDate > today
  return (
    <button
      type="button"
      className={`school-upcoming__event school-upcoming__event--${event.type.toLowerCase().replace('_', '-')}`}
      onClick={() => onAsk(event)}
    >
      <span className="school-upcoming__time">{time}</span>
      <span className="school-upcoming__dot" aria-hidden="true" />
      <span className="school-upcoming__event-text">
        {isHeadsUp(event) && (
          <span className="school-upcoming__badge">{headsUpLabel(event.type)}</span>
        )}
        <span className="school-upcoming__event-title">{event.title}</span>
        {event.yearGroups.length > 0 && (
          <span className="school-upcoming__meta"> · {yearsLabel(event.yearGroups)}</span>
        )}
        {continues && (
          <span className="school-upcoming__meta"> · until {formatDay(event.endDate)}</span>
        )}
      </span>
      <span className="school-upcoming__ask" aria-hidden="true">
        <MessageCircleQuestion size={14} />
      </span>
      <span className="school-upcoming__sr">Ask about this</span>
    </button>
  )
}
