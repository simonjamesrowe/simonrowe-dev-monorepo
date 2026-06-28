import { beforeEach, describe, expect, it, vi } from 'vitest'

const clientMocks = vi.hoisted(() => ({
  instances: [] as Array<{
    activate: ReturnType<typeof vi.fn>
    deactivate: ReturnType<typeof vi.fn>
    forceDisconnect: ReturnType<typeof vi.fn>
  }>,
}))

vi.mock('@stomp/stompjs', () => ({
  Client: vi.fn().mockImplementation(() => {
    const client = {
      activate: vi.fn(),
      deactivate: vi.fn(() => Promise.resolve()),
      forceDisconnect: vi.fn(),
    }
    clientMocks.instances.push(client)
    return client
  }),
}))

describe('chatService', () => {
  beforeEach(() => {
    clientMocks.instances.length = 0
  })

  it('deactivates the STOMP client without force-closing the WebSocket twice', async () => {
    const chatService = await import('../../src/services/chatService')

    chatService.connect('session-1', vi.fn())
    chatService.disconnect()

    const client = clientMocks.instances[0]
    expect(client.forceDisconnect).not.toHaveBeenCalled()
    expect(client.deactivate).toHaveBeenCalledTimes(1)
  })
})
