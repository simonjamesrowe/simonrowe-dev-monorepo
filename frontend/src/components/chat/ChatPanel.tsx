import { useCallback, useEffect, useRef, useState } from 'react'
import { RotateCcw, X } from 'lucide-react'
import * as chatService from '../../services/chatService'
import type { ChatResponse } from '../../services/chatService'
import { ChatMessage } from './ChatMessage'
import { ChatInput } from './ChatInput'
import { ChatTypingIndicator } from './ChatTypingIndicator'

const MAX_USER_MESSAGES = 10

interface ChatPanelProps {
  initialQuery: string
  onClose: () => void
  profileImageUrl?: string
  visible?: boolean
}

interface Message {
  role: 'user' | 'assistant'
  content: string
  timestamp: string
}

function formatTimestamp(): string {
  return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

export function ChatPanel({ initialQuery, onClose, profileImageUrl, visible = true }: ChatPanelProps) {
  const [messages, setMessages] = useState<Message[]>([])
  const [connected, setConnected] = useState(false)
  const [streamingContent, setStreamingContent] = useState<string | null>(null)
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const sessionIdRef = useRef<string>(crypto.randomUUID())
  const streamFinalized = useRef(false)
  const streamContentRef = useRef('')
  const cancelledRef = useRef(false)

  const userMessageCount = messages.filter((m) => m.role === 'user').length
  const limitReached = userMessageCount >= MAX_USER_MESSAGES

  const onMessage = useCallback((response: ChatResponse) => {
    if (cancelledRef.current) return
    if (response.type === 'STREAM_START') {
      streamContentRef.current = ''
      streamFinalized.current = false
      setStreamingContent('')
    } else if (response.type === 'STREAM_CHUNK') {
      streamContentRef.current += response.content
      setStreamingContent(streamContentRef.current)
    } else if (response.type === 'STREAM_RESET') {
      streamContentRef.current = ''
      setStreamingContent('')
    } else if (response.type === 'STREAM_END') {
      if (streamFinalized.current) return
      streamFinalized.current = true
      const finalContent = streamContentRef.current || response.content || ''
      setStreamingContent(null)
      setMessages((msgs) => [
        ...msgs,
        {
          role: 'assistant',
          content: finalContent,
          timestamp: formatTimestamp(),
        },
      ])
    } else if (response.type === 'ERROR') {
      if (streamFinalized.current) return
      streamFinalized.current = true
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
  }, [])

  useEffect(() => {
    const sessionId = crypto.randomUUID()
    sessionIdRef.current = sessionId
    streamFinalized.current = false
    cancelledRef.current = false

    setMessages([
      {
        role: 'user',
        content: initialQuery,
        timestamp: formatTimestamp(),
      },
    ])
    setStreamingContent(null)

    let sendTimeout: ReturnType<typeof setTimeout>

    chatService.connect(
      sessionId,
      onMessage,
      () => {
        if (cancelledRef.current) return
        setConnected(true)
        sendTimeout = setTimeout(() => {
          if (!cancelledRef.current) {
            chatService.sendMessage({ sessionId, message: initialQuery })
          }
        }, 50)
      },
      () => {
        if (cancelledRef.current) return
        setConnected(false)
      }
    )

    return () => {
      cancelledRef.current = true
      clearTimeout(sendTimeout)
      chatService.disconnect()
    }
  }, [initialQuery, onMessage])

  useEffect(() => {
    if (visible) {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
  }, [messages, streamingContent, visible])

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && visible) {
        onClose()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onClose, visible])

  const handleSend = (text: string) => {
    if (limitReached) return
    const userMessage: Message = {
      role: 'user',
      content: text,
      timestamp: formatTimestamp(),
    }
    streamFinalized.current = false
    setMessages((msgs) => [...msgs, userMessage])
    setStreamingContent('')
    chatService.sendMessage({ sessionId: sessionIdRef.current, message: text })
  }

  const handleClearChat = () => {
    chatService.disconnect()
    setMessages([])
    setStreamingContent(null)
    setConnected(false)
    streamFinalized.current = false
    cancelledRef.current = false

    const newSessionId = crypto.randomUUID()
    sessionIdRef.current = newSessionId

    chatService.connect(
      newSessionId,
      onMessage,
      () => setConnected(true),
      () => setConnected(false)
    )
  }

  const isStreaming = streamingContent !== null

  if (!visible) return null

  return (
    <div className="chat-drawer-backdrop" onClick={onClose}>
      <div className="chat-panel" onClick={(e) => e.stopPropagation()}>
        <div className="chat-panel__header">
          <h3>Ask me anything</h3>
          <div className="chat-panel__header-actions">
            <button
              className="chat-panel__clear"
              onClick={handleClearChat}
              aria-label="Clear chat"
              title="Clear chat"
            >
              <RotateCcw size={16} />
            </button>
            <button className="chat-panel__close" onClick={onClose} aria-label="Close chat">
              <X size={18} />
            </button>
          </div>
        </div>

        <div className="chat-panel__messages">
          {messages.map((msg, idx) => (
            <ChatMessage
              key={idx}
              role={msg.role}
              content={msg.content}
              timestamp={msg.timestamp}
              profileImageUrl={profileImageUrl}
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
              profileImageUrl={profileImageUrl}
            />
          )}
          {limitReached && !isStreaming && (
            <div className="chat-panel__limit-notice">
              You've reached the message limit for this session. Clear the chat to start a new conversation.
            </div>
          )}
          <div ref={messagesEndRef} />
        </div>

        <div className="chat-panel__input">
          <ChatInput
            onSend={handleSend}
            disabled={!connected || isStreaming || limitReached}
          />
        </div>
      </div>
    </div>
  )
}
