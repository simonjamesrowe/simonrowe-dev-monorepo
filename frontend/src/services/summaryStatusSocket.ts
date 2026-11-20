import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'

import { buildWsUrl } from './stompEndpoint'
import type { ArticleSummaryState } from '../types/articleSummary'

/** Matches `SummaryStatusBroadcaster.TOPIC` on the backend. */
export const SUMMARY_TOPIC = '/topic/summaries'

/** One summary's state change, as `SummaryStatusUpdate` sends it. */
export interface SummaryStatusUpdate {
  articleId: string
  state: Exclude<ArticleSummaryState, 'NOT_REQUESTED'>
}

export interface SummaryStatusListener {
  onUpdate: (update: SummaryStatusUpdate) => void
  /**
   * The socket came back after dropping. Updates sent while it was down are lost (the broker
   * keeps nothing), so the listener should re-read the state it cares about.
   */
  onReconnect?: () => void
}

const STATES = new Set(['GENERATING', 'READY', 'FAILED'])

/** Exported for tests. Anything that is not exactly the backend's shape is dropped. */
export function parseUpdate(body: string): SummaryStatusUpdate | null {
  try {
    const value: unknown = JSON.parse(body)
    if (typeof value !== 'object' || value === null) return null
    const { articleId, state } = value as Record<string, unknown>
    if (typeof articleId !== 'string' || articleId === '') return null
    if (typeof state !== 'string' || !STATES.has(state)) return null
    return { articleId, state: state as SummaryStatusUpdate['state'] }
  } catch {
    return null
  }
}

interface Entry {
  listener: SummaryStatusListener
  /** Has this listener lived through a connect yet? Only then is the next one a reconnect. */
  sawConnect: boolean
}

const entries = new Set<Entry>()
let client: Client | null = null
let subscription: StompSubscription | null = null

function deliver(message: IMessage) {
  const update = parseUpdate(message.body)
  if (!update) return
  entries.forEach(entry => entry.listener.onUpdate(update))
}

function start() {
  const next = new Client({
    brokerURL: buildWsUrl(),
    reconnectDelay: 5000,
    onConnect: () => {
      if (client !== next) return
      // onConnect fires again on every reconnect. A second live subscription would deliver
      // each update twice, so the old one goes first.
      subscription?.unsubscribe()
      subscription = next.subscribe(SUMMARY_TOPIC, deliver)
      entries.forEach(entry => {
        if (entry.sawConnect) entry.listener.onReconnect?.()
        entry.sawConnect = true
      })
    },
  })
  client = next
  next.activate()
}

function stop() {
  const current = client
  client = null
  subscription = null
  if (current) void current.deactivate()
}

/**
 * Follows summary state changes for as long as the returned function is not called.
 *
 * One socket is shared by every listener, opened by the first and closed by the last, so a
 * page that mounts the hook twice (StrictMode does) still holds one connection.
 *
 * Losing the socket is not an error anyone sees: the cards simply stop updating live until
 * it reconnects, and then `onReconnect` catches them up.
 */
export function subscribeToSummaryStatus(listener: SummaryStatusListener): () => void {
  // A listener joining a socket that is already up has, in effect, seen its connect.
  const entry: Entry = { listener, sawConnect: client?.connected ?? false }
  entries.add(entry)
  if (!client) start()
  return () => {
    entries.delete(entry)
    if (entries.size === 0) stop()
  }
}
