import { track } from '../../analytics/analytics'
import { formatSpan, headsUpItems, headsUpLabel, questionAbout } from '../calendarModel'
import type { CalendarEvent } from '../schoolCalendarApi'
import type { SchoolCalendarState } from '../useSchoolCalendar'

interface HeadsUpStripProps {
  calendar: SchoolCalendarState
  onAsk: (question: string) => void
  /** Stacks the items, for the narrow column beside a conversation. */
  compact?: boolean
}

/**
 * Half terms, INSET days and term dates in the calendar's window, in one strip.
 *
 * Above everything else on the page because a day with no school is the one thing a parent
 * cannot afford to miss, and in a list of clubs and trips it is the thing that gets missed.
 * Includes one already under way, so a visitor in the middle of half term sees when it ends.
 * Renders nothing until the calendar has loaded, and nothing when there is nothing to say.
 */
export function HeadsUpStrip({ calendar, onAsk, compact = false }: HeadsUpStripProps) {
  if (calendar.load.status !== 'ready') return null
  const items = headsUpItems(calendar.load.calendar.events)
  if (items.length === 0) return null

  const ask = (event: CalendarEvent) => {
    track('Calendar Event Clicked', { eventType: event.type, section: 'heads-up' })
    onAsk(questionAbout(event))
  }

  return (
    <section
      className={`school-headsup${compact ? ' school-headsup--compact' : ''}`}
      aria-label="Days that change"
    >
      <ul className="school-headsup__list">
        {items.map((event) => (
          <li key={event.id}>
            <button
              type="button"
              className={`school-headsup__item${
                event.type === 'TERM_BOUNDARY' ? ' school-headsup__item--calm' : ''
              }`}
              onClick={() => ask(event)}
              aria-label={`${headsUpLabel(event.type)}, ${formatSpan(event)}: ${
                event.title
              }. Ask about this`}
            >
              <span className="school-headsup__tag">{headsUpLabel(event.type)}</span>
              <span className="school-headsup__when">{formatSpan(event)}</span>
              {event.type !== 'HALF_TERM' && (
                <span className="school-headsup__what">{event.title}</span>
              )}
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}
