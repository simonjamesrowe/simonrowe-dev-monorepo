import type { ChatResponse } from '../../services/chatService'
import type { ChatBlock, ChatMessageModel } from './chatTypes'

export function createEmptyAssistantMessage(timestamp: string): ChatMessageModel {
  return {
    role: 'assistant',
    blocks: [],
    timestamp,
    finalized: false,
  }
}

export function applyChatStreamEvent(
  message: ChatMessageModel,
  response: ChatResponse,
): ChatMessageModel {
  const blocks = [...(message.blocks ?? [])]

  if (response.type === 'STREAM_CHUNK') {
    appendText(blocks, response.content)
    return { ...message, blocks, finalized: false }
  }

  if (response.type === 'TOOL_START' && response.toolLabel) {
    blocks.push({ kind: 'tool', label: response.toolLabel, status: 'running' })
    return { ...message, blocks, finalized: false }
  }

  if (response.type === 'TOOL_END' && response.toolLabel) {
    const index = findLastRunningToolIndex(blocks, response.toolLabel)
    if (index >= 0) {
      blocks[index] = { kind: 'tool', label: response.toolLabel, status: 'done' }
    }
    return { ...message, blocks, finalized: false }
  }

  if (response.type === 'WIDGET' && response.widgetKind && response.payload != null) {
    blocks.push({
      kind: 'widget',
      widgetKind: response.widgetKind,
      payload: response.payload,
    })
    return { ...message, blocks, finalized: false }
  }

  if (response.type === 'STREAM_END') {
    if (blocks.length === 0 && response.content) {
      appendText(blocks, response.content)
    }
    return { ...message, blocks, finalized: true }
  }

  if (response.type === 'ERROR') {
    appendText(blocks, response.content || 'An error occurred. Please try again.')
    return { ...message, blocks, finalized: true }
  }

  return { ...message, blocks }
}

export function finalizeAssistantMessage(message: ChatMessageModel): ChatMessageModel {
  return { ...message, finalized: true }
}

function appendText(blocks: ChatBlock[], content: string) {
  if (!content) return
  const last = blocks[blocks.length - 1]
  if (last?.kind === 'text') {
    blocks[blocks.length - 1] = { ...last, content: last.content + content }
  } else {
    blocks.push({ kind: 'text', content })
  }
}

function findLastRunningToolIndex(blocks: ChatBlock[], label: string): number {
  for (let i = blocks.length - 1; i >= 0; i -= 1) {
    const block = blocks[i]
    if (block.kind === 'tool' && block.label === label && block.status === 'running') {
      return i
    }
  }
  return -1
}
