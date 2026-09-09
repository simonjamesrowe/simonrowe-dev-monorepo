import { GraduationCap, Moon, Sun, Trash2 } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'

import { useTheme } from '../contexts/ThemeContext'

import { ChatInput } from '../components/chat/ChatInput'
import { ChatMessage } from '../components/chat/ChatMessage'
import { ChatTypingIndicator } from '../components/chat/ChatTypingIndicator'
import {
  applyChatStreamEvent,
  createEmptyAssistantMessage,
} from '../components/chat/chatStreamReducer'
import type { ChatMessageModel } from '../components/chat/chatTypes'
import type { ChatResponse } from '../services/chatService'
import { HowItWorks } from './components/HowItWorks'
import { YearSelector } from './components/YearSelector'
import * as schoolChat from './schoolChatService'

const YEAR_STORAGE_KEY = 'term-time-year-groups'
const SCHOOL_URL = 'https://www.kilmorieschool.co.uk'

const YEAR_GROUPS = [
  'Reception',
  'Year 1',
  'Year 2',
  'Year 3',
  'Year 4',
  'Year 5',
  'Year 6',
]

const SUGGESTIONS = [
  'When is half term?',
  'When are the INSET days?',
  "What's on this week?",
  'When do enrichment clubs start?',
]

/**
 * Matches ChatPanel's formatting exactly. ChatMessage renders whatever string it is given
 * verbatim, so passing an ISO instant here puts `2026-09-08T14:22:55.447Z` under every bubble.
 */
function formatTimestamp(): string {
  return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

/**
 * localStorage access that cannot throw.
 *
 * Private browsing, a blocked-cookies setting or a constrained embed can all make storage
 * unavailable or partially stubbed, and an unguarded call there takes the whole page down for a
 * preference nobody would miss. ThemeContext guards it the same way.
 */
function readStored(key: string): string | null {
  try {
    return window.localStorage?.getItem?.(key) ?? null
  } catch {
    return null
  }
}

function writeStored(key: string, value: string | null): void {
  try {
    if (value === null) {
      window.localStorage?.removeItem?.(key)
    } else {
      window.localStorage?.setItem?.(key, value)
    }
  } catch {
    // Preference not saved; nothing else is affected.
  }
}

function newSessionId(): string {
  // Cryptographically random, with no Math.random() fallback anywhere in the function. This
  // value is the STOMP topic suffix the answer streams back on (/topic/school.<id>), so a
  // guessable one lets somebody subscribe to another visitor's conversation.
  //
  // getRandomValues rather than randomUUID: randomUUID requires a secure context and is the
  // newer API, so it needs a fallback — and a fallback is exactly where the weak generator
  // crept back in. getRandomValues has neither constraint and is available in every browser
  // and in jsdom, so there is one path and it is the strong one.
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `school-${hex}`
}

export default function App() {
  const { theme, toggleTheme } = useTheme()
  const [messages, setMessages] = useState<ChatMessageModel[]>([])
  const [awaiting, setAwaiting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [yearGroups, setYearGroups] = useState<string[]>([])
  const sessionIdRef = useRef<string>(newSessionId())
  const endRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    // Tolerates the single-string value the previous version stored, so an existing visitor
    // does not silently lose their selection on the first load after this ships.
    const stored = readStored(YEAR_STORAGE_KEY)
    const legacy = readStored('term-time-year-group')
    if (stored) {
      try {
        const parsed: unknown = JSON.parse(stored)
        if (Array.isArray(parsed)) {
          setYearGroups(parsed.filter((y): y is string => typeof y === 'string'))
        }
      } catch {
        // Unparseable is the same as unset.
      }
    } else if (legacy) {
      setYearGroups([legacy])
    }
  }, [])

  const onFrame = useCallback((response: ChatResponse) => {
    if (response.type === 'ERROR') {
      setAwaiting(false)
      setError(response.content)
      return
    }
    setMessages((prior) => {
      const next = [...prior]
      const last = next[next.length - 1]
      if (!last || last.role !== 'assistant' || last.finalized) {
        return next
      }
      next[next.length - 1] = applyChatStreamEvent(last, response)
      return next
    })
    if (response.type === 'STREAM_END') {
      setAwaiting(false)
    }
  }, [])

  useEffect(() => {
    const sessionId = sessionIdRef.current
    schoolChat.connect(
      sessionId,
      onFrame,
      undefined,
      (message) => setError(message),
    )
    return () => schoolChat.disconnect()
  }, [onFrame])

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [messages, awaiting])

  function clearChat() {
    // A new session id, not just an empty list. The backend caps messages per session and the
    // reply topic is named after it, so reusing the old id would carry the old cap across and
    // leave the previous subscription live.
    schoolChat.disconnect()
    sessionIdRef.current = newSessionId()
    setMessages([])
    setError(null)
    setAwaiting(false)
    schoolChat.connect(sessionIdRef.current, onFrame, undefined, (m) => setError(m))
  }

  function chooseYears(next: string[]) {
    setYearGroups(next)
    writeStored(YEAR_STORAGE_KEY, next.length === 0 ? null : JSON.stringify(next))
  }

  const send = useCallback(
    async (text: string) => {
      const trimmed = text.trim()
      if (!trimmed || awaiting) {
        return
      }
      setError(null)
      setAwaiting(true)

      const now = formatTimestamp()
      setMessages((prior) => [
        ...prior,
        { role: 'user', content: trimmed, blocks: [], timestamp: now, finalized: true },
        createEmptyAssistantMessage(now),
      ])

      // No token, ever. Term Time is entirely public; the backend resolves a null token to the
      // anonymous audience, which can only reach content that has been approved for it.
      schoolChat.sendMessage({
        sessionId: sessionIdRef.current,
        message: trimmed,
        yearGroups,
        accessToken: null,
      })
    },
    [awaiting, yearGroups],
  )

  return (
    <div className="school-page">
      <header className="school-page__header">
        <div className="school-page__title">
          <GraduationCap size={24} aria-hidden="true" />
          <h1>Term Time</h1>
        </div>
        <div className="school-page__actions">
          {messages.length > 0 && (
            <button
              type="button"
              className="school-page__clear"
              onClick={clearChat}
              aria-label="Clear this conversation"
            >
              <Trash2 size={15} aria-hidden="true" />
              Clear chat
            </button>
          )}
          <button
            type="button"
            className="nav__theme-toggle"
            aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
            onClick={toggleTheme}
          >
            {theme === 'dark' ? <Sun size={20} /> : <Moon size={20} />}
          </button>
        </div>
      </header>

      <p className="school-page__strapline">
        <span className="school-page__beta">Beta</span>
        Questions about Kilmorie Primary School — term dates, events, clubs and arrangements.
      </p>

      {/*
        Stated at the top rather than buried in the footer disclaimer. Someone deciding whether
        to act on an answer about their child's school should see this before they read it, not
        after.
      */}
      <p className="school-page__beta-note">
        This is an early version, built by a parent and not by the school. It can be wrong or
        out of date — please check anything important against the school&rsquo;s own
        communications.
      </p>

      <YearSelector yearGroups={YEAR_GROUPS} selected={yearGroups} onChange={chooseYears} />

      <HowItWorks />

      <div className="school-page__chat">
        <div className="chat-panel__messages">
          {messages.length === 0 && (
            <div className="chat-panel__welcome">
              <GraduationCap size={32} className="chat-panel__welcome-icon" />
              <p className="chat-panel__welcome-title">Ask about school</p>
              <p className="chat-panel__welcome-text">
                Term dates, INSET days, what&rsquo;s on this week, clubs and arrangements.
              </p>
              <div className="chat-panel__welcome-prompts">
                {SUGGESTIONS.map((suggestion) => (
                  <button
                    key={suggestion}
                    className="chat-panel__welcome-chip"
                    onClick={() => void send(suggestion)}
                  >
                    {suggestion}
                  </button>
                ))}
              </div>
            </div>
          )}

          {messages.map((message, index) => (
            <ChatMessage
              key={`${message.timestamp}-${index}`}
              role={message.role}
              content={message.content}
              blocks={message.blocks}
              timestamp={message.timestamp}
              // Term Time emits no widgets, so without this its own refusal advice — "check
              // the school's website" — would render as unclickable plain text.
              extraAllowedUrls={[SCHOOL_URL, `${SCHOOL_URL}/`]}
              // Deep links the model got from a tool result — a calendar event, a PDF — are
              // never seen by the browser, so exact-URL matching would strip every one. The
              // prefix is our own constant, not model output.
              allowedLinkPrefixes={[SCHOOL_URL]}
            />
          ))}

          {awaiting && messages[messages.length - 1]?.blocks?.length === 0 && (
            <div className="chat-message chat-message--assistant">
              <ChatTypingIndicator />
            </div>
          )}

          {error && <p className="school-page__error">{error}</p>}
          <div ref={endRef} />
        </div>

        <ChatInput onSend={(text) => void send(text)} disabled={awaiting} maxLength={500} />
      </div>

      <footer className="school-page__footer">
        <p className="school-page__disclaimer">
          Term Time is not affiliated with or endorsed by Kilmorie Primary School. It can be
          wrong or out of date — always check{' '}
          <a href={SCHOOL_URL} target="_blank" rel="noopener noreferrer">
            the school&rsquo;s own website
          </a>{' '}
          for anything that matters.
        </p>
      </footer>
    </div>
  )
}
