import { beforeEach, describe, expect, it, vi } from 'vitest'

// src/test/setup.ts replaces this module for every other test so none opens a real socket.
vi.unmock('../../src/services/summaryStatusSocket')

interface FakeClient {
  config: { brokerURL: string, onConnect: () => void }
  connected: boolean
  activate: ReturnType<typeof vi.fn>
  deactivate: ReturnType<typeof vi.fn>
  subscribe: ReturnType<typeof vi.fn>
  /** Sends a frame to whatever the current subscription is. */
  deliver: (body: string) => void
  subscriptions: Array<{ unsubscribe: ReturnType<typeof vi.fn> }>
}

const stomp = vi.hoisted(() => ({ clients: [] as FakeClient[] }))

vi.mock('@stomp/stompjs', () => ({
  Client: vi.fn().mockImplementation((config: FakeClient['config']) => {
    let handler: ((message: { body: string }) => void) | null = null
    const client: FakeClient = {
      config,
      connected: false,
      activate: vi.fn(),
      deactivate: vi.fn(() => Promise.resolve()),
      subscriptions: [],
      subscribe: vi.fn((_topic: string, onMessage: (message: { body: string }) => void) => {
        handler = onMessage
        const subscription = { unsubscribe: vi.fn() }
        client.subscriptions.push(subscription)
        return subscription
      }),
      deliver: (body) => handler?.({ body }),
    }
    stomp.clients.push(client)
    return client
  }),
}))

async function load() {
  vi.resetModules()
  return import('../../src/services/summaryStatusSocket')
}

function connect(client: FakeClient) {
  client.connected = true
  client.config.onConnect()
}

describe('summaryStatusSocket', () => {
  beforeEach(() => {
    stomp.clients.length = 0
  })

  it('opens one socket for every listener on the shared STOMP endpoint', async () => {
    const socket = await load()
    const first = socket.subscribeToSummaryStatus({ onUpdate: vi.fn() })
    const second = socket.subscribeToSummaryStatus({ onUpdate: vi.fn() })

    expect(stomp.clients).toHaveLength(1)
    expect(stomp.clients[0].config.brokerURL).toMatch(/\/ws\/chat$/)
    expect(stomp.clients[0].activate).toHaveBeenCalledTimes(1)

    first()
    expect(stomp.clients[0].deactivate).not.toHaveBeenCalled()
    second()
    expect(stomp.clients[0].deactivate).toHaveBeenCalledTimes(1)
  })

  it('subscribes to the summaries topic and hands valid updates to every listener',
    async () => {
      const socket = await load()
      const a = vi.fn()
      const b = vi.fn()
      socket.subscribeToSummaryStatus({ onUpdate: a })
      socket.subscribeToSummaryStatus({ onUpdate: b })
      const client = stomp.clients[0]
      connect(client)

      expect(client.subscribe).toHaveBeenCalledWith('/topic/summaries', expect.any(Function))
      client.deliver('{"articleId":"art-1","state":"READY"}')

      expect(a).toHaveBeenCalledWith({ articleId: 'art-1', state: 'READY' })
      expect(b).toHaveBeenCalledWith({ articleId: 'art-1', state: 'READY' })
    })

  it('drops anything that is not exactly an update', async () => {
    const socket = await load()
    const onUpdate = vi.fn()
    socket.subscribeToSummaryStatus({ onUpdate })
    const client = stomp.clients[0]
    connect(client)

    client.deliver('not json')
    client.deliver('null')
    client.deliver('{"articleId":"art-1","state":"NOT_REQUESTED"}')
    client.deliver('{"articleId":"","state":"READY"}')
    client.deliver('{"articleId":7,"state":"READY"}')

    expect(onUpdate).not.toHaveBeenCalled()
  })

  it('re-subscribes on reconnect without doubling up, and tells listeners to catch up',
    async () => {
      const socket = await load()
      const onReconnect = vi.fn()
      const onUpdate = vi.fn()
      socket.subscribeToSummaryStatus({ onUpdate, onReconnect })
      const client = stomp.clients[0]

      connect(client)
      // The first connect is not a reconnect: the listener has just read its state.
      expect(onReconnect).not.toHaveBeenCalled()

      connect(client)
      expect(onReconnect).toHaveBeenCalledTimes(1)
      expect(client.subscriptions[0].unsubscribe).toHaveBeenCalledTimes(1)

      client.deliver('{"articleId":"art-1","state":"GENERATING"}')
      expect(onUpdate).toHaveBeenCalledTimes(1)
    })

  it('treats the next connect as a reconnect for a listener that joined a live socket',
    async () => {
      const socket = await load()
      socket.subscribeToSummaryStatus({ onUpdate: vi.fn() })
      const client = stomp.clients[0]
      connect(client)

      const onReconnect = vi.fn()
      socket.subscribeToSummaryStatus({ onUpdate: vi.fn(), onReconnect })
      connect(client)

      expect(onReconnect).toHaveBeenCalledTimes(1)
    })

  it('opens a fresh socket when a listener arrives after the last one left', async () => {
    const socket = await load()
    socket.subscribeToSummaryStatus({ onUpdate: vi.fn() })()
    socket.subscribeToSummaryStatus({ onUpdate: vi.fn() })

    expect(stomp.clients).toHaveLength(2)
    // A late connect from the closed client must not subscribe anything.
    connect(stomp.clients[0])
    expect(stomp.clients[0].subscribe).not.toHaveBeenCalled()
  })
})
