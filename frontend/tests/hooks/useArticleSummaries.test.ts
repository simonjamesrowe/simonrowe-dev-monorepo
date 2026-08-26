import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))
vi.mock('../../src/services/articleSummaryApi', () => ({
  fetchArticleSummary: vi.fn(),
  fetchSummarisedArticleIds: vi.fn(),
  requestArticleSummary: vi.fn(),
}))

import { useAuth } from '../../src/auth/useAuth'
import {
  fetchArticleSummary,
  fetchSummarisedArticleIds,
  requestArticleSummary,
} from '../../src/services/articleSummaryApi'
import { useArticleSummaries } from '../../src/hooks/useArticleSummaries'
import type { ArticleSummaryResponse } from '../../src/types/articleSummary'

const mockUseAuth = vi.mocked(useAuth)
const mockFetchSummary = vi.mocked(fetchArticleSummary)
const mockFetchIds = vi.mocked(fetchSummarisedArticleIds)
const mockRequestSummary = vi.mocked(requestArticleSummary)

const getAccessToken = vi.fn().mockResolvedValue('token')
const loginWithPopup = vi.fn()

function setAuth(isAuthenticated: boolean) {
  mockUseAuth.mockReturnValue({
    isAuthenticated,
    getAccessToken,
    loginWithPopup,
  } as unknown as ReturnType<typeof useAuth>)
}

const READY: ArticleSummaryResponse = {
  state: 'READY',
  version: 2,
  body: 'Generated prose.',
  retryable: false,
  message: 'Summary ready',
}

const GENERATING: ArticleSummaryResponse = {
  state: 'GENERATING',
  version: 1,
  retryable: false,
  message: 'Writing the summary',
}

const NOT_REQUESTED: ArticleSummaryResponse = {
  state: 'NOT_REQUESTED',
  version: 0,
  retryable: false,
  message: 'Summarise this article',
}

describe('useArticleSummaries', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getAccessToken.mockResolvedValue('token')
    loginWithPopup.mockResolvedValue(undefined)
    mockFetchIds.mockResolvedValue([])
    mockFetchSummary.mockResolvedValue(NOT_REQUESTED)
    mockRequestSummary.mockResolvedValue(READY)
  })

  it('loads the summarised-article id set on mount without a token', async () => {
    setAuth(false)
    mockFetchIds.mockResolvedValue(['art-1'])

    const { result } = renderHook(() => useArticleSummaries())

    await waitFor(() => expect(result.current.hasSummary('art-1')).toBe(true))
    expect(result.current.hasSummary('art-2')).toBe(false)
    expect(getAccessToken).not.toHaveBeenCalled()
  })

  it('leaves the set empty when the ids fetch fails rather than throwing', async () => {
    setAuth(true)
    mockFetchIds.mockRejectedValue(new Error('down'))

    const { result } = renderHook(() => useArticleSummaries())

    await waitFor(() => expect(mockFetchIds).toHaveBeenCalled())
    expect(result.current.hasSummary('art-1')).toBe(false)
  })

  it('reads an existing summary without requesting generation', async () => {
    setAuth(false)
    mockFetchSummary.mockResolvedValue(READY)

    const { result } = renderHook(() => useArticleSummaries())
    await act(async () => { await result.current.loadSummary('art-1') })

    expect(result.current.summaryFor('art-1')).toEqual(READY)
    expect(mockRequestSummary).not.toHaveBeenCalled()
    // Reading a shared summary must never push a logged-out visitor at the login popup.
    expect(loginWithPopup).not.toHaveBeenCalled()
    expect(mockFetchSummary).toHaveBeenCalledWith('art-1', expect.any(Object))
  })

  it('runs the login popup before requesting generation when logged out', async () => {
    setAuth(false)

    const { result } = renderHook(() => useArticleSummaries())
    await act(async () => { await result.current.requestSummary('art-1') })

    expect(loginWithPopup).toHaveBeenCalledTimes(1)
    expect(mockRequestSummary).toHaveBeenCalledTimes(1)
  })

  /**
   * The whole reason ensureAuthenticated confirms with a token: auth0-react resolves the
   * popup promise even when the visitor cancels. A dismissed popup must cost nothing.
   */
  it('does not request generation when the login popup is dismissed', async () => {
    setAuth(false)
    getAccessToken.mockRejectedValue(new Error('login required'))

    const { result } = renderHook(() => useArticleSummaries())
    await act(async () => { await result.current.requestSummary('art-1') })

    expect(mockRequestSummary).not.toHaveBeenCalled()
  })

  it('does not run the popup when already signed in', async () => {
    setAuth(true)

    const { result } = renderHook(() => useArticleSummaries())
    await act(async () => { await result.current.requestSummary('art-1') })

    expect(loginWithPopup).not.toHaveBeenCalled()
    expect(mockRequestSummary).toHaveBeenCalledWith(
      getAccessToken, 'art-1', expect.any(AbortSignal))
  })

  it('adds the article to the ids set once generation completes', async () => {
    setAuth(true)

    const { result } = renderHook(() => useArticleSummaries())
    expect(result.current.hasSummary('art-1')).toBe(false)

    await act(async () => { await result.current.requestSummary('art-1') })

    expect(result.current.hasSummary('art-1')).toBe(true)
  })

  it('long-polls while generation is in progress and stops once it settles', async () => {
    setAuth(true)
    mockRequestSummary.mockResolvedValue(GENERATING)
    mockFetchSummary.mockResolvedValue(READY)

    const { result } = renderHook(() => useArticleSummaries())
    await act(async () => { await result.current.requestSummary('art-1') })

    expect(mockFetchSummary).toHaveBeenCalledWith('art-1', expect.objectContaining({
      afterVersion: GENERATING.version,
      waitSeconds: 25,
    }))
    expect(mockFetchSummary).toHaveBeenCalledTimes(1)
    expect(result.current.summaryFor('art-1')).toEqual(READY)
  })

  it('gives up after the poll budget and flags the article as delayed', async () => {
    setAuth(true)
    mockRequestSummary.mockResolvedValue(GENERATING)
    mockFetchSummary.mockResolvedValue(GENERATING)

    const { result } = renderHook(() => useArticleSummaries())
    await act(async () => { await result.current.requestSummary('art-1') })

    expect(mockFetchSummary).toHaveBeenCalledTimes(4)
    expect(result.current.isDelayed('art-1')).toBe(true)
  })

  it('surfaces a request failure as an error rather than throwing', async () => {
    setAuth(true)
    mockRequestSummary.mockRejectedValue(new Error('boom'))

    const { result } = renderHook(() => useArticleSummaries())
    await act(async () => { await result.current.requestSummary('art-1') })

    expect(result.current.errorFor('art-1'))
      .toBe('The summary could not be requested. Please try again.')
  })

  it('surfaces a read failure as an error', async () => {
    setAuth(false)
    mockFetchSummary.mockRejectedValue(new Error('boom'))

    const { result } = renderHook(() => useArticleSummaries())
    await act(async () => { await result.current.loadSummary('art-1') })

    expect(result.current.errorFor('art-1'))
      .toBe('The summary could not be loaded. Please try again.')
  })

  it('aborts an in-flight poll when the drawer is cancelled', async () => {
    setAuth(true)
    let capturedSignal: AbortSignal | undefined
    mockRequestSummary.mockImplementation(async (_token, _id, signal) => {
      capturedSignal = signal
      return GENERATING
    })
    mockFetchSummary.mockResolvedValue(GENERATING)

    const { result } = renderHook(() => useArticleSummaries())
    await act(async () => { await result.current.requestSummary('art-1') })

    act(() => { result.current.cancel('art-1') })

    expect(capturedSignal?.aborted).toBe(true)
    expect(result.current.isDelayed('art-1')).toBe(false)
  })

  it('aborts in-flight work on unmount', async () => {
    setAuth(true)
    let capturedSignal: AbortSignal | undefined
    mockRequestSummary.mockImplementation(async (_token, _id, signal) => {
      capturedSignal = signal
      return READY
    })

    const { result, unmount } = renderHook(() => useArticleSummaries())
    await act(async () => { await result.current.requestSummary('art-1') })

    unmount()

    expect(capturedSignal?.aborted).toBe(true)
  })

  /**
   * The docked audio player's Listen chain can generate a summary as an intermediate step, and
   * it sits above this hook in the tree so it cannot write here itself. Without `noteSummarised`
   * such a card would keep reading "Summarise" until the next full page load.
   */
  describe('noteSummarised', () => {
    it('flips the card without refetching the ids set', async () => {
      setAuth(true)
      mockFetchIds.mockResolvedValue([])

      const { result } = renderHook(() => useArticleSummaries())
      await waitFor(() => expect(mockFetchIds).toHaveBeenCalledTimes(1))
      expect(result.current.hasSummary('art-1')).toBe(false)

      act(() => { result.current.noteSummarised('art-1') })

      expect(result.current.hasSummary('art-1')).toBe(true)
      expect(mockFetchIds).toHaveBeenCalledTimes(1)
    })

    it('is idempotent and leaves other articles alone', async () => {
      setAuth(true)
      mockFetchIds.mockResolvedValue(['art-9'])

      const { result } = renderHook(() => useArticleSummaries())
      await waitFor(() => expect(result.current.hasSummary('art-9')).toBe(true))

      act(() => { result.current.noteSummarised('art-1') })
      act(() => { result.current.noteSummarised('art-1') })

      expect(result.current.hasSummary('art-1')).toBe(true)
      expect(result.current.hasSummary('art-9')).toBe(true)
      expect(result.current.hasSummary('art-2')).toBe(false)
    })
  })
})
