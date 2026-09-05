import { useCallback, useEffect, useRef, useState } from 'react'
import { MessageCircle, RotateCcw, X } from 'lucide-react'
import * as chatService from '../../services/chatService'
import type { ChatResponse } from '../../services/chatService'
import { ChatMessage } from './ChatMessage'
import { ChatInput } from './ChatInput'
import { ChatTypingIndicator } from './ChatTypingIndicator'
import {
  applyChatStreamEvent,
  createEmptyAssistantMessage,
  finalizeAssistantMessage,
} from './chatStreamReducer'
import type { ChatMessageModel } from './chatTypes'

const MAX_USER_MESSAGES = 10
const STREAM_TIMEOUT_MS = 30000

const SUGGESTED_PROMPTS = [
  'What is your experience with Kafka?',
  'What have you been blogging about recently?',
  'Show me some code examples',
  'What was your role at Global?',
  'What testing strategies do you use?',
]

interface ChatPanelProps {
  initialQuery?: string
  onClose: () => void
  onInitialResponse?: () => void
  profileImageUrl?: string
  visible?: boolean
}

function formatTimestamp(): string {
  return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

export function ChatPanel({
  initialQuery,
  onClose,
  onInitialResponse,
  profileImageUrl,
  visible = true,
}: ChatPanelProps) {
  const [messages, setMessages] = useState<ChatMessageModel[]>([])
  const [connected, setConnected] = useState(false)
  const [activeAssistant, setActiveAssistantState] = useState<ChatMessageModel | null>(null)
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const sessionIdRef = useRef<string>(crypto.randomUUID())
  const streamFinalized = useRef(false)
  const cancelledRef = useRef(false)
  const streamTimeoutRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const initialQueryTimeoutRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const initialQuerySentRef = useRef(false)
  const activeAssistantRef = useRef<ChatMessageModel | null>(null)
  const initialResponseReportedRef = useRef(false)

  const userMessageCount = messages.filter((m) => m.role === 'user').length
  const limitReached = userMessageCount >= MAX_USER_MESSAGES

  const setActiveAssistant = useCallback((message: ChatMessageModel | null) => {
    activeAssistantRef.current = message
    setActiveAssistantState(message)
  }, [])

  const finalizeStream = useCallback(() => {
    if (streamFinalized.current) return
    streamFinalized.current = true
    clearTimeout(streamTimeoutRef.current)
    const finalMessage = activeAssistantRef.current
    setActiveAssistant(null)
    if (finalMessage && (finalMessage.blocks?.length ?? 0) > 0) {
      setMessages((msgs) => [...msgs, finalizeAssistantMessage(finalMessage)])
    }
  }, [setActiveAssistant])

  const resetStreamTimeout = useCallback(() => {
    clearTimeout(streamTimeoutRef.current)
    streamTimeoutRef.current = setTimeout(() => {
      if (!streamFinalized.current && !cancelledRef.current) {
        finalizeStream()
      }
    }, STREAM_TIMEOUT_MS)
  }, [finalizeStream])

  const onMessage = useCallback((response: ChatResponse) => {
    if (cancelledRef.current) return
    if (response.sessionId && response.sessionId !== sessionIdRef.current) return
    if (response.type === 'STREAM_START') {
      streamFinalized.current = false
      setActiveAssistant(createEmptyAssistantMessage(formatTimestamp()))
      resetStreamTimeout()
    } else if (response.type === 'STREAM_END') {
      clearTimeout(streamTimeoutRef.current)
      if (streamFinalized.current) return
      streamFinalized.current = true
      const current = activeAssistantRef.current ?? createEmptyAssistantMessage(formatTimestamp())
      const finalMessage = applyChatStreamEvent(current, response)
      setActiveAssistant(null)
      if ((finalMessage.blocks?.length ?? 0) > 0) {
        setMessages((msgs) => [...msgs, finalMessage])
      }
    } else if (response.type === 'ERROR') {
      clearTimeout(streamTimeoutRef.current)
      if (streamFinalized.current) return
      streamFinalized.current = true
      const current = activeAssistantRef.current ?? createEmptyAssistantMessage(formatTimestamp())
      const finalMessage = applyChatStreamEvent(current, response)
      setActiveAssistant(null)
      setMessages((msgs) => [...msgs, finalMessage])
    } else {
      // Ignore any late chunk/tool/widget frame that arrives after the answer was
      // finalized (e.g. a duplicate delivery), so it cannot rebuild a garbled bubble.
      if (streamFinalized.current) return
      const current = activeAssistantRef.current ?? createEmptyAssistantMessage(formatTimestamp())
      const next = applyChatStreamEvent(current, response)
      setActiveAssistant(next)
      resetStreamTimeout()
    }
  }, [resetStreamTimeout, setActiveAssistant])

  useEffect(() => {
    const sessionId = crypto.randomUUID()
    sessionIdRef.current = sessionId
    streamFinalized.current = false
    cancelledRef.current = false
    // A fresh session for this initialQuery; allow exactly one initial-query send.
    initialQuerySentRef.current = false
    initialResponseReportedRef.current = false

    if (initialQuery) {
      setMessages([
        {
          role: 'user',
          content: initialQuery,
          timestamp: formatTimestamp(),
        },
      ])
    } else {
      setMessages([])
    }
    setActiveAssistant(null)

    chatService.connect(
      sessionId,
      onMessage,
      () => {
        if (cancelledRef.current) return
        setConnected(true)
        // Send the initial query exactly once, even if onConnect fires again on a
        // STOMP reconnect (reconnectDelay), which would otherwise duplicate the prompt.
        if (initialQuery && !initialQuerySentRef.current) {
          initialQuerySentRef.current = true
          initialQueryTimeoutRef.current = setTimeout(() => {
            if (!cancelledRef.current) {
              chatService.sendMessage({ sessionId, message: initialQuery })
            }
          }, 50)
        }
      },
      () => {
        if (cancelledRef.current) return
        setConnected(false)
      }
    )

    return () => {
      cancelledRef.current = true
      clearTimeout(initialQueryTimeoutRef.current)
      clearTimeout(streamTimeoutRef.current)
      chatService.disconnect()
    }
  }, [initialQuery, onMessage, setActiveAssistant])

  useEffect(() => {
    if (!onInitialResponse || initialResponseReportedRef.current) return

    const responseIsVisible = [activeAssistant, ...messages].some((message) =>
      message?.role === 'assistant' && (message.blocks?.length ?? 0) > 0)
    if (responseIsVisible) {
      initialResponseReportedRef.current = true
      onInitialResponse()
    }
  }, [activeAssistant, messages, onInitialResponse])

  useEffect(() => {
    if (visible && messagesEndRef.current) {
      const container = messagesEndRef.current.parentElement
      if (container) {
        container.scrollTop = container.scrollHeight
      }
    }
  }, [messages, activeAssistant, visible])

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
    const userMessage: ChatMessageModel = {
      role: 'user',
      content: text,
      timestamp: formatTimestamp(),
    }
    streamFinalized.current = false
    setMessages((msgs) => [...msgs, userMessage])
    setActiveAssistant(createEmptyAssistantMessage(formatTimestamp()))
    chatService.sendMessage({ sessionId: sessionIdRef.current, message: text })
  }

  const handleClearChat = () => {
    chatService.disconnect()
    setMessages([])
    setActiveAssistant(null)
    setConnected(false)
    streamFinalized.current = false
    cancelledRef.current = false
    clearTimeout(initialQueryTimeoutRef.current)
    clearTimeout(streamTimeoutRef.current)

    const newSessionId = crypto.randomUUID()
    sessionIdRef.current = newSessionId

    chatService.connect(
      newSessionId,
      onMessage,
      () => {
        if (!cancelledRef.current) {
          setConnected(true)
        }
      },
      () => {
        if (!cancelledRef.current) {
          setConnected(false)
        }
      }
    )
  }

  const isStreaming = activeAssistant !== null

  if (!visible) return null

  return (
    <div className="chat-drawer-backdrop" onClick={onClose}>
      <div className="chat-panel" onClick={(e) => e.stopPropagation()} data-testid="chat-panel">
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
          {messages.length === 0 && !isStreaming && (
            <div className="chat-panel__welcome">
              <MessageCircle size={32} className="chat-panel__welcome-icon" />
              <p className="chat-panel__welcome-title">Hi, I'm Simon's AI assistant</p>
              <p className="chat-panel__welcome-text">
                Ask me anything about Simon's experience, skills, and career history.
              </p>
              <div className="chat-panel__welcome-prompts">
                {SUGGESTED_PROMPTS.map(prompt => (
                  <button
                    key={prompt}
                    className="chat-panel__welcome-chip"
                    onClick={() => handleSend(prompt)}
                    type="button"
                    disabled={!connected}
                  >
                    {prompt}
                  </button>
                ))}
              </div>
            </div>
          )}
          {messages.map((msg, idx) => (
            <ChatMessage
              key={idx}
              role={msg.role}
              content={msg.content}
              blocks={msg.blocks}
              timestamp={msg.timestamp}
              profileImageUrl={profileImageUrl}
            />
          ))}
          {isStreaming && (activeAssistant.blocks?.length ?? 0) > 0 && (
            <ChatMessage
              role="assistant"
              blocks={activeAssistant.blocks}
              timestamp={activeAssistant.timestamp}
              profileImageUrl={profileImageUrl}
            />
          )}
          {/* Show the typing indicator whenever the assistant is streaming but has no
              in-progress text yet — before the first block AND in the gap after a tool/widget
              block finishes while the model composes its answer. */}
          {isStreaming
            && activeAssistant.blocks?.[activeAssistant.blocks.length - 1]?.kind !== 'text' && (
            <div className="chat-message chat-message--assistant">
              <ChatTypingIndicator />
            </div>
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
