import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useNarration } from '../../../src/components/narration/useNarration'
import type { BlogNarrationResponse } from '../../../src/types/blog'

const NOT_REQUESTED: BlogNarrationResponse = {
  state: 'NOT_REQUESTED', version: 0, retryable: false, message: 'Listen to this post',
}
const QUEUED: BlogNarrationResponse = {
  state: 'QUEUED', version: 1, retryable: false, message: 'Preparing audio',
}
const READY: BlogNarrationResponse = {
  state: 'READY',
  version: 3,
  audioUrl: '/uploads/narrations/id/narration.mp3',
  durationSeconds: 90,
  retryable: false,
  message: 'Ready',
}

function transport(overrides: Partial<{
  fetchStatus: ReturnType<typeof vi.fn>
  request: ReturnType<typeof vi.fn>
}> = {}) {
  return {
    fetchStatus: vi.fn().mockResolvedValue(NOT_REQUESTED),
    request: vi.fn().mockResolvedValue(QUEUED),
    ...overrides,
  }
}

describe('useNarration', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('reads the current status on mount', async () => {
    const t = transport()

    const { result } = renderHook(() => useNarration(t))

    await waitFor(() => expect(result.current.checking).toBe(false))
    expect(t.fetchStatus).toHaveBeenCalledTimes(1)
    expect(result.current.narration).toEqual(NOT_REQUESTED)
  })

  it('does not request generation on its own', async () => {
    const t = transport()

    const { result } = renderHook(() => useNarration(t))

    await waitFor(() => expect(result.current.checking).toBe(false))
    expect(t.request).not.toHaveBeenCalled()
  })

  it('long-polls while pending and stops once the state settles', async () => {
    const t = transport({
      fetchStatus: vi.fn()
        .mockResolvedValueOnce(NOT_REQUESTED)
        .mockResolvedValue(READY),
    })

    const { result } = renderHook(() => useNarration(t))
    await waitFor(() => expect(result.current.checking).toBe(false))

    await act(async () => { await result.current.requestNarration() })

    expect(t.fetchStatus).toHaveBeenLastCalledWith(expect.objectContaining({
      afterVersion: QUEUED.version,
      waitSeconds: 25,
    }))
    expect(result.current.narration).toEqual(READY)
    expect(result.current.delayed).toBe(false)
  })

  it('flags delayed once the poll budget is exhausted', async () => {
    const t = transport({
      fetchStatus: vi.fn()
        .mockResolvedValueOnce(NOT_REQUESTED)
        .mockResolvedValue(QUEUED),
    })

    const { result } = renderHook(() => useNarration(t))
    await waitFor(() => expect(result.current.checking).toBe(false))

    await act(async () => { await result.current.requestNarration() })

    // One initial read plus four long-polls.
    expect(t.fetchStatus).toHaveBeenCalledTimes(5)
    expect(result.current.delayed).toBe(true)
  })

  it('starts polling immediately when the initial read is already pending', async () => {
    const t = transport({
      fetchStatus: vi.fn()
        .mockResolvedValueOnce(QUEUED)
        .mockResolvedValue(READY),
    })

    const { result } = renderHook(() => useNarration(t))

    await waitFor(() => expect(result.current.narration).toEqual(READY))
  })

  it('surfaces a status-read failure as a client error', async () => {
    const t = transport({ fetchStatus: vi.fn().mockRejectedValue(new Error('down')) })

    const { result } = renderHook(() => useNarration(t))

    await waitFor(() => expect(result.current.clientError)
      .toBe('Audio status could not be checked. Please try again.'))
    expect(result.current.checking).toBe(false)
  })

  it('surfaces a request failure as a client error', async () => {
    const t = transport({ request: vi.fn().mockRejectedValue(new Error('down')) })

    const { result } = renderHook(() => useNarration(t))
    await waitFor(() => expect(result.current.checking).toBe(false))

    await act(async () => { await result.current.requestNarration() })

    expect(result.current.clientError)
      .toBe('Audio could not be requested. Please try again.')
    expect(result.current.requesting).toBe(false)
  })

  it('aborts the in-flight request on unmount', async () => {
    let capturedSignal: AbortSignal | undefined
    const t = transport({
      fetchStatus: vi.fn().mockImplementation(async (options) => {
        capturedSignal = options.signal
        return NOT_REQUESTED
      }),
    })

    const { result, unmount } = renderHook(() => useNarration(t))
    await waitFor(() => expect(result.current.checking).toBe(false))

    unmount()

    expect(capturedSignal?.aborted).toBe(true)
  })

  it('does not update state after an abort', async () => {
    const t = transport({
      fetchStatus: vi.fn().mockImplementation(async (options) => {
        options.signal?.dispatchEvent?.(new Event('abort'))
        return NOT_REQUESTED
      }),
    })

    const { result, unmount } = renderHook(() => useNarration(t))
    await waitFor(() => expect(t.fetchStatus).toHaveBeenCalled())

    unmount()

    // No unhandled rejection and no post-unmount state write.
    expect(result.current.clientError).toBeNull()
  })

  it('re-checks from the current pending state when asked', async () => {
    const t = transport({
      fetchStatus: vi.fn()
        .mockResolvedValueOnce(QUEUED)
        .mockResolvedValue(READY),
    })

    const { result } = renderHook(() => useNarration(t))
    await waitFor(() => expect(result.current.narration).toEqual(READY))

    const callsBefore = t.fetchStatus.mock.calls.length
    act(() => { result.current.recheck() })

    await waitFor(() =>
      expect(t.fetchStatus.mock.calls.length).toBeGreaterThan(callsBefore))
  })
})
