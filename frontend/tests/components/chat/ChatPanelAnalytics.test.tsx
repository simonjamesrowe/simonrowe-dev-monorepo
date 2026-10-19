import { act, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { track } from '../../../src/analytics/analytics'
import { ChatPanel } from '../../../src/components/chat/ChatPanel'
import type { ChatResponse } from '../../../src/services/chatService'

vi.mock('../../../src/analytics/analytics', () => ({ track: vi.fn() }))
vi.mock('../../../src/services/chatService', () => ({
  connect: vi.fn(),
  disconnect: vi.fn(),
  sendMessage: vi.fn(),
  isConnected: vi.fn(() => false),
}))

import * as chatService from '../../../src/services/chatService'

vi.stubGlobal('crypto', { randomUUID: vi.fn(() => 'session-1') })
Element.prototype.scrollIntoView = vi.fn()

function frame(type: ChatResponse['type'], extra: Partial<ChatResponse> = {}): ChatResponse {
  return { sessionId: 'session-1', content: '', type, timestamp: '2026-10-19T12:00:00Z', ...extra }
}

/** Renders the panel with a seeded question, connects, and returns the stream's frame handler. */
function openWithQuestion(): (response: ChatResponse) => void {
  let onMessage: ((response: ChatResponse) => void) | undefined
  vi.mocked(chatService.connect).mockImplementation((_id, handler, onConnect) => {
    onMessage = handler
    onConnect?.()
  })
  render(<ChatPanel initialQuery="What did Simon build?" onClose={vi.fn()} />)
  act(() => {
    vi.advanceTimersByTime(100)
  })
  return (response) => act(() => onMessage?.(response))
}

describe('ChatPanel analytics', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    let now = 1_000
    vi.spyOn(performance, 'now').mockImplementation(() => {
      now += 1_500
      return now
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('records the question, never its text', () => {
    openWithQuestion()

    expect(track).toHaveBeenCalledWith('Chat Question Sent')
  })

  it('records the answer once, with how many tools it used and how long it took', () => {
    const send = openWithQuestion()

    send(frame('STREAM_START'))
    send(frame('TOOL_START', { toolLabel: 'Searching blogs' }))
    send(frame('TOOL_END', { toolLabel: 'Searching blogs' }))
    send(frame('STREAM_CHUNK', { content: 'He built Term Time.' }))
    send(frame('STREAM_END', { content: 'He built Term Time.' }))
    send(frame('STREAM_END', { content: 'He built Term Time.' }))

    const answers = vi.mocked(track).mock.calls.filter(([name]) => name === 'Chat Answer Received')
    expect(answers).toEqual([['Chat Answer Received', { toolCount: 1, latencyMs: 1_500, failed: false }]])
  })

  it('records an error as a failed answer', () => {
    const send = openWithQuestion()

    send(frame('STREAM_START'))
    send(frame('ERROR', { content: 'Something went wrong' }))

    expect(track).toHaveBeenCalledWith('Chat Answer Received', {
      toolCount: 0,
      latencyMs: 1_500,
      failed: true,
    })
  })
})
