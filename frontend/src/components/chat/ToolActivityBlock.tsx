import { Check, Loader2 } from 'lucide-react'

import type { ChatBlock } from './chatTypes'

interface ToolActivityBlockProps {
  block: Extract<ChatBlock, { kind: 'tool' }>
}

export function ToolActivityBlock({ block }: ToolActivityBlockProps) {
  if (block.status === 'running') {
    return (
      <div className="chat-tool chat-tool--running" data-testid="tool-activity">
        <Loader2 size={14} className="chat-tool__spinner" />
        <span>{block.label}</span>
      </div>
    )
  }

  return (
    <div className="chat-tool chat-tool--done" data-testid="tool-activity">
      <Check size={14} />
      <span>{block.label}</span>
    </div>
  )
}
