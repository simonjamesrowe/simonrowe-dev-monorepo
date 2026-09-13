import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import App from '../../src/school/App'
import type { SchoolChatHandlers } from '../../src/school/schoolChatService'
import type { SiteStatus } from '../../src/services/siteStatus'

// jsdom does not implement scrollIntoView
Element.prototype.scrollIntoView = vi.fn()

const theme = { theme: 'dark' as const, toggleTheme: vi.fn() }
vi.mock('../../src/contexts/ThemeContext', () => ({ useTheme: () => theme }))

let handlers: SchoolChatHandlers | null = null
const connect = vi.fn((_sessionId: string, given: SchoolChatHandlers) => {
  handlers = given
})
const disconnect = vi.fn()
const sendMessage = vi.fn(() => true)
vi.mock('../../src/school/schoolChatService', () => ({
  connect: (sessionId: string, given: SchoolChatHandlers) => connect(sessionId, given),
  disconnect: () => disconnect(),
  sendMessage: (...a: unknown[]) => sendMessage(...(a as [])),
  isConnected: () => true,
}))

const probeSiteStatus = vi.fn<() => Promise<SiteStatus>>()
const reload = vi.fn()
vi.mock('../../src/services/siteStatus', () => ({
  probeSiteStatus: () => probeSiteStatus(),
  reloadPage: () => reload(),
}))

beforeEach(() => {
  handlers = null
  connect.mockClear()
  disconnect.mockClear()
  sendMessage.mockClear()
  probeSiteStatus.mockReset()
  probeSiteStatus.mockResolvedValue('unreachable')
  reload.mockClear()
})

afterEach(() => {
  vi.restoreAllMocks()
})

/** Raises a transport state change the way the service would. */
async function state(next: 'connecting' | 'connected' | 'reconnecting' | 'offline') {
  await act(async () => {
    handlers?.onStateChange?.(next)
  })
}

describe('what Term Time shows while the socket is unhappy', () => {
  it('says nothing at all on first paint', () => {
    render(<App />)

    // Every visit starts in 'connecting'. A notice here would fire on every single page
    // load, which is how a connection warning stops meaning anything.
    expect(screen.queryByText(/Reconnecting/)).toBeNull()
    expect(screen.queryByText(/lost its connection/)).toBeNull()
  })

  it('says nothing at all about a routine reconnect', async () => {
    render(<App />)
    await state('reconnecting')

    // A dropped socket that is already coming back — typically inside a second — is not
    // something to tell the reader about. Announcing it is what made the page feel broken
    // every time a proxy timed an idle connection out.
    expect(screen.queryByText(/Reconnecting/)).toBeNull()
    expect(document.querySelector('.school-page__error')).toBeNull()
  })

  it('only says it is still trying, and offers nothing to press', async () => {
    render(<App />)
    await state('offline')

    expect(screen.getByText(/Reconnecting to Term Time/)).toBeInTheDocument()
    // Recovery is automatic. A button here would be a control that does what the page is
    // already doing, on a page whose whole problem was looking broken when it was not.
    expect(
      screen.queryByRole('button', { name: /try|retry|reconnect/i }),
    ).toBeNull()
    expect(document.querySelector('.school-page__error')).toBeNull()

    await state('connected')
    expect(screen.queryByText(/Reconnecting/)).toBeNull()
  })

  it('shows the landing page when the reason is a deploy', async () => {
    probeSiteStatus.mockResolvedValue('maintenance')
    render(<App />)

    await state('offline')

    // Reloading this exact url is the whole mechanism: nginx is already serving the themed
    // "update in progress" page here, and that page brings the reader back to Term Time
    // when the deploy finishes.
    await waitFor(() => expect(reload).toHaveBeenCalled())
  })

  it('stays put when the site is simply unreachable', async () => {
    probeSiteStatus.mockResolvedValue('unavailable')
    render(<App />)

    await state('offline')

    await waitFor(() => expect(probeSiteStatus).toHaveBeenCalled())
    // The transcript is still readable and the socket is still retrying. Replacing that
    // with an error page would be a downgrade.
    expect(reload).not.toHaveBeenCalled()
    expect(screen.getByText(/Reconnecting to Term Time/)).toBeInTheDocument()
  })

  it('does not ask about deploys while the connection is fine', async () => {
    render(<App />)
    await state('connected')

    expect(probeSiteStatus).not.toHaveBeenCalled()
  })
})

describe('a question that never got through', () => {
  it('stops the typing indicator and removes the empty reply', async () => {
    render(<App />)

    await act(async () => {
      screen.getByRole('button', { name: 'When is half term?' }).click()
    })
    await waitFor(() => expect(sendMessage).toHaveBeenCalled())
    // Three: the question, the empty reply the optimistic render added, and the typing
    // indicator, which renders in its own .chat-message wrapper while `awaiting` is set.
    expect(document.querySelectorAll('.chat-message')).toHaveLength(3)
    expect(screen.getByTestId('typing-indicator')).toBeInTheDocument()

    await act(async () => {
      handlers?.onUndelivered?.({
        sessionId: 's1',
        message: 'When is half term?',
        yearGroups: [],
        accessToken: null,
      })
    })

    // Only the question is left. Leaving the empty reply and the indicator is the failure
    // this whole change is about: the page looks like it is still thinking, forever.
    expect(document.querySelectorAll('.chat-message')).toHaveLength(1)
    expect(screen.queryByTestId('typing-indicator')).toBeNull()
    expect(screen.getByText(/did not get through/)).toBeInTheDocument()
  })
})
