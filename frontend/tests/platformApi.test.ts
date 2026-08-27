import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchPlatformStatus, fetchReleases } from '../src/services/platformApi'
import type { PlatformStatus, Release } from '../src/types/platform'

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
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/api/platform/status'))
  })

  it('fetches releases with the default limit of 20', async () => {
    respondWith(RELEASES)

    await expect(fetchReleases()).resolves.toEqual(RELEASES)
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('limit=20'))
  })

  it('fetches releases with an explicit limit', async () => {
    respondWith(RELEASES)

    await fetchReleases(5)

    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('limit=5'))
  })

  it('throws a readable error on a failed status response', async () => {
    respondWith(null, false, 503)

    await expect(fetchPlatformStatus()).rejects.toThrow(/status/i)
  })

  it('throws a readable error on a failed releases response', async () => {
    respondWith(null, false, 500)

    await expect(fetchReleases()).rejects.toThrow(/releases/i)
  })
})
