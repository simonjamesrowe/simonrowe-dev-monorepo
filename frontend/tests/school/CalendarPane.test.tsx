import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import App from '../../src/school/App'

// jsdom does not implement scrollIntoView
Element.prototype.scrollIntoView = vi.fn()

const theme = { theme: 'dark' as const, toggleTheme: vi.fn() }
vi.mock('../../src/contexts/ThemeContext', () => ({ useTheme: () => theme }))

const sendMessage = vi.fn()
vi.mock('../../src/school/schoolChatService', () => ({
  connect: vi.fn(),
  disconnect: vi.fn(),
  sendMessage: (...a: unknown[]) => sendMessage(...a),
  isConnected: () => true,
}))

vi.mock('../../src/school/schoolNewsApi', () => ({
  fetchNewsletters: () => new Promise(() => {}),
}))

const fetchSchoolCalendar = vi.fn()
vi.mock('../../src/school/schoolCalendarApi', () => ({
  fetchSchoolCalendar: (...a: unknown[]) => fetchSchoolCalendar(...a),
  CalendarUnavailableError: class extends Error {},
}))

describe('the Coming up card', () => {
  it('asks the chat about a clicked event with the visitor’s year groups, and shows the answer', async () => {
    window.localStorage.setItem('term-time-year-groups', JSON.stringify(['Year 3']))
    fetchSchoolCalendar.mockResolvedValue({
      today: '2026-10-13',
      weekEnd: '2026-10-18',
      until: '2026-11-01',
      events: [
        {
          id: 'trip',
          title: 'Horniman Museum trip',
          startDate: '2026-10-15',
          endDate: '2026-10-15',
          allDay: false,
          time: '9:15am',
          type: 'TRIP',
          yearGroups: ['Year 3'],
          location: null,
        },
      ],
    })
    render(<App />)

    // The page opens on the home view, with the calendar and no conversation.
    expect(screen.queryByRole('region', { name: 'Conversation' })).toBeNull()
    expect(fetchSchoolCalendar.mock.calls[0][0]).toEqual(['Year 3'])

    fireEvent.click(await screen.findByText('Horniman Museum trip'))

    await waitFor(() => expect(sendMessage).toHaveBeenCalled())
    expect(sendMessage.mock.calls[0][0]).toMatchObject({
      message: 'Tell me more about "Horniman Museum trip" on Thursday 15 October.',
      yearGroups: ['Year 3'],
    })
    // Asking switches to the conversation, with the calendar still beside it.
    expect(screen.getByRole('region', { name: 'Conversation' })).toBeInTheDocument()
    expect(screen.getByText('Horniman Museum trip')).toBeInTheDocument()
    window.localStorage.clear()
  })
})
