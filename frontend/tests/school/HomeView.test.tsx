import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import App from '../../src/school/App'

// jsdom does not implement scrollIntoView
Element.prototype.scrollIntoView = vi.fn()

const { track } = vi.hoisted(() => ({ track: vi.fn() }))
vi.mock('../../src/analytics/analytics', () => ({
  track,
  isAnalyticsConfigured: () => false,
  openCookieSettings: vi.fn(),
}))

const theme = { theme: 'dark' as const, toggleTheme: vi.fn() }
vi.mock('../../src/contexts/ThemeContext', () => ({ useTheme: () => theme }))

const sendMessage = vi.fn()
vi.mock('../../src/school/schoolChatService', () => ({
  connect: vi.fn(),
  disconnect: vi.fn(),
  sendMessage: (...a: unknown[]) => sendMessage(...a),
  isConnected: () => true,
}))

vi.mock('../../src/school/schoolCalendarApi', () => ({
  fetchSchoolCalendar: () => new Promise(() => {}),
  CalendarUnavailableError: class extends Error {},
}))

const fetchNewsletters = vi.fn()
vi.mock('../../src/school/schoolNewsApi', () => ({
  fetchNewsletters: (...a: unknown[]) => fetchNewsletters(...a),
  fetchSchoolMessages: () => Promise.resolve([]),
}))

describe('the home view and the conversation', () => {
  beforeEach(() => {
    sendMessage.mockReset()
    track.mockClear()
    fetchNewsletters.mockResolvedValue([
      {
        id: '171',
        title: 'Newsletter - 23rd October 2026',
        url: 'https://www.kilmorieschool.co.uk/parentportal/newsletter/?id=171',
        publishedAt: '2026-10-23T00:00:00Z',
        headline: 'End of a busy first half term',
        points: [{ text: 'INSET day Monday 2 November.', yearGroups: [] }],
      },
    ])
    window.localStorage.clear()
  })

  it('opens on the home view with no beta notice and no conversation', async () => {
    render(<App />)

    expect(screen.getByRole('textbox', { name: 'Ask a question' })).toBeInTheDocument()
    expect(await screen.findByText('End of a busy first half term')).toBeInTheDocument()
    expect(screen.queryByText(/early version/)).toBeNull()
    expect(screen.queryByText('Beta')).toBeNull()
    expect(screen.queryByRole('region', { name: 'Conversation' })).toBeNull()
  })

  it('moves to the conversation when a question is asked, and back again without losing it', async () => {
    render(<App />)

    fireEvent.change(screen.getByRole('textbox', { name: 'Ask a question' }), {
      target: { value: 'When do clubs restart?' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send message' }))
    await waitFor(() => expect(sendMessage).toHaveBeenCalled())

    expect(screen.getByRole('region', { name: 'Conversation' })).toBeInTheDocument()
    expect(screen.getByText('When do clubs restart?')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Back to this week/ }))
    expect(screen.queryByRole('region', { name: 'Conversation' })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: /Back to your conversation/ }))
    expect(screen.getByText('When do clubs restart?')).toBeInTheDocument()
  })

  it('asks about the newsletter from its card, and says where the question came from', async () => {
    render(<App />)

    fireEvent.click(await screen.findByRole('button', { name: /Ask about it/ }))

    await waitFor(() => expect(sendMessage).toHaveBeenCalled())
    expect(sendMessage.mock.calls[0][0]).toMatchObject({
      message: 'What was in the school newsletter of Friday 23 October?',
    })
    expect(track).toHaveBeenCalledWith('Question Asked', {
      source: 'newsletter',
      yearGroupCount: 0,
    })
  })
})
