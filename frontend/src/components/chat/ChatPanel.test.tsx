import { act, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ChatPanel } from './ChatPanel'
import type { ChatResponse } from '../../services/chatService'

const chatMock = vi.hoisted(() => ({
  connect: vi.fn(),
  disconnect: vi.fn(),
  sendMessage: vi.fn(),
  onMessage: undefined as ((response: ChatResponse) => void) | undefined,
}))

vi.mock('../../services/chatService', () => ({
  connect: vi.fn((_sessionId, onMessage, onConnect) => {
    chatMock.onMessage = onMessage
    onConnect?.()
  }),
  disconnect: vi.fn(),
  sendMessage: vi.fn(),
}))

function response(partial: Partial<ChatResponse>): ChatResponse {
  return {
    sessionId: 'session-1',
    content: '',
    type: 'STREAM_CHUNK',
    timestamp: '2026-05-30T12:00:00Z',
    ...partial,
  } as ChatResponse
}

describe('ChatPanel', () => {
  beforeEach(() => {
    chatMock.onMessage = undefined
    vi.clearAllMocks()
  })

  it('renders streamed tool activity, widget cards, and framing text in order', () => {
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

    expect(screen.getByText('Used 1 tool')).toBeInTheDocument()
    expect(screen.getByText('Streaming chat')).toBeInTheDocument()
    expect(screen.getByText('These posts are a good starting point.')).toBeInTheDocument()
  })
})
