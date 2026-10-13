import { describe, expect, it } from 'vitest'

import {
  buildCalendarView,
  formatDay,
  formatSpan,
  questionAbout,
  timeLabel,
  yearsLabel,
} from '../../src/school/calendarModel'
import type { CalendarEvent, SchoolCalendar } from '../../src/school/schoolCalendarApi'

function event(overrides: Partial<CalendarEvent> & Pick<CalendarEvent, 'id'>): CalendarEvent {
  return {
    title: overrides.id,
    startDate: '2026-10-13',
    endDate: overrides.startDate ?? '2026-10-13',
    allDay: false,
    time: null,
    type: 'OTHER',
    yearGroups: [],
    location: null,
    ...overrides,
  }
}

const calendar = (events: CalendarEvent[]): SchoolCalendar => ({
  today: '2026-10-13',
  weekEnd: '2026-10-18',
  until: '2026-11-01',
  events,
})

describe('buildCalendarView', () => {
  const events = [
    event({ id: 'harvest', time: '2:30pm' }),
    event({ id: 'book fair', startDate: '2026-10-12', endDate: '2026-10-14' }),
    event({ id: 'trip', startDate: '2026-10-15', type: 'TRIP' }),
    event({ id: 'assembly', startDate: '2026-10-16' }),
    event({ id: 'inset', startDate: '2026-10-23', type: 'INSET' }),
    event({ id: 'disco', startDate: '2026-10-22' }),
    event({
      id: 'half term',
      startDate: '2026-10-26',
      endDate: '2026-10-30',
      type: 'HALF_TERM',
    }),
  ]

  it('puts everything running today, including a multi-day event, in the Today card', () => {
    const view = buildCalendarView(calendar(events), 'week')
    expect(view.today.map((e) => e.id)).toEqual(['harvest', 'book fair'])
  })

  it('lists the day-changing kinds first in the Today card', () => {
    const view = buildCalendarView(
      calendar([event({ id: 'club' }), event({ id: 'inset today', type: 'INSET' })]),
      'week',
    )
    expect(view.today.map((e) => e.id)).toEqual(['inset today', 'club'])
  })

  it('shows INSET days and half term in the heads-up strip whatever the range', () => {
    for (const range of ['today', 'week', 'weeks'] as const) {
      expect(buildCalendarView(calendar(events), range).headsUp.map((e) => e.id)).toEqual([
        'inset',
        'half term',
      ])
    }
  })

  it('lists only this week after today for "This week", grouped by day', () => {
    const view = buildCalendarView(calendar(events), 'week')
    expect(view.days).toEqual([
      { date: '2026-10-15', events: [events[2]] },
      { date: '2026-10-16', events: [events[3]] },
    ])
    expect(view.listUntil).toBe('2026-10-18')
  })

  it('reaches the end of the window for "3 weeks", leaving heads-up kinds out of the list', () => {
    const view = buildCalendarView(calendar(events), 'weeks')
    expect(view.days.map((d) => d.date)).toEqual(['2026-10-15', '2026-10-16', '2026-10-22'])
    expect(view.listUntil).toBe('2026-11-01')
  })

  it('lists nothing for "Today", which the Today card already covers', () => {
    const view = buildCalendarView(calendar(events), 'today')
    expect(view.days).toEqual([])
    expect(view.listUntil).toBeNull()
  })
})

describe('formatting', () => {
  it('formats dates without shifting them by a time zone', () => {
    expect(formatDay('2026-10-26')).toBe('Mon 26 Oct')
    expect(
      formatSpan(event({ id: 'ht', startDate: '2026-10-26', endDate: '2026-10-30' })),
    ).toBe('Mon 26 Oct – Fri 30 Oct')
  })

  it("keeps the source's own time, and says All day when it gave none", () => {
    expect(timeLabel(event({ id: 'a', time: '6:00pm' }))).toBe('6:00pm')
    expect(timeLabel(event({ id: 'b', time: 'All Day', allDay: true }))).toBe('All day')
    expect(timeLabel(event({ id: 'c', time: null, allDay: true }))).toBe('All day')
    expect(timeLabel(event({ id: 'd', time: null, allDay: false }))).toBe('')
  })

  it('collapses runs of year groups into ranges', () => {
    expect(yearsLabel(['Year 3'])).toBe('Year 3')
    expect(yearsLabel(['Year 3', 'Year 4'])).toBe('Years 3, 4')
    expect(yearsLabel(['Year 6', 'Year 3', 'Year 5', 'Year 4'])).toBe('Years 3–6')
    expect(yearsLabel(['Reception', 'Year 1', 'Year 2', 'Year 3', 'Year 6'])).toBe(
      'Reception, Years 1–3, 6',
    )
  })

  it('asks the chat about an event by name and date', () => {
    expect(questionAbout(event({ id: 'Horniman Museum trip', startDate: '2026-10-15' }))).toBe(
      'Tell me more about "Horniman Museum trip" on Thursday 15 October.',
    )
  })
})
