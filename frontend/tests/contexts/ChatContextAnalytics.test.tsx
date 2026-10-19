import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { track } from '../../src/analytics/analytics'
import { ChatProvider, useChat } from '../../src/contexts/ChatContext'

vi.mock('../../src/analytics/analytics', () => ({ track: vi.fn() }))

const wrapper = ({ children }: { children: ReactNode }) => <ChatProvider>{children}</ChatProvider>

describe('ChatContext analytics', () => {
  beforeEach(() => vi.mocked(track).mockClear())

  it('records the open only once the reCAPTCHA is passed, credited to where it started', () => {
    const { result } = renderHook(() => useChat(), { wrapper })

    act(() => result.current.openChat(undefined, 'hero'))
    expect(track).not.toHaveBeenCalled()

    act(() => result.current.handleRecaptchaVerified())
    expect(track).toHaveBeenCalledWith('Chat Opened', { source: 'hero' })
  })

  it('records an open straight away once the visitor has passed the reCAPTCHA', () => {
    const { result } = renderHook(() => useChat(), { wrapper })
    act(() => result.current.openChat())
    act(() => result.current.handleRecaptchaVerified())
    act(() => result.current.closeChat())
    vi.mocked(track).mockClear()

    act(() => result.current.openChat('Kafka', 'search'))

    expect(track).toHaveBeenCalledWith('Chat Opened', { source: 'search' })
  })

  it('records an abandoned reCAPTCHA as no open at all', () => {
    const { result } = renderHook(() => useChat(), { wrapper })

    act(() => result.current.openChat(undefined, 'pill'))
    act(() => result.current.cancelRecaptcha())

    expect(track).not.toHaveBeenCalled()
  })

  it('credits the tour', () => {
    const { result } = renderHook(() => useChat(), { wrapper })

    act(() => result.current.openChatBypassRecaptcha('What does Simon do?'))

    expect(track).toHaveBeenCalledWith('Chat Opened', { source: 'tour' })
  })
})
