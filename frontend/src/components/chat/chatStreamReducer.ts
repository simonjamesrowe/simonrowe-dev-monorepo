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
    // The server accumulates the full answer and sends it here. Treat it as
    // authoritative for the prose so the final answer is clean even if the
    // intermediate chunks arrived scrambled or interleaved. Tool/widget blocks
    // are preserved; only the streamed text is reconciled.
    if (response.content && response.content.trim().length > 0) {
      return { ...message, blocks: reconcileProse(blocks, response.content), finalized: true }
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

// Replace all streamed text blocks with a single authoritative prose block,
// keeping tool/widget blocks in their arrival order. The reconciled prose is
// placed where the first streamed text block appeared; if no prose was streamed
// it is appended after the tool/widget blocks.
function reconcileProse(blocks: ChatBlock[], authoritative: string): ChatBlock[] {
  const proseBlock: ChatBlock = { kind: 'text', content: authoritative }
  const hasText = blocks.some((block) => block.kind === 'text')

  if (!hasText) {
    return [...blocks, proseBlock]
  }

  const result: ChatBlock[] = []
  let inserted = false
  for (const block of blocks) {
    if (block.kind === 'text') {
      if (!inserted) {
        result.push(proseBlock)
        inserted = true
      }
      // Drop any further streamed text fragments; the authoritative prose wins.
    } else {
      result.push(block)
    }
  }
  return result
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
