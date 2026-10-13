import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { UpcomingPanel } from '../../src/school/components/UpcomingPanel'
import type { SchoolCalendar } from '../../src/school/schoolCalendarApi'

const fetchSchoolCalendar = vi.fn()
vi.mock('../../src/school/schoolCalendarApi', async () => {
  const actual = await vi.importActual<typeof import('../../src/school/schoolCalendarApi')>(
    '../../src/school/schoolCalendarApi',
  )
  return {
    ...actual,
    fetchSchoolCalendar: (...a: unknown[]) => fetchSchoolCalendar(...a),
  }
})

const CALENDAR: SchoolCalendar = {
  today: '2026-10-13',
  weekEnd: '2026-10-18',
  until: '2026-11-01',
  events: [
    {
      id: 'harvest',
      title: 'Harvest Festival assembly',
      startDate: '2026-10-13',
      endDate: '2026-10-13',
      allDay: false,
      time: '2:30pm',
      type: 'OTHER',
      yearGroups: [],
      location: null,
    },
    {
      id: 'trip',
      title: 'Horniman Museum trip',
      startDate: '2026-10-15',
      endDate: '2026-10-15',
      allDay: false,
      time: '9:15am',
      type: 'TRIP',
      yearGroups: ['Year 3'],
      location: 'Forest Hill',
    },
    {
      id: 'disco',
      title: 'Halloween disco',
      startDate: '2026-10-22',
      endDate: '2026-10-22',
      allDay: false,
      time: '5pm',
      type: 'OTHER',
      yearGroups: [],
      location: null,
    },
    {
      id: 'inset',
      title: 'INSET day',
      startDate: '2026-10-23',
      endDate: '2026-10-23',
      allDay: true,
      time: 'All Day',
      type: 'INSET',
      yearGroups: [],
      location: null,
    },
  ],
}

describe('UpcomingPanel', () => {
  beforeEach(() => {
    fetchSchoolCalendar.mockReset()
    window.localStorage.clear()
  })

  it("pins today's events and pulls no-school days into the heads-up strip", async () => {
    fetchSchoolCalendar.mockResolvedValue(CALENDAR)
    render(<UpcomingPanel yearGroups={[]} onAsk={vi.fn()} />)

    expect(await screen.findByText('Today · Tuesday 13 October')).toBeInTheDocument()
    expect(screen.getByText('Harvest Festival assembly')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /No school, Fri 23 Oct: INSET day/ })).toBeVisible()
  })

  it('shows this week by default and three weeks on request, remembering the choice', async () => {
    fetchSchoolCalendar.mockResolvedValue(CALENDAR)
    render(<UpcomingPanel yearGroups={[]} onAsk={vi.fn()} />)

    expect(await screen.findByText('Horniman Museum trip')).toBeInTheDocument()
    expect(screen.queryByText('Halloween disco')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '3 weeks' }))

    expect(screen.getByText('Halloween disco')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '3 weeks' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(window.localStorage.getItem('term-time-calendar-range')).toBe('weeks')
  })

  it('asks the chat about an event when it is clicked', async () => {
    fetchSchoolCalendar.mockResolvedValue(CALENDAR)
    const onAsk = vi.fn()
    render(<UpcomingPanel yearGroups={[]} onAsk={onAsk} />)

    fireEvent.click(await screen.findByText('Horniman Museum trip'))

    expect(onAsk).toHaveBeenCalledWith(
      'Tell me more about "Horniman Museum trip" on Thursday 15 October.',
    )
  })

  it('fetches again for the new year groups when the selection changes', async () => {
    fetchSchoolCalendar.mockResolvedValue(CALENDAR)
    const { rerender } = render(<UpcomingPanel yearGroups={['Year 3']} onAsk={vi.fn()} />)
    await screen.findByText('Horniman Museum trip')

    rerender(<UpcomingPanel yearGroups={['Year 3']} onAsk={vi.fn()} />)
    rerender(<UpcomingPanel yearGroups={['Year 3', 'Year 6']} onAsk={vi.fn()} />)

    await waitFor(() => expect(fetchSchoolCalendar).toHaveBeenCalledTimes(2))
    expect(fetchSchoolCalendar.mock.calls[0][0]).toEqual(['Year 3'])
    expect(fetchSchoolCalendar.mock.calls[1][0]).toEqual(['Year 3', 'Year 6'])
  })

  it('says when there is nothing on today rather than showing an empty card', async () => {
    fetchSchoolCalendar.mockResolvedValue({ ...CALENDAR, events: [] })
    render(<UpcomingPanel yearGroups={[]} onAsk={vi.fn()} />)

    expect(await screen.findByText('Nothing on at school today.')).toBeInTheDocument()
    expect(screen.getByText('Nothing else on the calendar until Sun 18 Oct.')).toBeInTheDocument()
  })

  it('offers a retry when the calendar cannot be loaded', async () => {
    fetchSchoolCalendar.mockRejectedValueOnce(new Error('boom')).mockResolvedValue(CALENDAR)
    render(<UpcomingPanel yearGroups={[]} onAsk={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: /Try again/ }))

    expect(await screen.findByText('Harvest Festival assembly')).toBeInTheDocument()
  })
})
