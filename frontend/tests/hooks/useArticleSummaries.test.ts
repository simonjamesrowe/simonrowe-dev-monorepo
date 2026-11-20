import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../src/auth/useAuth', () => ({
  useAuth: vi.fn(),
}))
vi.mock('../../src/services/articleSummaryApi', () => ({
  fetchArticleSummary: vi.fn(),
  fetchSummarisedArticleIds: vi.fn(),
  fetchGeneratingArticleIds: vi.fn(),
  requestArticleSummary: vi.fn(),
}))

import { useAuth } from '../../src/auth/useAuth'
import {
  fetchArticleSummary,
  fetchGeneratingArticleIds,
  fetchSummarisedArticleIds,
  requestArticleSummary,
} from '../../src/services/articleSummaryApi'
import {
  subscribeToSummaryStatus,
  type SummaryStatusListener,
  type SummaryStatusUpdate,
} from '../../src/services/summaryStatusSocket'
import { useArticleSummaries } from '../../src/hooks/useArticleSummaries'
import type { ArticleSummaryResponse } from '../../src/types/articleSummary'

const mockUseAuth = vi.mocked(useAuth)
const mockFetchSummary = vi.mocked(fetchArticleSummary)
const mockFetchIds = vi.mocked(fetchSummarisedArticleIds)
const mockRequestSummary = vi.mocked(requestArticleSummary)
const mockFetchGenerating = vi.mocked(fetchGeneratingArticleIds)
// Mocked for every test in src/test/setup.ts, so nothing here opens a real socket.
const mockSubscribe = vi.mocked(subscribeToSummaryStatus)

/** The listener the hook registered on the summary socket. */
function socketListener(): SummaryStatusListener {
  const call = mockSubscribe.mock.calls.at(-1)
  if (!call) throw new Error('The hook did not subscribe to summary updates')
  return call[0]
}

function push(update: SummaryStatusUpdate) {
  act(() => { socketListener().onUpdate(update) })
}

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
    mockFetchGenerating.mockResolvedValue([])
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

  /**
   * The reported bug: pressing Summarise on several cards in turn, closing each drawer, left
   * every card still offering Summarise. Closing aborts the request, so the request can no
   * longer be what tells the card; the summary socket does.
   */
  describe('live summary state', () => {
    it('starts with the summaries already being written as generating', async () => {
      setAuth(false)
      mockFetchGenerating.mockResolvedValue(['art-1'])

      const { result } = renderHook(() => useArticleSummaries())

      await waitFor(() => expect(result.current.isGenerating('art-1')).toBe(true))
      expect(result.current.isGenerating('art-2')).toBe(false)
      expect(getAccessToken).not.toHaveBeenCalled()
    })

    it('follows a summary someone else started, through to ready', async () => {
      setAuth(false)
      const { result } = renderHook(() => useArticleSummaries())
      await waitFor(() => expect(mockSubscribe).toHaveBeenCalled())

      push({ articleId: 'art-1', state: 'GENERATING' })
      expect(result.current.isGenerating('art-1')).toBe(true)
      expect(result.current.hasSummary('art-1')).toBe(false)

      push({ articleId: 'art-1', state: 'READY' })
      expect(result.current.isGenerating('art-1')).toBe(false)
      expect(result.current.hasSummary('art-1')).toBe(true)
    })

    it('stops showing progress when the summary fails', async () => {
      setAuth(false)
      mockFetchGenerating.mockResolvedValue(['art-1'])
      const { result } = renderHook(() => useArticleSummaries())
      await waitFor(() => expect(result.current.isGenerating('art-1')).toBe(true))

      push({ articleId: 'art-1', state: 'FAILED' })

      expect(result.current.isGenerating('art-1')).toBe(false)
      expect(result.current.hasSummary('art-1')).toBe(false)
    })

    it('keeps a card summarising after its drawer is closed, until the server says ready',
      async () => {
        setAuth(true)
        // The POST blocks for the whole generation, and closing the drawer aborts it.
        mockRequestSummary.mockImplementation((_token, _id, signal) =>
          new Promise((_resolve, reject) => {
            signal?.addEventListener('abort', () =>
              reject(new DOMException('aborted', 'AbortError')))
          }))
        mockFetchSummary.mockResolvedValue(GENERATING)

        const { result } = renderHook(() => useArticleSummaries())
        await waitFor(() => expect(mockSubscribe).toHaveBeenCalled())
        act(() => { void result.current.requestSummary('art-1') })
        await waitFor(() => expect(result.current.isGenerating('art-1')).toBe(true))

        act(() => { result.current.cancel('art-1') })
        // The server got the request, so the read that follows the abort says generating.
        await waitFor(() => expect(mockFetchSummary).toHaveBeenCalledWith('art-1'))
        expect(result.current.isGenerating('art-1')).toBe(true)

        push({ articleId: 'art-1', state: 'READY' })
        expect(result.current.hasSummary('art-1')).toBe(true)
        expect(result.current.isGenerating('art-1')).toBe(false)
      })

    it('clears the progress when an aborted request never reached the server', async () => {
      setAuth(true)
      mockRequestSummary.mockImplementation((_token, _id, signal) =>
        new Promise((_resolve, reject) => {
          signal?.addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')))
        }))
      mockFetchSummary.mockResolvedValue(NOT_REQUESTED)

      const { result } = renderHook(() => useArticleSummaries())
      act(() => { void result.current.requestSummary('art-1') })
      await waitFor(() => expect(result.current.isGenerating('art-1')).toBe(true))

      act(() => { result.current.cancel('art-1') })

      // Otherwise the card would read "Summarising" for good.
      await waitFor(() => expect(result.current.isGenerating('art-1')).toBe(false))
    })

    it('does not re-check on cancel once the request has answered', async () => {
      setAuth(true)
      mockRequestSummary.mockResolvedValue(READY)

      const { result } = renderHook(() => useArticleSummaries())
      await act(async () => { await result.current.requestSummary('art-1') })
      act(() => { result.current.cancel('art-1') })

      expect(mockFetchSummary).not.toHaveBeenCalled()
    })

    it('clears the progress when the request itself fails', async () => {
      setAuth(true)
      mockRequestSummary.mockRejectedValue(new Error('boom'))

      const { result } = renderHook(() => useArticleSummaries())
      await act(async () => { await result.current.requestSummary('art-1') })

      expect(result.current.isGenerating('art-1')).toBe(false)
    })

    it('brings a drawer that gave up polling up to date when the summary is ready',
      async () => {
        setAuth(true)
        mockRequestSummary.mockResolvedValue(GENERATING)
        mockFetchSummary.mockResolvedValue(GENERATING)

        const { result } = renderHook(() => useArticleSummaries())
        await act(async () => { await result.current.requestSummary('art-1') })
        expect(result.current.isDelayed('art-1')).toBe(true)

        mockFetchSummary.mockResolvedValue(READY)
        push({ articleId: 'art-1', state: 'READY' })

        await waitFor(() => expect(result.current.summaryFor('art-1')?.state).toBe('READY'))
        expect(result.current.isDelayed('art-1')).toBe(false)
      })

    it('re-reads both id sets when the socket reconnects', async () => {
      setAuth(false)
      const { result } = renderHook(() => useArticleSummaries())
      await waitFor(() => expect(mockFetchGenerating).toHaveBeenCalledTimes(1))

      // Missed while the socket was down: art-1 finished and art-2 started.
      mockFetchIds.mockResolvedValue(['art-1'])
      mockFetchGenerating.mockResolvedValue(['art-2'])
      act(() => { socketListener().onReconnect?.() })

      await waitFor(() => expect(result.current.hasSummary('art-1')).toBe(true))
      await waitFor(() => expect(result.current.isGenerating('art-2')).toBe(true))
    })

    it('unsubscribes on unmount', async () => {
      const unsubscribe = vi.fn()
      mockSubscribe.mockReturnValueOnce(unsubscribe)
      setAuth(false)

      const { unmount } = renderHook(() => useArticleSummaries())
      unmount()

      expect(unsubscribe).toHaveBeenCalledTimes(1)
    })
  })
})
