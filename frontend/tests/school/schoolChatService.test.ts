import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { ChatResponse } from '../../src/services/chatService'

/**
 * The transport, with @stomp/stompjs stood in for.
 *
 * Every behaviour here is a reaction to an event the real library raises — a close, a
 * connect, a heartbeat setting handed to it — so the fake is a recorder of the config it was
 * given plus a way to raise those events. Standing up a real broker would test stompjs.
 */

interface FakeConfig {
  brokerURL: string
  heartbeatIncoming: number
  heartbeatOutgoing: number
  reconnectDelay: number
  maxReconnectDelay: number
  connectionTimeout: number
  onConnect: () => void
  onWebSocketClose: () => void
  onStompError: (frame: { headers: Record<string, string> }) => void
}

const clients: FakeClient[] = []

class FakeClient {
  connected = false
  published: Array<{ destination: string; body: string }> = []
  subscriptions: string[] = []
  deactivated = false

  constructor(public readonly config: FakeConfig) {
    clients.push(this)
  }

  activate() {}

  subscribe(destination: string, handler: (message: { body: string }) => void) {
    this.subscriptions.push(destination)
    this.handler = handler
    return { unsubscribe: () => {} }
  }

  publish(frame: { destination: string; body: string }) {
    this.published.push(frame)
  }

  deactivate() {
    this.deactivated = true
    return Promise.resolve()
  }

  handler: ((message: { body: string }) => void) | null = null

  /** Raises what the real client raises when the socket comes up. */
  open() {
    this.connected = true
    this.config.onConnect()
  }

  /** Raises what the real client raises when the socket goes down, for any reason. */
  close() {
    this.connected = false
    this.config.onWebSocketClose()
  }
}

vi.mock('@stomp/stompjs', () => ({
  Client: class {
    constructor(config: FakeConfig) {
      return new FakeClient(config)
    }
  },
  ReconnectionTimeMode: { LINEAR: 0, EXPONENTIAL: 1 },
}))

let service: typeof import('../../src/school/schoolChatService')

beforeEach(async () => {
  vi.useFakeTimers()
  clients.length = 0
  vi.resetModules()
  service = await import('../../src/school/schoolChatService')
})

afterEach(() => {
  service.disconnect()
  vi.useRealTimers()
})

const latest = () => clients[clients.length - 1]

function request(message = 'When is half term?') {
  return { sessionId: 's1', message, yearGroups: [], accessToken: null }
}

describe('the Term Time socket', () => {
  it('offers heartbeats in both directions', () => {
    service.connect('s1', { onMessage: vi.fn() })

    // Zero in either direction is what the backend used to advertise, and it is what let
    // every proxy on the path close an idle socket as dead. Both halves have to be non-zero
    // for STOMP to negotiate anything at all.
    expect(latest().config.heartbeatOutgoing).toBeGreaterThan(0)
    expect(latest().config.heartbeatIncoming).toBeGreaterThan(0)
  })

  it('does not call a single dropped connection a failure', () => {
    const onStateChange = vi.fn()
    service.connect('s1', { onMessage: vi.fn(), onStateChange })
    latest().open()
    onStateChange.mockClear()

    latest().close()

    // The state a page renders as "Reconnecting…", not as an error. A socket crossing
    // Cloudflare and a tunnel drops as a matter of routine.
    expect(onStateChange).toHaveBeenCalledWith('reconnecting')
    expect(onStateChange).not.toHaveBeenCalledWith('offline')
  })

  it('reports offline only after the retries have genuinely not worked', () => {
    const onStateChange = vi.fn()
    service.connect('s1', { onMessage: vi.fn(), onStateChange })

    latest().close()
    latest().close()
    expect(onStateChange).not.toHaveBeenCalledWith('offline')

    latest().close()
    expect(onStateChange).toHaveBeenCalledWith('offline')
  })

  it('goes back to connected, and forgets the failures, once it reconnects', () => {
    const onStateChange = vi.fn()
    service.connect('s1', { onMessage: vi.fn(), onStateChange })
    latest().close()
    latest().close()
    latest().close()
    expect(onStateChange).toHaveBeenCalledWith('offline')

    latest().open()
    expect(onStateChange).toHaveBeenLastCalledWith('connected')

    // The counter reset is the load-bearing half: without it the next single blip, however
    // long afterwards, would be the third failure and read as a dead service.
    onStateChange.mockClear()
    latest().close()
    expect(onStateChange).toHaveBeenCalledWith('reconnecting')
    expect(onStateChange).not.toHaveBeenCalledWith('offline')
  })

  it('holds a message sent while the socket is down, and sends it on reconnect', () => {
    service.connect('s1', { onMessage: vi.fn() })

    // The old version was `if (connected) publish(...)` with no else: this message simply
    // vanished, while the page showed a typing indicator that never stopped.
    expect(service.sendMessage(request())).toBe(false)
    expect(latest().published).toHaveLength(0)

    latest().open()
    expect(latest().published).toHaveLength(1)
    expect(JSON.parse(latest().published[0].body).message).toBe('When is half term?')
  })

  it('reports a held message as undelivered rather than holding it indefinitely', () => {
    const onUndelivered = vi.fn()
    service.connect('s1', { onMessage: vi.fn(), onUndelivered })
    service.sendMessage(request())

    vi.advanceTimersByTime(20_000)

    expect(onUndelivered).toHaveBeenCalledTimes(1)
    expect(onUndelivered.mock.calls[0][0].message).toBe('When is half term?')

    // And having reported it, it must not also send it later — the page has told the reader
    // it did not go and removed the placeholder.
    latest().open()
    expect(latest().published).toHaveLength(0)
  })

  it('keeps only the most recent held message', () => {
    const onUndelivered = vi.fn()
    service.connect('s1', { onMessage: vi.fn(), onUndelivered })
    service.sendMessage(request('first'))
    service.sendMessage(request('second'))

    latest().open()

    expect(latest().published).toHaveLength(1)
    expect(JSON.parse(latest().published[0].body).message).toBe('second')

    // The superseded message must not also time out and report itself undelivered.
    vi.advanceTimersByTime(30_000)
    expect(onUndelivered).not.toHaveBeenCalled()
  })

  it('sends straight away when the socket is up', () => {
    service.connect('s1', { onMessage: vi.fn() })
    latest().open()

    expect(service.sendMessage(request())).toBe(true)
    expect(latest().published).toHaveLength(1)
  })

  it('does not score a deliberate disconnect as a failure', () => {
    const onStateChange = vi.fn()
    service.connect('s1', { onMessage: vi.fn(), onStateChange })
    latest().open()
    const client = latest()
    onStateChange.mockClear()

    service.disconnect()
    client.close()

    expect(onStateChange).not.toHaveBeenCalled()
  })

  it('ignores the outgoing client when a reconnect reuses the same session', () => {
    const onStateChange = vi.fn()
    service.connect('s1', { onMessage: vi.fn(), onStateChange })
    const abandoned = clients[0]
    abandoned.close()
    abandoned.close()

    // A reconnect keeps the SAME session id, so that the transcript on screen still matches
    // the one the backend holds. deactivate() is asynchronous, so the abandoned client's
    // close lands after the replacement is already live.
    service.connect('s1', { onMessage: vi.fn(), onStateChange })
    const replacement = latest()
    expect(replacement).not.toBe(abandoned)
    onStateChange.mockClear()

    abandoned.close()

    // Scored on session id these two clients are indistinguishable, and this close would be
    // the third failure of a connection that has not failed at all.
    expect(onStateChange).not.toHaveBeenCalled()

    replacement.close()
    expect(onStateChange).toHaveBeenCalledWith('reconnecting')
    expect(onStateChange).not.toHaveBeenCalledWith('offline')
  })

  it('delivers frames to the current session only', () => {
    const onMessage = vi.fn()
    service.connect('s1', { onMessage })
    latest().open()

    const frame: ChatResponse = {
      sessionId: 's1',
      content: 'Half term is 27 October.',
      type: 'STREAM_END',
      timestamp: '',
    }
    latest().handler?.({ body: JSON.stringify(frame) })

    expect(onMessage).toHaveBeenCalledWith(frame)
    expect(latest().subscriptions).toContain('/topic/school.s1')
  })
})
