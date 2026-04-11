import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { User } from 'lucide-react'

interface ChatMessageProps {
  role: 'user' | 'assistant'
  content: string
  timestamp?: string
  profileImageUrl?: string
  onCodeExampleClick?: (id: string) => void
}

export function ChatMessage({ role, content, timestamp, profileImageUrl, onCodeExampleClick }: ChatMessageProps) {
  const isUser = role === 'user'

  const handleLinkClick = (href: string | undefined, e: React.MouseEvent) => {
    if (!href) return
    const codeExampleMatch = href.match(/\/code-examples\/([a-f0-9]+)/)
    if (codeExampleMatch && onCodeExampleClick) {
      e.preventDefault()
      onCodeExampleClick(codeExampleMatch[1])
    }
  }

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
          ) : (
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={{
                a: ({ href, children }) => (
                  <a
                    href={href}
                    target="_blank"
                    rel="noopener noreferrer"
                    onClick={(e) => handleLinkClick(href, e)}
                  >
                    {children}
                  </a>
                ),
              }}
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
