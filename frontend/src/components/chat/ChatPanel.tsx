import { useEffect, useRef, useState } from 'react'
import { X } from 'lucide-react'
import * as chatService from '../../services/chatService'
import type { ChatResponse } from '../../services/chatService'
import { ChatMessage } from './ChatMessage'
import { ChatInput } from './ChatInput'
import { ChatTypingIndicator } from './ChatTypingIndicator'

interface ChatPanelProps {
  initialQuery: string
  onClose: () => void
}

interface Message {
  role: 'user' | 'assistant'
  content: string
  timestamp: string
}

function formatTimestamp(): string {
  return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

export function ChatPanel({ initialQuery, onClose }: ChatPanelProps) {
  const [messages, setMessages] = useState<Message[]>([])
  const [connected, setConnected] = useState(false)
  const [streamingContent, setStreamingContent] = useState<string | null>(null)
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const sessionIdRef = useRef<string>(crypto.randomUUID())
  const initialSentRef = useRef(false)

  useEffect(() => {
    const sessionId = sessionIdRef.current

    setMessages([
      {
        role: 'user',
        content: initialQuery,
        timestamp: formatTimestamp(),
      },
    ])

    const onMessage = (response: ChatResponse) => {
      if (response.type === 'STREAM_START') {
        setStreamingContent('')
      } else if (response.type === 'STREAM_CHUNK') {
        setStreamingContent((prev) => (prev ?? '') + response.content)
      } else if (response.type === 'STREAM_END') {
        setStreamingContent((prev) => {
          const finalContent = prev ?? ''
          setMessages((msgs) => [
            ...msgs,
            {
              role: 'assistant',
              content: finalContent,
              timestamp: formatTimestamp(),
            },
          ])
          return null
        })
      } else if (response.type === 'ERROR') {
        setStreamingContent(null)
        setMessages((msgs) => [
          ...msgs,
          {
            role: 'assistant',
            content: response.content || 'An error occurred. Please try again.',
            timestamp: formatTimestamp(),
          },
        ])
      }
    }

    chatService.connect(
      sessionId,
      onMessage,
      () => {
        setConnected(true)
        if (!initialSentRef.current) {
          initialSentRef.current = true
          chatService.sendMessage({ sessionId, message: initialQuery })
        }
      },
      () => {
        setConnected(false)
      }
    )

    return () => {
      chatService.disconnect()
    }
  }, [initialQuery])

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, streamingContent])

  const handleSend = (text: string) => {
    const userMessage: Message = {
      role: 'user',
      content: text,
      timestamp: formatTimestamp(),
    }
    setMessages((msgs) => [...msgs, userMessage])
    chatService.sendMessage({ sessionId: sessionIdRef.current, message: text })
  }

  const isStreaming = streamingContent !== null

  return (
    <div className="chat-panel">
      <div className="chat-panel__header">
        <h3>Ask me anything</h3>
        <button className="chat-panel__close" onClick={onClose} aria-label="Close chat">
          <X size={18} />
        </button>
      </div>

      <div className="chat-panel__messages">
        {messages.map((msg, idx) => (
          <ChatMessage
            key={idx}
            role={msg.role}
            content={msg.content}
            timestamp={msg.timestamp}
          />
        ))}
        {isStreaming && streamingContent === '' && (
          <div className="chat-message chat-message--assistant">
            <ChatTypingIndicator />
          </div>
        )}
        {isStreaming && streamingContent !== '' && (
          <ChatMessage
            role="assistant"
            content={streamingContent ?? ''}
          />
        )}
        <div ref={messagesEndRef} />
      </div>

      <div className="chat-panel__input">
        <ChatInput
          onSend={handleSend}
          disabled={!connected || isStreaming}
        />
      </div>
    </div>
  )
}
