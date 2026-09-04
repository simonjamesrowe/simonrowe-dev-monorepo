import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchFactoryFlow } from '../src/services/softwareFactoryApi'

afterEach(() => { vi.unstubAllGlobals() })

describe('fetchFactoryFlow', () => {
  it('sends the bearer token and returns the graph', async () => {
    const body = {
      fetchedAt: '2026-09-04T10:00:00Z',
      nodes: [{
        key: 'logwatch', kind: 'MODULE', band: 'OBSERVE', label: 'Log watch',
        counts: { inFlight: 0, ok24h: 2, failed24h: 0 }, health: 'READY', diagnostic: null,
      }],
      edges: [{ from: 'logwatch', to: 'linear', label: 'files signature', loop: 'MAIN' }],
    }
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, json: () => Promise.resolve(body),
    })
    vi.stubGlobal('fetch', fetchMock)

    const flow = await fetchFactoryFlow(() => Promise.resolve('token-abc'))

    expect(flow.nodes[0].key).toBe('logwatch')
    expect(flow.edges[0].loop).toBe('MAIN')
    const [url, options] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/api/admin/software-factory/flow')
    expect(options.headers.Authorization).toBe('Bearer token-abc')
  })

  it('surfaces the server message when the request fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 502,
      json: () => Promise.resolve({ message: 'The Software Factory is unreachable' }),
    }))

    await expect(fetchFactoryFlow(() => Promise.resolve('t')))
      .rejects.toThrow('The Software Factory is unreachable')
  })
})
