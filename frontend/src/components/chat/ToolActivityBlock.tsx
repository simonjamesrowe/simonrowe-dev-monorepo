import {
  Braces,
  Briefcase,
  CalendarDays,
  FileText,
  Loader2,
  Newspaper,
  Sparkles,
  Wrench,
} from 'lucide-react'
import type { ComponentType } from 'react'

import type { ChatBlock } from './chatTypes'

interface ToolActivityBlockProps {
  block: Extract<ChatBlock, { kind: 'tool' }>
}

/**
 * Tool blocks carry only a label and a status — the stream has no tool identifier — so the
 * icon is derived from distinctive words in the label. Matching on keywords rather than the
 * exact strings means a reworded label still resolves (the labels are backend constants in
 * `ProfileMcpTools`, out of this file's reach), and anything unrecognised falls back to a
 * generic tool icon rather than rendering none.
 */
const ICONS_BY_KEYWORD: [RegExp, ComponentType<{ size?: number; className?: string }>][] = [
  [/skill/i, Sparkles],
  [/employment|job|role|career/i, Briefcase],
  [/code/i, Braces],
  [/blog|post|writing/i, FileText],
  [/news/i, Newspaper],
  [/event/i, CalendarDays],
]

function iconFor(label: string) {
  const match = ICONS_BY_KEYWORD.find(([pattern]) => pattern.test(label))
  return match ? match[1] : Wrench
}

export function ToolActivityBlock({ block }: ToolActivityBlockProps) {
  const Icon = iconFor(block.label)
  const isRunning = block.status === 'running'

  return (
    <div
      className={`chat-tool ${isRunning ? 'chat-tool--running' : 'chat-tool--done'}`}
      data-testid="tool-activity"
    >
      <Icon size={14} className="chat-tool__icon" />
      <span>{block.label}</span>
      {isRunning ? <Loader2 size={13} className="chat-tool__spinner" /> : null}
    </div>
  )
}
