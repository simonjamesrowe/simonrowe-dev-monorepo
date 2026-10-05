import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchPlatformStatus, fetchReleases } from '../src/services/platformApi'
import type { PlatformStatus, Release, ReleasePage } from '../src/types/platform'

const STATUS: PlatformStatus = {
  services: [
    {
      name: 'backend',
      commit: '840c311abcdef0123456789abcdef0123456789a',
      shortCommit: '840c311',
      commitSubject: 'docs: overhaul the README',
      commitTime: '2026-08-26T14:02:11Z',
      startedAt: '2026-08-24T09:15:03Z',
      reachable: true,
    },
  ],
  components: [{ name: 'mongodb', image: 'mongo', tag: '8', floating: false }],
}

const RELEASES: Release[] = [
  {
    sha: '840c311abcdef0123456789abcdef0123456789a',
    shortSha: '840c311',
    type: 'docs',
    subject: 'docs: overhaul the README (#118)',
    commitTime: '2026-08-26T14:02:11Z',
    running: true,
    summary: 'The README was rewritten.',
    summaryStatus: 'READY',
  },
]

const PAGE: ReleasePage = {
  items: RELEASES,
  page: 0,
  size: 10,
  totalItems: 1,
  totalPages: 1,
  totalReleases: 1,
  typeCounts: { docs: 1 },
}

describe('platformApi', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function respondWith(body: unknown, ok = true, statusCode = 200) {
    vi.mocked(fetch).mockResolvedValue({
      ok,
      status: statusCode,
      json: async () => body,
    } as Response)
  }

  it('fetches the platform status', async () => {
    respondWith(STATUS)

    await expect(fetchPlatformStatus()).resolves.toEqual(STATUS)
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/api/platform/status'), {})
  })

  it('fetches the first page of releases by default', async () => {
    respondWith(PAGE)

    await expect(fetchReleases()).resolves.toEqual(PAGE)
    const url = new URL(vi.mocked(fetch).mock.calls[0][0] as string)
    expect(url.pathname).toBe('/api/platform/releases')
    expect(url.searchParams.get('page')).toBe('0')
    expect(url.searchParams.get('size')).toBe('10')
    expect(url.searchParams.has('type')).toBe(false)
    expect(url.searchParams.has('q')).toBe(false)
  })

  it('sends the page, type and trimmed search, encoded', async () => {
    respondWith(PAGE)

    await fetchReleases({ page: 3, size: 25, type: 'feat', query: '  C++ & co ' })

    const url = new URL(vi.mocked(fetch).mock.calls[0][0] as string)
    expect(url.searchParams.get('page')).toBe('3')
    expect(url.searchParams.get('size')).toBe('25')
    expect(url.searchParams.get('type')).toBe('feat')
    expect(url.searchParams.get('q')).toBe('C++ & co')
  })

  it('throws a readable error on a failed status response', async () => {
    respondWith(null, false, 400)

    await expect(fetchPlatformStatus()).rejects.toThrow(/status/i)
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('throws a readable error on a failed releases response', async () => {
    respondWith(null, false, 400)

    await expect(fetchReleases()).rejects.toThrow(/releases/i)
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
