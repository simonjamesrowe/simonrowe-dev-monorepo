import { Client, IMessage } from '@stomp/stompjs'

import { API_BASE_URL } from '../config/api'

export interface ChatRequest {
  sessionId: string
  message: string
}

export interface ChatResponse {
  sessionId: string
  content: string
  type: 'STREAM_START' | 'STREAM_CHUNK' | 'STREAM_END' | 'TOOL_START' | 'TOOL_END' | 'WIDGET' | 'ERROR'
  timestamp: string
  toolLabel?: string | null
  widgetKind?: string | null
  payload?: unknown
}

function buildWsUrl(): string {
  if (API_BASE_URL) {
    // API_BASE_URL is an absolute HTTP(S) URL, e.g. http://localhost:8080
    return API_BASE_URL.replace(/^http:/, 'ws:').replace(/^https:/, 'wss:') + '/ws/chat'
  }
  // Relative-path fallback: derive from current page origin
  const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${wsProtocol}//${window.location.host}/ws/chat`
}

const WS_URL = buildWsUrl()

let stompClient: Client | null = null
let activeSessionId: string | null = null

export function connect(
  sessionId: string,
  onMessage: (response: ChatResponse) => void,
  onConnect?: () => void,
  onError?: (error: string) => void
): void {
  disconnect()
  activeSessionId = sessionId

  stompClient = new Client({
    brokerURL: WS_URL,
    reconnectDelay: 5000,
    onConnect: () => {
      if (activeSessionId !== sessionId) return
      stompClient?.subscribe(`/topic/chat.${sessionId}`, (message: IMessage) => {
        if (activeSessionId !== sessionId) return
        const response: ChatResponse = JSON.parse(message.body) as ChatResponse
        onMessage(response)
      })
      onConnect?.()
    },
    onStompError: (frame) => {
      if (activeSessionId !== sessionId) return
      onError?.(frame.headers['message'] || 'WebSocket connection error')
    },
    onWebSocketError: () => {
      if (activeSessionId !== sessionId) return
      onError?.('Unable to connect to chat service')
    },
  })

  stompClient.activate()
}

export function sendMessage(request: ChatRequest): void {
  if (stompClient?.connected) {
    stompClient.publish({
      destination: '/app/chat.send',
      body: JSON.stringify(request),
    })
  }
}

export function disconnect(): void {
  activeSessionId = null
  if (stompClient) {
    const client = stompClient
    stompClient = null
    void client.deactivate()
  }
}

export function isConnected(): boolean {
  return stompClient?.connected ?? false
}
