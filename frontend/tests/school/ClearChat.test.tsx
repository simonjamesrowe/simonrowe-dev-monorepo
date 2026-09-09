import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import App from '../../src/school/App'

// jsdom does not implement scrollIntoView
Element.prototype.scrollIntoView = vi.fn()

const theme = { theme: 'dark' as const, toggleTheme: vi.fn() }
vi.mock('../../src/contexts/ThemeContext', () => ({ useTheme: () => theme }))

const connect = vi.fn()
const disconnect = vi.fn()
const sendMessage = vi.fn()
vi.mock('../../src/school/schoolChatService', () => ({
  connect: (...a: unknown[]) => connect(...a),
  disconnect: (...a: unknown[]) => disconnect(...a),
  sendMessage: (...a: unknown[]) => sendMessage(...a),
  isConnected: () => true,
}))

describe('clearing the chat', () => {
  beforeEach(() => {
    connect.mockReset()
    disconnect.mockReset()
    sendMessage.mockReset()
  })

  it('offers no clear control until something has been said', () => {
    render(<App />)
    expect(screen.queryByRole('button', { name: /Clear this conversation/ })).toBeNull()
  })

  it('empties the transcript and starts a new session', async () => {
    render(<App />)
    fireEvent.click(screen.getByRole('button', { name: 'When is half term?' }))

    await waitFor(() => expect(sendMessage).toHaveBeenCalled())
    const firstSession = sendMessage.mock.calls[0][0].sessionId
    expect(document.querySelectorAll('.chat-message').length).toBeGreaterThan(0)

    fireEvent.click(screen.getByRole('button', { name: /Clear this conversation/ }))

    // Asserted on the transcript, not on the text: once cleared, the welcome panel returns and
    // one of its suggestion chips carries exactly the same wording as the question.
    await waitFor(() => expect(document.querySelectorAll('.chat-message')).toHaveLength(0))
    expect(screen.getByText('Ask about school')).toBeInTheDocument()

    // A new session id, not just an empty list: the backend caps messages per session and
    // names the reply topic after it, so reuse would carry the old cap across.
    fireEvent.click(screen.getByRole('button', { name: 'When is half term?' }))
    await waitFor(() => expect(sendMessage).toHaveBeenCalledTimes(2))
    expect(sendMessage.mock.calls[1][0].sessionId).not.toBe(firstSession)
  })

  it('drops the previous subscription rather than leaving it live', async () => {
    render(<App />)
    fireEvent.click(screen.getByRole('button', { name: 'When is half term?' }))
    await waitFor(() => expect(sendMessage).toHaveBeenCalled())

    const disconnectsBefore = disconnect.mock.calls.length
    fireEvent.click(screen.getByRole('button', { name: /Clear this conversation/ }))

    expect(disconnect.mock.calls.length).toBeGreaterThan(disconnectsBefore)
    expect(connect).toHaveBeenCalledTimes(2)
  })
})
