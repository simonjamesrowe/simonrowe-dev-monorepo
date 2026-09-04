import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchFactoryFlow, fetchFactoryFlowDetail } from '../src/services/softwareFactoryApi'

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

describe('fetchFactoryFlowDetail', () => {
  it('sends the bearer token and requests the given node', async () => {
    const body = {
      nodeKey: 'logwatch',
      items: [{ id: 'logwatch-1', title: 'logwatch-1', status: 'COMPLETED', at: null, url: null }],
    }
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, json: () => Promise.resolve(body),
    })
    vi.stubGlobal('fetch', fetchMock)

    const detail = await fetchFactoryFlowDetail(() => Promise.resolve('token-abc'), 'logwatch')

    expect(detail.items[0].id).toBe('logwatch-1')
    const [url, options] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/api/admin/software-factory/flow/logwatch')
    expect(options.headers.Authorization).toBe('Bearer token-abc')
  })

  it('url-encodes the node key', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, json: () => Promise.resolve({ nodeKey: 'a/b', items: [] }),
    })
    vi.stubGlobal('fetch', fetchMock)

    await fetchFactoryFlowDetail(() => Promise.resolve('t'), 'a/b')

    expect(String(fetchMock.mock.calls[0][0])).toContain('/flow/a%2Fb')
  })
})
