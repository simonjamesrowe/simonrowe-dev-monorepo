import {
  Client,
  ReconnectionTimeMode,
  type IMessage,
  type StompSubscription,
} from '@stomp/stompjs'

import { API_BASE_URL } from '../config/api'
import type { ChatResponse } from '../services/chatService'

/**
 * STOMP transport for Term Time.
 *
 * A near-copy of services/chatService.ts rather than a shared abstraction, and deliberately so:
 * the two differ in destination, in payload (this one carries a year group and a bearer token)
 * and in lifecycle, and the shared version would be a parameterised wrapper whose only caller
 * variation is those three things. Copying ~60 lines is cheaper than the indirection, and the
 * ChatResponse type IS shared, which is the part that actually has to stay in step.
 *
 * Where it has since diverged, and why: Term Time is a whole page that sits open while somebody
 * reads it, so its socket spends most of its life idle. The portfolio chat lives in a drawer
 * that is opened, used and closed. An idle socket is the case this file has to survive.
 */

export interface SchoolChatRequest {
  sessionId: string
  message: string
  yearGroups: string[]
  accessToken: string | null
}

/**
 * What the page is allowed to know about the socket.
 *
 * `reconnecting` and `offline` are separate states carrying separate UI, and collapsing them is
 * the bug this file exists to stop. A WebSocket that drops and comes back inside a second or two
 * is the normal behaviour of a connection crossing Cloudflare and a tunnel; announcing it as a
 * failure trains the reader to ignore the one time it is real.
 */
export type SchoolConnectionState = 'connecting' | 'connected' | 'reconnecting' | 'offline'

export interface SchoolChatHandlers {
  onMessage: (response: ChatResponse) => void
  onStateChange?: (state: SchoolConnectionState) => void
  /** A STOMP-level error from the server. Transport trouble arrives via onStateChange instead. */
  onError?: (message: string) => void
  /** A queued message that could not be delivered before {@link PENDING_SEND_TIMEOUT_MS}. */
  onUndelivered?: (request: SchoolChatRequest) => void
}

/**
 * Both directions, in milliseconds, and they must match the backend's — see WebSocketConfig.
 * STOMP negotiates the pair down to the weaker of the two ends, so a zero on either side
 * disables the direction entirely.
 */
const HEARTBEAT_MS = 10_000

/** Give up on a connect attempt that has neither succeeded nor failed. */
const CONNECTION_TIMEOUT_MS = 10_000

/**
 * First retry, then doubling to {@link MAX_RECONNECT_DELAY_MS}. Starting at one second is what
 * makes an idle-timeout drop invisible: the socket is back before the reader has finished the
 * sentence they were on. The doubling is what stops a page left open through a long outage from
 * reconnecting once a second for an hour. The ceiling is low on purpose: nobody presses
 * anything to recover here, so the ceiling IS how long a reader waits after the service comes
 * back.
 */
const RECONNECT_DELAY_MS = 1_000
const MAX_RECONNECT_DELAY_MS = 15_000

/**
 * Consecutive failed attempts before the page is told the service is genuinely unreachable.
 * With the delays above that is roughly seven seconds of trying, which comfortably outlasts a
 * dropped socket, a tunnel reconnect and a backend restart, and does not outlast a deploy.
 */
const OFFLINE_AFTER_FAILURES = 3

/**
 * How long a message sent while the socket is down is held before the page is told it did not
 * go. Long enough to cover a reconnect, short enough that nobody sits watching a typing
 * indicator wondering. There is exactly one slot: a second question supersedes the first rather
 * than queueing behind it, because two answers arriving at once is worse than one lost draft.
 */
const PENDING_SEND_TIMEOUT_MS = 20_000

function buildWsUrl(): string {
  if (API_BASE_URL) {
    return API_BASE_URL.replace(/^http:/, 'ws:').replace(/^https:/, 'wss:') + '/ws/chat'
  }
  const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${wsProtocol}//${window.location.host}/ws/chat`
}

let client: Client | null = null
/**
 * Bumped by every connect and every disconnect, and captured by each client's callbacks.
 *
 * Staleness cannot be judged on the session id. A reconnect keeps the SAME session —
 * deliberately, so the transcript on screen still matches the one the backend holds — and
 * deactivate() is asynchronous, so the outgoing client's close event arrives after the
 * replacement is already live. Compared on session id those two are identical, and the stale
 * close would be scored as a failure of a connection that is fine.
 */
let epoch = 0
let subscription: StompSubscription | null = null
let handlers: SchoolChatHandlers | null = null
let state: SchoolConnectionState = 'connecting'
let consecutiveFailures = 0
let pending: SchoolChatRequest | null = null
let pendingTimer: ReturnType<typeof setTimeout> | null = null

function setState(next: SchoolConnectionState): void {
  if (state === next) return
  state = next
  handlers?.onStateChange?.(next)
}

function clearPending(): void {
  if (pendingTimer !== null) {
    clearTimeout(pendingTimer)
    pendingTimer = null
  }
  pending = null
}

function flushPending(): void {
  if (!pending || !client?.connected) return
  const request = pending
  clearPending()
  client.publish({ destination: '/app/school.send', body: JSON.stringify(request) })
}

export function connect(sessionId: string, chatHandlers: SchoolChatHandlers): void {
  disconnect()
  const thisConnection = ++epoch
  handlers = chatHandlers
  consecutiveFailures = 0
  // Assigned directly rather than through setState: disconnect() has just reset the state and
  // a caller reconnecting from 'offline' needs to be told it is trying again.
  state = 'connecting'
  handlers.onStateChange?.('connecting')

  client = new Client({
    brokerURL: buildWsUrl(),
    reconnectDelay: RECONNECT_DELAY_MS,
    reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
    maxReconnectDelay: MAX_RECONNECT_DELAY_MS,
    connectionTimeout: CONNECTION_TIMEOUT_MS,
    // Offered in both directions. The server's CONNECTED frame decides what is actually used,
    // and until the backend gained a broker TaskScheduler it answered "0, 0" — which turned
    // these off however they were set here, left the socket silent, and let every proxy on the
    // path time it out as dead.
    heartbeatIncoming: HEARTBEAT_MS,
    heartbeatOutgoing: HEARTBEAT_MS,
    onConnect: () => {
      if (epoch !== thisConnection) return
      // Drop any prior subscription first: a duplicate delivers every frame twice, which
      // interleaves the streamed answer with itself. This fires again on every reconnect, so
      // it is not a first-connect guard.
      if (subscription) {
        subscription.unsubscribe()
        subscription = null
      }
      subscription =
        client?.subscribe(`/topic/school.${sessionId}`, (message: IMessage) => {
          if (epoch !== thisConnection) return
          handlers?.onMessage(JSON.parse(message.body) as ChatResponse)
        }) ?? null
      consecutiveFailures = 0
      setState('connected')
      flushPending()
    },
    onStompError: (frame) => {
      if (epoch !== thisConnection) return
      handlers?.onError?.(frame.headers['message'] || 'Connection error')
    },
    // Close, not error, is what counts an attempt — and deliberately only one of the two.
    // A WebSocket that errors is always closed by the browser immediately afterwards, so
    // counting both would score every refused connection twice and reach `offline` in half
    // the intended time. A socket that dies mid-life reports only a close, so close is also
    // the one that covers both shapes.
    //
    // A clean shutdown closes too, and so does the client being replaced. disconnect() bumps
    // the epoch before deactivating, so the guard below is what tells those apart from a real
    // drop of the connection currently in use.
    onWebSocketClose: () => {
      if (epoch !== thisConnection) return
      consecutiveFailures += 1
      setState(consecutiveFailures >= OFFLINE_AFTER_FAILURES ? 'offline' : 'reconnecting')
    },
  })

  client.activate()
}

/**
 * Publishes if the socket is up, and otherwise holds the message for the reconnect that is
 * already in flight.
 *
 * The previous version was `if (client?.connected) publish(...)` with no else, so a question
 * typed into a page whose socket had quietly timed out was discarded silently — the caller had
 * already drawn the question in the transcript and started a typing indicator that never ended.
 *
 * @returns whether it went straight out. `false` means queued, not lost; a queued message is
 *     either flushed on connect or reported through {@link SchoolChatHandlers.onUndelivered}.
 */
export function sendMessage(request: SchoolChatRequest): boolean {
  clearPending()
  if (client?.connected) {
    client.publish({ destination: '/app/school.send', body: JSON.stringify(request) })
    return true
  }
  pending = request
  pendingTimer = setTimeout(() => {
    pendingTimer = null
    const undelivered = pending
    pending = null
    if (undelivered) handlers?.onUndelivered?.(undelivered)
  }, PENDING_SEND_TIMEOUT_MS)
  return false
}

export function disconnect(): void {
  epoch += 1
  subscription = null
  handlers = null
  consecutiveFailures = 0
  state = 'connecting'
  clearPending()
  if (client) {
    const active = client
    client = null
    void active.deactivate()
  }
}

export function isConnected(): boolean {
  return client?.connected ?? false
}

export function connectionState(): SchoolConnectionState {
  return state
}
