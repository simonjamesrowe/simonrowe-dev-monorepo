import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ChatPanel } from './ChatPanel'
import * as chatService from '../../services/chatService'
import type { ChatResponse } from '../../services/chatService'

const chatMock = vi.hoisted(() => ({
  connect: vi.fn(),
  disconnect: vi.fn(),
  sendMessage: vi.fn(),
  sessionId: undefined as string | undefined,
  onMessage: undefined as ((response: ChatResponse) => void) | undefined,
}))

vi.mock('../../services/chatService', () => ({
  connect: vi.fn((sessionId, onMessage, onConnect) => {
    chatMock.sessionId = sessionId
    chatMock.onMessage = onMessage
    onConnect?.()
  }),
  disconnect: vi.fn(),
  sendMessage: vi.fn(),
}))

function response(partial: Partial<ChatResponse>): ChatResponse {
  return {
    sessionId: chatMock.sessionId ?? 'session-1',
    content: '',
    type: 'STREAM_CHUNK',
    timestamp: '2026-05-30T12:00:00Z',
    ...partial,
  } as ChatResponse
}

describe('ChatPanel', () => {
  beforeEach(() => {
    chatMock.sessionId = undefined
    chatMock.onMessage = undefined
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders one clean answer even when chunks and STREAM_END are delivered twice (duplicate subscription)', () => {
    render(<ChatPanel onClose={() => {}} visible />)

    const clean = 'He uses Kafka encoding and decoding with Testcontainers.'
    // Simulate a duplicate STOMP subscription: every frame delivered twice, interleaved.
    act(() => {
      chatMock.onMessage?.(response({ type: 'STREAM_START' }))
      chatMock.onMessage?.(response({ type: 'STREAM_START' }))
      const chunks = ['He uses Kafka ', 'encoding ', 'and decoding ', 'with Testcontainers.']
      for (const c of chunks) {
        chatMock.onMessage?.(response({ type: 'STREAM_CHUNK', content: c }))
        chatMock.onMessage?.(response({ type: 'STREAM_CHUNK', content: c }))
      }
      chatMock.onMessage?.(response({ type: 'STREAM_END', content: clean }))
      chatMock.onMessage?.(response({ type: 'STREAM_END', content: clean }))
    })

    // The finalized answer must equal the authoritative text exactly, once.
    expect(screen.getAllByText(clean)).toHaveLength(1)
    // No scrambled/doubled fragment like "He uses Kafka He uses Kafka" should survive.
    expect(screen.queryByText(/He uses Kafka He uses Kafka/)).not.toBeInTheDocument()
  })

  it('renders streamed tool activity and framing text; hides the widget detail card', () => {
    render(<ChatPanel onClose={() => {}} visible />)

    act(() => {
      chatMock.onMessage?.(response({ type: 'STREAM_START' }))
      chatMock.onMessage?.(response({
        type: 'TOOL_START',
        toolLabel: 'Searching blog posts',
      }))
    })

    expect(screen.getByText('Searching blog posts')).toBeInTheDocument()

    act(() => {
      chatMock.onMessage?.(response({
        type: 'WIDGET',
        widgetKind: 'blogs',
        payload: {
          posts: [{
            title: 'Streaming chat',
            summary: 'Why visible progress matters',
            url: '/blogs/streaming-chat',
          }],
        },
      }))
      chatMock.onMessage?.(response({
        type: 'TOOL_END',
        toolLabel: 'Searching blog posts',
      }))
      chatMock.onMessage?.(response({
        type: 'STREAM_CHUNK',
        content: 'These posts are a good starting point.',
      }))
      chatMock.onMessage?.(response({ type: 'STREAM_END' }))
    })

    // Finished tool block shows the contextual label, not "Used 1 tool".
    expect(screen.queryByText('Used 1 tool')).not.toBeInTheDocument()
    expect(screen.getByText('Searching blog posts')).toBeInTheDocument()
    expect(screen.getByText('These posts are a good starting point.')).toBeInTheDocument()
    // The widget detail card is intentionally hidden (its title must not render).
    expect(screen.queryByText('Streaming chat')).not.toBeInTheDocument()
  })

  it('does not send a message or render an assistant response on initial mount without an initial query', () => {
    render(<ChatPanel onClose={() => {}} visible />)

    expect(chatService.connect).toHaveBeenCalledTimes(1)
    expect(chatService.sendMessage).not.toHaveBeenCalled()
    expect(screen.getByText("Hi, I'm Simon's AI assistant")).toBeInTheDocument()
    expect(screen.queryByText('Assistant')).not.toBeInTheDocument()
    expect(screen.queryByText(/Used \d+ tools?/)).not.toBeInTheDocument()
  })

  it('sends an initial query once after connecting', () => {
    vi.useFakeTimers()

    render(<ChatPanel onClose={() => {}} initialQuery="Show me recent AI work" visible />)

    expect(screen.getByText('Show me recent AI work')).toBeInTheDocument()
    expect(chatService.sendMessage).not.toHaveBeenCalled()

    act(() => {
      vi.advanceTimersByTime(50)
    })

    expect(chatService.sendMessage).toHaveBeenCalledTimes(1)
    expect(chatService.sendMessage).toHaveBeenCalledWith({
      sessionId: expect.any(String),
      message: 'Show me recent AI work',
    })
  })

  it('does not send a delayed initial query after clearing chat', () => {
    vi.useFakeTimers()

    render(<ChatPanel onClose={() => {}} initialQuery="Show me recent AI work" visible />)

    expect(screen.getByText('Show me recent AI work')).toBeInTheDocument()
    expect(chatService.sendMessage).not.toHaveBeenCalled()

    act(() => {
      screen.getByRole('button', { name: /clear chat/i }).click()
      vi.advanceTimersByTime(50)
    })

    expect(screen.queryByText('Show me recent AI work')).not.toBeInTheDocument()
    expect(chatService.sendMessage).not.toHaveBeenCalled()
  })

  it('ignores duplicate stream end events for the same assistant response', () => {
    render(<ChatPanel onClose={() => {}} visible />)

    act(() => {
      chatMock.onMessage?.(response({ type: 'STREAM_START' }))
      chatMock.onMessage?.(response({ type: 'STREAM_CHUNK', content: 'One answer.' }))
      chatMock.onMessage?.(response({ type: 'STREAM_END' }))
      chatMock.onMessage?.(response({ type: 'STREAM_END' }))
    })

    expect(screen.getAllByText('One answer.')).toHaveLength(1)
  })

  it('does not replay an old response after clearing chat', () => {
    render(<ChatPanel onClose={() => {}} visible />)
    const oldSessionId = chatMock.sessionId
    const oldOnMessage = chatMock.onMessage

    act(() => {
      oldOnMessage?.(response({ sessionId: oldSessionId, type: 'STREAM_START' }))
      oldOnMessage?.(response({
        sessionId: oldSessionId,
        type: 'STREAM_CHUNK',
        content: 'Old response.',
      }))
      oldOnMessage?.(response({ sessionId: oldSessionId, type: 'STREAM_END' }))
    })

    expect(screen.getByText('Old response.')).toBeInTheDocument()

    act(() => {
      screen.getByRole('button', { name: /clear chat/i }).click()
    })

    expect(chatMock.sessionId).not.toBe(oldSessionId)
    expect(screen.queryByText('Old response.')).not.toBeInTheDocument()

    act(() => {
      oldOnMessage?.(response({ sessionId: oldSessionId, type: 'STREAM_START' }))
      oldOnMessage?.(response({
        sessionId: oldSessionId,
        type: 'STREAM_CHUNK',
        content: 'Old response.',
      }))
      oldOnMessage?.(response({ sessionId: oldSessionId, type: 'STREAM_END' }))
    })

    expect(screen.queryByText('Old response.')).not.toBeInTheDocument()
    expect(chatService.sendMessage).not.toHaveBeenCalled()
  })
})
