import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import App from '../../src/school/App'
import type { SchoolChatHandlers } from '../../src/school/schoolChatService'
import type { ChatResponse } from '../../src/services/chatService'

// jsdom does not implement scrollIntoView
Element.prototype.scrollIntoView = vi.fn()

const { track, configured, openCookieSettings } = vi.hoisted(() => ({
  track: vi.fn(),
  configured: vi.fn(() => false),
  openCookieSettings: vi.fn(),
}))
vi.mock('../../src/analytics/analytics', () => ({
  track,
  isAnalyticsConfigured: () => configured(),
  openCookieSettings: () => openCookieSettings(),
}))

const theme = { theme: 'dark' as const, toggleTheme: vi.fn() }
vi.mock('../../src/contexts/ThemeContext', () => ({ useTheme: () => theme }))

let handlers: SchoolChatHandlers | null = null
const sendMessage = vi.fn(() => true)
vi.mock('../../src/school/schoolChatService', () => ({
  connect: (_sessionId: string, given: SchoolChatHandlers) => {
    handlers = given
  },
  disconnect: vi.fn(),
  sendMessage: (...a: unknown[]) => sendMessage(...(a as [])),
  isConnected: () => true,
}))

vi.mock('../../src/services/siteStatus', () => ({
  probeSiteStatus: () => Promise.resolve('unreachable'),
  reloadPage: vi.fn(),
}))

const fetchSchoolCalendar = vi.fn()
vi.mock('../../src/school/schoolCalendarApi', () => ({
  fetchSchoolCalendar: (...a: unknown[]) => fetchSchoolCalendar(...a),
  CalendarUnavailableError: class extends Error {},
}))

function calls(name: string) {
  return track.mock.calls.filter(([event]) => event === name)
}

async function frame(type: ChatResponse['type'], extra: Partial<ChatResponse> = {}) {
  await act(async () => {
    handlers?.onMessage({ sessionId: 's', content: '', timestamp: 't', type, ...extra })
  })
}

beforeEach(() => {
  handlers = null
  track.mockClear()
  configured.mockReturnValue(false)
  openCookieSettings.mockClear()
  sendMessage.mockClear()
  fetchSchoolCalendar.mockReset()
  fetchSchoolCalendar.mockReturnValue(new Promise(() => {}))
  window.localStorage.clear()
})

afterEach(() => {
  vi.restoreAllMocks()
  window.localStorage.clear()
})

describe('Term Time analytics', () => {
  it('reports where a question came from and how many years were chosen, never its words', async () => {
    window.localStorage.setItem('term-time-year-groups', JSON.stringify(['Year 3', 'Year 6']))
    render(<App />)

    fireEvent.click(screen.getByRole('button', { name: 'When is half term?' }))
    await waitFor(() => expect(sendMessage).toHaveBeenCalled())

    expect(calls('Question Asked')).toEqual([
      ['Question Asked', { source: 'chip', yearGroupCount: 2 }],
    ])
  })

  it('reports a typed question as input', async () => {
    render(<App />)

    fireEvent.change(screen.getByPlaceholderText('Type a message...'), {
      target: { value: 'Is there swimming this week?' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send message' }))
    await waitFor(() => expect(sendMessage).toHaveBeenCalled())

    expect(calls('Question Asked')).toEqual([
      ['Question Asked', { source: 'input', yearGroupCount: 0 }],
    ])
  })

  it('reports the answer once, with its latency and how many tools it used', async () => {
    const now = vi.spyOn(performance, 'now')
    now.mockReturnValue(1_000)
    render(<App />)

    fireEvent.click(screen.getByRole('button', { name: 'When is half term?' }))
    await waitFor(() => expect(sendMessage).toHaveBeenCalled())

    await frame('TOOL_START', { toolLabel: 'Checking the calendar' })
    await frame('TOOL_END', { toolLabel: 'Checking the calendar' })
    await frame('TOOL_START', { toolLabel: 'Searching letters' })
    await frame('TOOL_END', { toolLabel: 'Searching letters' })
    await frame('STREAM_CHUNK', { content: 'Half term is ' })
    now.mockReturnValue(1_450)
    await frame('STREAM_END', { content: 'Half term is 26 October.' })
    // A stray second end, as a replayed frame would be, is not a second answer.
    await frame('STREAM_END', { content: 'Half term is 26 October.' })

    expect(calls('Answer Received')).toEqual([
      ['Answer Received', { toolCount: 2, latencyMs: 450, failed: false }],
    ])
  })

  it('reports an answer that ended in an error as failed', async () => {
    render(<App />)

    fireEvent.click(screen.getByRole('button', { name: 'When is half term?' }))
    await waitFor(() => expect(sendMessage).toHaveBeenCalled())
    await frame('ERROR', { content: 'Something went wrong' })

    expect(calls('Answer Received')).toHaveLength(1)
    expect(calls('Answer Received')[0][1]).toMatchObject({ toolCount: 0, failed: true })
  })

  it('reports an undelivered question as a failed answer', async () => {
    render(<App />)

    fireEvent.click(screen.getByRole('button', { name: 'When is half term?' }))
    await waitFor(() => expect(sendMessage).toHaveBeenCalled())
    await act(async () => {
      handlers?.onUndelivered?.({
        sessionId: 's',
        message: 'When is half term?',
        yearGroups: [],
        accessToken: null,
      })
    })

    expect(calls('Answer Received')).toHaveLength(1)
    expect(calls('Answer Received')[0][1]).toMatchObject({ failed: true })
  })

  it('reports a calendar click with the event type and the part of the pane it was in', async () => {
    fetchSchoolCalendar.mockResolvedValue({
      today: '2026-10-13',
      weekEnd: '2026-10-18',
      until: '2026-11-01',
      events: [
        {
          id: 'club',
          title: 'Chess club',
          startDate: '2026-10-13',
          endDate: '2026-10-13',
          allDay: false,
          time: '3:30pm',
          type: 'CLUB',
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
          location: null,
        },
      ],
    })
    render(<App />)

    fireEvent.click(await screen.findByText('Horniman Museum trip'))
    await waitFor(() => expect(sendMessage).toHaveBeenCalled())

    expect(calls('Calendar Event Clicked')).toEqual([
      ['Calendar Event Clicked', { eventType: 'TRIP', section: 'day' }],
    ])
    expect(calls('Question Asked')).toEqual([
      ['Question Asked', { source: 'calendar', yearGroupCount: 0 }],
    ])

    // The answer is still awaited, so a second question is not sent, but the click is reported.
    fireEvent.click(screen.getByText('Chess club'))
    expect(calls('Calendar Event Clicked')[1]).toEqual([
      'Calendar Event Clicked',
      { eventType: 'CLUB', section: 'today' },
    ])
  })

  it('reports a year group choice as a count', () => {
    render(<App />)

    fireEvent.click(screen.getByRole('checkbox', { name: 'Year 4' }))

    expect(calls('Year Group Selected')).toEqual([
      ['Year Group Selected', { yearGroupCount: 1 }],
    ])
  })

  it('reports a lost connection once per drop into offline, and not a routine reconnect', async () => {
    render(<App />)

    await act(async () => handlers?.onStateChange?.('reconnecting'))
    expect(calls('Connection Lost')).toHaveLength(0)

    await act(async () => handlers?.onStateChange?.('offline'))
    expect(calls('Connection Lost')).toHaveLength(1)

    await act(async () => handlers?.onStateChange?.('connected'))
    await act(async () => handlers?.onStateChange?.('offline'))
    expect(calls('Connection Lost')).toHaveLength(2)
  })

  it('offers cookie settings in the footer only when analytics is built in', () => {
    const { unmount } = render(<App />)
    expect(screen.queryByRole('button', { name: 'Cookie settings' })).toBeNull()
    unmount()

    configured.mockReturnValue(true)
    render(<App />)
    const button = screen.getByRole('button', { name: 'Cookie settings' })
    expect(button.closest('footer')).not.toBeNull()
    fireEvent.click(button)
    expect(openCookieSettings).toHaveBeenCalledTimes(1)
  })
})
