import { render, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

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

describe('a question handed over in the address', () => {
  beforeEach(() => {
    sendMessage.mockReset()
    window.localStorage.clear()
  })

  afterEach(() => {
    window.history.replaceState(null, '', '/')
  })

  it('is asked once, with the saved year groups, and removed from the address', async () => {
    window.localStorage.setItem('term-time-year-groups', JSON.stringify(['Year 3']))
    window.history.replaceState(null, '', '/?q=When%20is%20half%20term%3F&utm=x#top')

    render(<App />)

    await waitFor(() => expect(sendMessage).toHaveBeenCalledTimes(1))
    expect(sendMessage.mock.calls[0][0]).toMatchObject({
      message: 'When is half term?',
      yearGroups: ['Year 3'],
    })
    expect(window.location.search).toBe('?utm=x')
    expect(window.location.hash).toBe('#top')
  })

  it('is capped at the input limit', async () => {
    window.history.replaceState(null, '', `/?q=${'a'.repeat(600)}`)

    render(<App />)

    await waitFor(() => expect(sendMessage).toHaveBeenCalledTimes(1))
    expect(sendMessage.mock.calls[0][0].message).toHaveLength(500)
  })

  it('asks nothing when there is no question', async () => {
    window.history.replaceState(null, '', '/?q=%20%20')

    render(<App />)

    await new Promise(resolve => setTimeout(resolve, 20))
    expect(sendMessage).not.toHaveBeenCalled()
  })
})
