import { ArrowLeft, GraduationCap, Moon, Sun, Trash2 } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import { track } from '../analytics/analytics'
import { CookieSettingsLink } from '../analytics/CookieSettingsLink'
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
import { probeSiteStatus, reloadPage } from '../services/siteStatus'
import { AskBar } from './components/AskBar'
import { HeadsUpStrip } from './components/HeadsUpStrip'
import { HowItWorks } from './components/HowItWorks'
import { NewsletterCard } from './components/NewsletterCard'
import { UpcomingPanel } from './components/UpcomingPanel'
import { YearSelector } from './components/YearSelector'
import * as schoolChat from './schoolChatService'
import type { SchoolConnectionState } from './schoolChatService'
import { useSchoolCalendar } from './useSchoolCalendar'
import { useSchoolNewsletters } from './useSchoolNews'

const YEAR_STORAGE_KEY = 'term-time-year-groups'
const SCHOOL_URL = 'https://www.kilmorieschool.co.uk'

/**
 * How often to ask the proxy whether a deploy is under way, once the socket has given up.
 *
 * Only while offline, and only then. A page that is connected has no question to ask, and a
 * poll running the rest of the time would be a request every few seconds from every open tab
 * for the whole life of the page.
 */
const SITE_STATUS_POLL_MS = 10_000

/**
 * Which layout the page shows. `home` leads with what is going on at school under a one-line
 * question box; `conversation` gives the chat the wide column and folds the cards into a rail.
 */
type PageView = 'home' | 'conversation'

/**
 * Where a question came from, for analytics: typed, a suggestion chip, the calendar, `?q=` or the
 * newsletter card.
 */
type QuestionSource = 'input' | 'chip' | 'calendar' | 'link' | 'newsletter'

/** The question in flight, for the analytics event its answer closes. Never the question's text. */
interface PendingAnswer {
  startedAt: number
  toolCount: number
}

/** The input box's limit, applied to a question handed over in the address too. */
const MAX_QUESTION_LENGTH = 500

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
  "What did last week's newsletter say?",
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

/**
 * The visitor's saved year groups. Tolerates the single-string value the previous version
 * stored, so an existing visitor does not silently lose their selection.
 */
function readStoredYearGroups(): string[] {
  const stored = readStored(YEAR_STORAGE_KEY)
  if (stored) {
    try {
      const parsed: unknown = JSON.parse(stored)
      if (Array.isArray(parsed)) {
        return parsed.filter((y): y is string => typeof y === 'string')
      }
    } catch {
      // Unparseable is the same as unset.
    }
    return []
  }
  const legacy = readStored('term-time-year-group')
  return legacy ? [legacy] : []
}

/**
 * A question handed over in the address as `?q=`, such as the example questions on this
 * project's portfolio page. Removed from the address as soon as it is read, so reloading the page
 * or sharing its URL does not ask it a second time. Capped at the input's own limit.
 */
function takeQuestionFromAddress(): string | null {
  const params = new URLSearchParams(window.location.search)
  const question = params.get('q')?.trim()
  if (!question) return null
  params.delete('q')
  const rest = params.toString()
  window.history.replaceState(
    window.history.state,
    '',
    `${window.location.pathname}${rest ? `?${rest}` : ''}${window.location.hash}`,
  )
  return question.slice(0, MAX_QUESTION_LENGTH)
}

export default function App() {
  const { theme, toggleTheme } = useTheme()
  const [messages, setMessages] = useState<ChatMessageModel[]>([])
  const [awaiting, setAwaiting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [connection, setConnection] = useState<SchoolConnectionState>('connecting')
  // Read during the first render rather than in an effect, so a question handed over in the
  // address is sent with the visitor's year groups and not with an empty selection.
  const [yearGroups, setYearGroups] = useState<string[]>(readStoredYearGroups)
  const [initialQuestion] = useState<string | null>(takeQuestionFromAddress)
  // Opens on the home view, because "is there school on Monday" then needs no typing, unless the
  // address handed over a question, whose answer is what the visitor came for.
  const [view, setView] = useState<PageView>(initialQuestion ? 'conversation' : 'home')
  const calendar = useSchoolCalendar(yearGroups)
  const newsletters = useSchoolNewsletters()
  const initialQuestionSentRef = useRef(false)
  const sessionIdRef = useRef<string>(newSessionId())
  const endRef = useRef<HTMLDivElement>(null)
  // Set when a question is sent and cleared by whichever ends it first (the answer, an error or
  // an undelivered message), so each question reports one Answer Received at most.
  const pendingAnswerRef = useRef<PendingAnswer | null>(null)

  const finishAnswer = useCallback((failed: boolean) => {
    const pending = pendingAnswerRef.current
    if (!pending) return
    pendingAnswerRef.current = null
    track('Answer Received', {
      toolCount: pending.toolCount,
      latencyMs: Math.max(0, Math.round(performance.now() - pending.startedAt)),
      failed,
    })
  }, [])

  const onFrame = useCallback((response: ChatResponse) => {
    if (response.type === 'ERROR') {
      finishAnswer(true)
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
    // Counted from the frames rather than read back from the reduced message: the state updater
    // above may run twice under StrictMode, and a side effect inside it would double-count. The
    // reducer adds one tool block for exactly these frames.
    if (response.type === 'TOOL_START' && response.toolLabel && pendingAnswerRef.current) {
      pendingAnswerRef.current.toolCount += 1
    }
    if (response.type === 'STREAM_END') {
      finishAnswer(false)
      setAwaiting(false)
    }
  }, [finishAnswer])

  /**
   * A message typed while the socket was down and never delivered.
   *
   * The optimistic render has already drawn the question and an empty reply bubble with a
   * typing indicator under it. Leaving those in place is the failure mode this whole change is
   * about: the page looks like it is thinking, forever. The empty bubble goes, the indicator
   * stops, and the reader is told plainly.
   */
  const onUndelivered = useCallback(() => {
    finishAnswer(true)
    setAwaiting(false)
    setMessages((prior) => {
      const last = prior[prior.length - 1]
      const isEmptyPlaceholder =
        last?.role === 'assistant' && !last.finalized && (last.blocks?.length ?? 0) === 0
      return isEmptyPlaceholder ? prior.slice(0, -1) : prior
    })
    setError(
      'That question did not get through — Term Time could not reach the server. ' +
        'Please try again in a moment.',
    )
  }, [finishAnswer])

  // One object, so clearChat reconnects with the same handlers the effect installed rather
  // than assembling its own.
  const handlers = useMemo(
    () => ({
      onMessage: onFrame,
      onStateChange: setConnection,
      onError: setError,
      onUndelivered,
    }),
    [onFrame, onUndelivered],
  )

  // Once per drop into 'offline', the state the transport reaches only after its retries have
  // genuinely failed. A routine 'reconnecting' blip is not reported, as it is not shown.
  useEffect(() => {
    if (connection === 'offline') track('Connection Lost')
  }, [connection])

  useEffect(() => {
    schoolChat.connect(sessionIdRef.current, handlers)
    return () => schoolChat.disconnect()
  }, [handlers])

  /**
   * Once the socket has genuinely given up, find out whether a deploy is the reason.
   *
   * If it is, nginx is already serving the themed "update in progress" page at this exact URL,
   * and that page polls its way back to Term Time when the deploy finishes — so the whole job
   * here is to reload and let it. Anything else (an upstream down, no network at all) leaves
   * the page where it is: the transcript is still readable and the socket is still retrying,
   * and replacing that with an error page would be a downgrade.
   */
  useEffect(() => {
    if (connection !== 'offline') return

    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | null = null
    let cancelled = false

    const check = async () => {
      const status = await probeSiteStatus(controller.signal)
      if (cancelled) return
      if (status === 'maintenance') {
        reloadPage()
        return
      }
      timer = setTimeout(() => void check(), SITE_STATUS_POLL_MS)
    }

    void check()

    return () => {
      cancelled = true
      controller.abort()
      if (timer !== null) clearTimeout(timer)
    }
  }, [connection])

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [messages, awaiting])

  function clearChat() {
    // A new session id, not just an empty list. The backend caps messages per session and the
    // reply topic is named after it, so reusing the old id would carry the old cap across and
    // leave the previous subscription live.
    schoolChat.disconnect()
    sessionIdRef.current = newSessionId()
    // The old session's answer can no longer arrive, so it is not waited on.
    pendingAnswerRef.current = null
    setMessages([])
    setError(null)
    setAwaiting(false)
    setView('home')
    schoolChat.connect(sessionIdRef.current, handlers)
  }

  function chooseYears(next: string[]) {
    setYearGroups(next)
    track('Year Group Selected', { yearGroupCount: next.length })
    writeStored(YEAR_STORAGE_KEY, next.length === 0 ? null : JSON.stringify(next))
  }

  const send = useCallback(
    async (text: string, source: QuestionSource) => {
      const trimmed = text.trim()
      if (!trimmed || awaiting) {
        return
      }
      track('Question Asked', { source, yearGroupCount: yearGroups.length })
      pendingAnswerRef.current = { startedAt: performance.now(), toolCount: 0 }
      setError(null)
      setAwaiting(true)
      setView('conversation')

      const now = formatTimestamp()
      setMessages((prior) => [
        ...prior,
        { role: 'user', content: trimmed, blocks: [], timestamp: now, finalized: true },
        createEmptyAssistantMessage(now),
      ])

      // No token, ever. Term Time is entirely public; the backend resolves a null token to the
      // anonymous audience, which can only reach content that has been approved for it.
      //
      // The return value is deliberately ignored: `false` means the transport is holding the
      // message until the reconnect already in flight completes, which is indistinguishable
      // from a slow answer and needs no separate UI. If that reconnect never comes, the
      // transport calls onUndelivered and the placeholder above is cleaned up there.
      schoolChat.sendMessage({
        sessionId: sessionIdRef.current,
        message: trimmed,
        yearGroups,
        accessToken: null,
      })
    },
    [awaiting, yearGroups],
  )

  const askFrom = useCallback(
    (source: QuestionSource) => (question: string) =>
      void send(question.slice(0, MAX_QUESTION_LENGTH), source),
    [send],
  )
  const askAboutEvent = useMemo(() => askFrom('calendar'), [askFrom])
  const askAboutNewsletter = useMemo(() => askFrom('newsletter'), [askFrom])

  // Sent without waiting for the socket: the transport holds one message until the connection
  // it is already opening is up. Deferred a tick and cancelled on cleanup because a development
  // build mounts twice, and the first unmount's disconnect() throws away a held message.
  useEffect(() => {
    if (!initialQuestion || initialQuestionSentRef.current) return
    const timer = setTimeout(() => {
      initialQuestionSentRef.current = true
      void send(initialQuestion, 'link')
    }, 0)
    return () => clearTimeout(timer)
  }, [initialQuestion, send])

  /*
    Nothing to press, and nothing to see for the first several seconds.

    'connecting' is where every visit starts, so a notice there would fire on every single page
    load. 'reconnecting' is a socket that dropped and is already coming back — typically inside
    a second — and saying so would be noise about something the reader cannot act on and will
    not outlast. Only 'offline', which the transport reaches after the retries have genuinely
    not worked, says anything, and what it says is that it is still trying. The retry is
    automatic, and a question typed in the meantime is held and sent the moment the socket
    returns.

    role="status" rather than role="alert": this is polite, and a screen reader interrupting an
    answer being read out to announce a reconnection is worse than the reconnection.
  */
  const connectionNotice = connection === 'offline' && (
    <p className="school-page__notice" role="status">
      Reconnecting to Term Time…
    </p>
  )
  const errorNotice = error && <p className="school-page__error">{error}</p>

  return (
    <div className="school-page">
      <header className="school-top">
        <div className="school-top__brand">
          <span className="school-top__mark" aria-hidden="true">
            <GraduationCap size={20} />
          </span>
          <div>
            <h1 className="school-top__name">Term Time</h1>
            <p className="school-top__school">Kilmorie Primary School</p>
          </div>
        </div>
        <YearSelector yearGroups={YEAR_GROUPS} selected={yearGroups} onChange={chooseYears} />
        <button
          type="button"
          className="nav__theme-toggle school-top__theme"
          aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
          onClick={toggleTheme}
        >
          {theme === 'dark' ? <Sun size={20} /> : <Moon size={20} />}
        </button>
      </header>

      {view === 'home' ? (
        <main className="school-home">
          <AskBar
            onSend={(text) => void send(text, 'input')}
            onSuggestion={(text) => void send(text, 'chip')}
            suggestions={SUGGESTIONS}
            disabled={awaiting}
            maxLength={MAX_QUESTION_LENGTH}
            conversationLength={messages.length}
            onResume={() => setView('conversation')}
          />
          {connectionNotice}
          {errorNotice}
          <HeadsUpStrip calendar={calendar} onAsk={askAboutEvent} />
          <div className="school-home__cards">
            <UpcomingPanel calendar={calendar} onAsk={askAboutEvent} />
            <NewsletterCard state={newsletters} onAsk={askAboutNewsletter} />
          </div>
        </main>
      ) : (
        <main className="school-convo">
          <section className="school-page__chat" aria-label="Conversation">
            <div className="school-convo__head">
              <button
                type="button"
                className="school-convo__back"
                onClick={() => setView('home')}
              >
                <ArrowLeft size={15} aria-hidden="true" />
                Back to this week
              </button>
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
            </div>
            <div className="chat-panel__messages">
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

              {connectionNotice}
              {errorNotice}
              <div ref={endRef} />
            </div>

            <ChatInput
              onSend={(text) => void send(text, 'input')}
              disabled={awaiting}
              maxLength={MAX_QUESTION_LENGTH}
            />
          </section>

          <aside className="school-convo__rail" aria-label="What is on at school">
            <HeadsUpStrip calendar={calendar} onAsk={askAboutEvent} compact />
            <UpcomingPanel calendar={calendar} onAsk={askAboutEvent} compact />
            <NewsletterCard state={newsletters} onAsk={askAboutNewsletter} compact />
          </aside>
        </main>
      )}

      <footer className="school-page__footer">
        <HowItWorks />
        <p className="school-page__disclaimer">
          Term Time is not affiliated with or endorsed by Kilmorie Primary School. It can be
          wrong or out of date — always check{' '}
          <a href={SCHOOL_URL} target="_blank" rel="noopener noreferrer">
            the school&rsquo;s own website
          </a>{' '}
          for anything that matters.
        </p>
        <CookieSettingsLink />
      </footer>
    </div>
  )
}
