import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'

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
 */

export interface SchoolChatRequest {
  sessionId: string
  message: string
  yearGroups: string[]
  accessToken: string | null
}

function buildWsUrl(): string {
  if (API_BASE_URL) {
    return API_BASE_URL.replace(/^http:/, 'ws:').replace(/^https:/, 'wss:') + '/ws/chat'
  }
  const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${wsProtocol}//${window.location.host}/ws/chat`
}

let client: Client | null = null
let activeSessionId: string | null = null
let subscription: StompSubscription | null = null

export function connect(
  sessionId: string,
  onMessage: (response: ChatResponse) => void,
  onConnect?: () => void,
  onError?: (error: string) => void,
): void {
  disconnect()
  activeSessionId = sessionId

  client = new Client({
    brokerURL: buildWsUrl(),
    reconnectDelay: 5000,
    onConnect: () => {
      if (activeSessionId !== sessionId) return
      // Drop any prior subscription first: a duplicate delivers every frame twice, which
      // interleaves the streamed answer with itself.
      if (subscription) {
        subscription.unsubscribe()
        subscription = null
      }
      subscription =
        client?.subscribe(`/topic/school.${sessionId}`, (message: IMessage) => {
          if (activeSessionId !== sessionId) return
          onMessage(JSON.parse(message.body) as ChatResponse)
        }) ?? null
      onConnect?.()
    },
    onStompError: (frame) => {
      if (activeSessionId !== sessionId) return
      onError?.(frame.headers['message'] || 'Connection error')
    },
    onWebSocketError: () => {
      if (activeSessionId !== sessionId) return
      onError?.('Unable to reach Term Time')
    },
  })

  client.activate()
}

export function sendMessage(request: SchoolChatRequest): void {
  if (client?.connected) {
    client.publish({ destination: '/app/school.send', body: JSON.stringify(request) })
  }
}

export function disconnect(): void {
  activeSessionId = null
  subscription = null
  if (client) {
    const active = client
    client = null
    void active.deactivate()
  }
}

export function isConnected(): boolean {
  return client?.connected ?? false
}
