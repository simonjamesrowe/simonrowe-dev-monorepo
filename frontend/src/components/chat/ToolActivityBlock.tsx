import { Check, Loader2 } from 'lucide-react'

import type { ChatBlock } from './chatTypes'

interface ToolActivityBlockProps {
  block: Extract<ChatBlock, { kind: 'tool' }>
}

export function ToolActivityBlock({ block }: ToolActivityBlockProps) {
  if (block.status === 'running') {
    return (
      <div className="chat-tool chat-tool--running">
        <Loader2 size={14} className="chat-tool__spinner" />
        <span>{block.label}</span>
      </div>
    )
  }

  return (
    <details className="chat-tool chat-tool--done">
      <summary>
        <Check size={14} />
        <span>Used 1 tool</span>
      </summary>
      <div className="chat-tool__details">{block.label}</div>
    </details>
  )
}
