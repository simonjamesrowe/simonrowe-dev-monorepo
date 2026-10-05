import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useReleases } from '../../src/hooks/useReleases'
import { fetchReleases } from '../../src/services/platformApi'
import type { Release, ReleasePage } from '../../src/types/platform'

vi.mock('../../src/services/platformApi', () => ({
  RELEASE_PAGE_SIZE: 10,
  fetchReleases: vi.fn(),
}))

const mockFetch = vi.mocked(fetchReleases)

function release(sha: string): Release {
  return {
    sha,
    shortSha: sha.slice(0, 7),
    type: 'feat',
    subject: `feat: ${sha}`,
    commitTime: '2026-10-01T09:00:00Z',
    running: false,
    summary: null,
    summaryStatus: 'PENDING',
  }
}

function page(items: Release[], number: number, totalPages: number): ReleasePage {
  return {
    items,
    page: number,
    size: 10,
    totalItems: totalPages * 10,
    totalPages,
    totalReleases: 99,
    typeCounts: { feat: 99 },
  }
}

describe('useReleases', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('loads the first page and appends the next one rather than replacing it', async () => {
    mockFetch
      .mockResolvedValueOnce(page([release('a'.repeat(40))], 0, 2))
      .mockResolvedValueOnce(page([release('b'.repeat(40))], 1, 2))

    const { result } = renderHook(() => useReleases())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.hasMore).toBe(true)
    expect(result.current.totalReleases).toBe(99)

    act(() => result.current.loadMore())
    await waitFor(() => expect(result.current.releases).toHaveLength(2))

    expect(mockFetch).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, type: null, query: '' }),
    )
    expect(result.current.hasMore).toBe(false)
  })

  it('asks the server for a type and starts again from the first page', async () => {
    mockFetch.mockResolvedValue(page([release('a'.repeat(40))], 0, 1))

    const { result } = renderHook(() => useReleases())
    await waitFor(() => expect(result.current.loading).toBe(false))

    act(() => result.current.setActiveType('fix'))
    await waitFor(() =>
      expect(mockFetch).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, type: 'fix' })),
    )
  })

  it('debounces the search so a word being typed is one request, not one per key', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mockFetch.mockResolvedValue(page([], 0, 0))

    const { result } = renderHook(() => useReleases())
    await waitFor(() => expect(mockFetch).toHaveBeenCalledTimes(1))

    act(() => result.current.setQuery('d'))
    act(() => result.current.setQuery('de'))
    act(() => result.current.setQuery('deploy'))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(350)
    })

    expect(mockFetch).toHaveBeenCalledTimes(2)
    expect(mockFetch).toHaveBeenLastCalledWith(expect.objectContaining({ query: 'deploy' }))
  })

  it('drops a slow response for a query the reader has already moved past', async () => {
    let resolveSlow: (value: ReleasePage) => void = () => {}
    mockFetch
      .mockReturnValueOnce(new Promise((resolve) => { resolveSlow = resolve }))
      .mockResolvedValueOnce(page([release('c'.repeat(40))], 0, 1))

    const { result } = renderHook(() => useReleases())
    act(() => result.current.setActiveType('fix'))
    await waitFor(() => expect(result.current.releases).toHaveLength(1))

    await act(async () => {
      resolveSlow(page([release('a'.repeat(40)), release('b'.repeat(40))], 0, 1))
    })

    expect(result.current.releases.map((r) => r.sha)).toEqual(['c'.repeat(40)])
  })
})
