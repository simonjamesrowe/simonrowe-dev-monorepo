import { describe, expect, it } from 'vitest'

import { applyChatStreamEvent, createEmptyAssistantMessage } from './chatStreamReducer'
import type { ChatResponse } from '../../services/chatService'

function event(partial: Partial<ChatResponse>): ChatResponse {
  return {
    sessionId: 'session-1',
    content: '',
    type: 'STREAM_CHUNK',
    timestamp: '2026-05-30T12:00:00Z',
    ...partial,
  } as ChatResponse
}

describe('chatStreamReducer', () => {
  it('appends stream chunks to a trailing text block', () => {
    const message = createEmptyAssistantMessage('12:00')

    const next = applyChatStreamEvent(
      applyChatStreamEvent(message, event({ content: 'Hello ' })),
      event({ content: 'there' }),
    )

    expect(next.blocks).toEqual([{ kind: 'text', content: 'Hello there' }])
    expect(next.finalized).toBe(false)
  })

  it('preserves tool and widget ordering between text blocks', () => {
    const payload = { groups: [] }
    let message = createEmptyAssistantMessage('12:00')

    message = applyChatStreamEvent(message, event({ content: 'Let me check. ' }))
    message = applyChatStreamEvent(message, event({
      type: 'TOOL_START',
      toolLabel: "Looking up Simon's skills",
    }))
    message = applyChatStreamEvent(message, event({
      type: 'WIDGET',
      widgetKind: 'skills',
      payload,
    }))
    message = applyChatStreamEvent(message, event({
      type: 'TOOL_END',
      toolLabel: "Looking up Simon's skills",
    }))
    message = applyChatStreamEvent(message, event({ content: 'These are the highlights.' }))

    expect(message.blocks).toEqual([
      { kind: 'text', content: 'Let me check. ' },
      { kind: 'tool', label: "Looking up Simon's skills", status: 'done' },
      { kind: 'widget', widgetKind: 'skills', payload },
      { kind: 'text', content: 'These are the highlights.' },
    ])
  })

  it('finalizes with a user-safe error text block', () => {
    const message = applyChatStreamEvent(
      createEmptyAssistantMessage('12:00'),
      event({ type: 'ERROR', content: 'Sorry, try again.' }),
    )

    expect(message.finalized).toBe(true)
    expect(message.blocks).toEqual([{ kind: 'text', content: 'Sorry, try again.' }])
  })

  it('reconciles scrambled prose to the authoritative fullResponse on STREAM_END, preserving tool/widget blocks', () => {
    const payload = { groups: [] }
    let message = createEmptyAssistantMessage('12:00')

    // Intermediate chunks arrive scrambled/interleaved.
    message = applyChatStreamEvent(message, event({ content: 'Jasper Reports F ' }))
    message = applyChatStreamEvent(message, event({
      type: 'TOOL_START',
      toolLabel: "Looking up Simon's skills",
    }))
    message = applyChatStreamEvent(message, event({
      type: 'WIDGET',
      widgetKind: 'skills',
      payload,
    }))
    message = applyChatStreamEvent(message, event({
      type: 'TOOL_END',
      toolLabel: "Looking up Simon's skills",
    }))
    message = applyChatStreamEvent(message, event({ content: 'Apache,OP scrambled' }))

    const finalMessage = applyChatStreamEvent(message, event({
      type: 'STREAM_END',
      content: 'He uses Jasper Reports, Apache FOP.',
    }))

    expect(finalMessage.finalized).toBe(true)
    // Exactly one clean text block equal to fullResponse; tool + widget preserved in order.
    expect(finalMessage.blocks).toEqual([
      { kind: 'text', content: 'He uses Jasper Reports, Apache FOP.' },
      { kind: 'tool', label: "Looking up Simon's skills", status: 'done' },
      { kind: 'widget', widgetKind: 'skills', payload },
    ])
    // Only one text block survives (no duplicate/scrambled prose).
    const textBlocks = (finalMessage.blocks ?? []).filter((b) => b.kind === 'text')
    expect(textBlocks).toHaveLength(1)
  })

  it('appends the authoritative fullResponse after tool/widget blocks when no prose streamed', () => {
    const payload = { groups: [] }
    let message = createEmptyAssistantMessage('12:00')
    message = applyChatStreamEvent(message, event({
      type: 'TOOL_START',
      toolLabel: "Looking up Simon's skills",
    }))
    message = applyChatStreamEvent(message, event({
      type: 'WIDGET',
      widgetKind: 'skills',
      payload,
    }))
    message = applyChatStreamEvent(message, event({
      type: 'TOOL_END',
      toolLabel: "Looking up Simon's skills",
    }))

    const finalMessage = applyChatStreamEvent(message, event({
      type: 'STREAM_END',
      content: 'Here are his skills.',
    }))

    expect(finalMessage.blocks).toEqual([
      { kind: 'tool', label: "Looking up Simon's skills", status: 'done' },
      { kind: 'widget', widgetKind: 'skills', payload },
      { kind: 'text', content: 'Here are his skills.' },
    ])
  })

  it('leaves incrementally-built text untouched when STREAM_END has empty content', () => {
    let message = createEmptyAssistantMessage('12:00')
    message = applyChatStreamEvent(message, event({ content: 'Streamed answer.' }))

    const finalMessage = applyChatStreamEvent(message, event({
      type: 'STREAM_END',
      content: '',
    }))

    expect(finalMessage.finalized).toBe(true)
    expect(finalMessage.blocks).toEqual([{ kind: 'text', content: 'Streamed answer.' }])
  })
})
