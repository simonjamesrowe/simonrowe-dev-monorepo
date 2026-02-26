import { Bot, User } from 'lucide-react'

interface ChatMessageProps {
  role: 'user' | 'assistant'
  content: string
  timestamp?: string
}

export function ChatMessage({ role, content, timestamp }: ChatMessageProps) {
  const isUser = role === 'user'

  return (
    <div className={`chat-message ${isUser ? 'chat-message--user' : 'chat-message--assistant'}`}>
      <div className="chat-message__avatar">
        {isUser ? <User size={14} /> : <Bot size={14} />}
      </div>
      <div>
        <div className="chat-message__bubble">{content}</div>
        {timestamp && <div className="chat-message__time">{timestamp}</div>}
      </div>
    </div>
  )
}
