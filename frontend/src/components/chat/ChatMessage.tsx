import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { User } from 'lucide-react'
import { ToolActivityBlock } from './ToolActivityBlock'
import { ChatWidget } from './widgets/ChatWidgetRegistry'
import type { ChatBlock } from './chatTypes'

interface ChatMessageProps {
  role: 'user' | 'assistant'
  content?: string
  blocks?: ChatBlock[]
  timestamp?: string
  profileImageUrl?: string
}

export function ChatMessage({ role, content = '', blocks, timestamp, profileImageUrl }: ChatMessageProps) {
  const isUser = role === 'user'
  const assistantBlocks = !isUser && blocks?.length ? blocks : undefined

  return (
    <div className={`chat-message ${isUser ? 'chat-message--user' : 'chat-message--assistant'}`}>
      <div className="chat-message__avatar">
        {isUser ? (
          <User size={14} />
        ) : profileImageUrl ? (
          <img src={profileImageUrl} alt="Assistant" className="chat-message__avatar-img" />
        ) : (
          <User size={14} />
        )}
      </div>
      <div>
        <div className="chat-message__bubble">
          {isUser ? (
            content
          ) : assistantBlocks ? (
            <div className="chat-message__blocks">
              {assistantBlocks.map((block, index) => {
                if (block.kind === 'text') {
                  return (
                    <ReactMarkdown remarkPlugins={[remarkGfm]} key={`text-${index}`}>
                      {block.content}
                    </ReactMarkdown>
                  )
                }
                if (block.kind === 'tool') {
                  return <ToolActivityBlock block={block} key={`tool-${index}-${block.label}`} />
                }
                return (
                  <ChatWidget
                    widgetKind={block.widgetKind}
                    payload={block.payload}
                    key={`widget-${index}-${block.widgetKind}`}
                  />
                )
              })}
            </div>
          ) : (
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
            >
              {content}
            </ReactMarkdown>
          )}
        </div>
        {timestamp && <div className="chat-message__time">{timestamp}</div>}
      </div>
    </div>
  )
}
