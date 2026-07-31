import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchWithRetry } from '../../src/services/fetchWithRetry'

/** A `Response` stand-in good enough for the paths fetchWithRetry exercises. */
function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response
}

/** A failed response whose body is not JSON, exercising the parse-failure path. */
function unparseableResponse(status: number): Response {
  return {
    ok: false,
    status,
    json: async () => {
      throw new Error('not json')
    },
  } as Response
}

describe('fetchWithRetry', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  /** Runs a promise to completion while advancing the retry backoff timer. */
  async function resolveWithTimers<T>(promise: Promise<T>): Promise<T> {
    const settled = promise.then(
      (value) => ({ ok: true as const, value }),
      (error) => ({ ok: false as const, error }),
    )
    await vi.runAllTimersAsync()
    const result = await settled
    if (!result.ok) {
      throw result.error
    }
    return result.value
  }

  it('returns the payload without retrying when the first attempt succeeds', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { id: 'a' }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await resolveWithTimers(fetchWithRetry<{ id: string }>('/api/thing'))

    expect(result).toEqual({ id: 'a' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('retries once on a 500 and returns the retry payload', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(500, {}))
      .mockResolvedValueOnce(jsonResponse(200, { id: 'b' }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await resolveWithTimers(fetchWithRetry<{ id: string }>('/api/thing'))

    expect(result).toEqual({ id: 'b' })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('retries once on a network error and returns the retry payload', async () => {
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(jsonResponse(200, { id: 'c' }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await resolveWithTimers(fetchWithRetry<{ id: string }>('/api/thing'))

    expect(result).toEqual({ id: 'c' })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('does not retry a 404', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(404, { message: 'Not found' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(resolveWithTimers(fetchWithRetry('/api/thing'))).rejects.toThrow('Not found')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('makes at most two attempts when the failure persists', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(503, {}))
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      resolveWithTimers(fetchWithRetry('/api/thing', { fallbackMessage: 'Unable to load.' })),
    ).rejects.toThrow('Unable to load.')

    // Guards against a future change to unbounded retry.
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('surfaces the server message in preference to the fallback', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(400, { message: 'Bad request' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      resolveWithTimers(fetchWithRetry('/api/thing', { fallbackMessage: 'Unable to load.' })),
    ).rejects.toThrow('Bad request')
  })

  it('falls back to the caller message when the body carries no usable message', async () => {
    const fetchMock = vi.fn().mockResolvedValue(unparseableResponse(400))
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      resolveWithTimers(fetchWithRetry('/api/thing', { fallbackMessage: 'Unable to load.' })),
    ).rejects.toThrow('Unable to load.')
  })

  it('never surfaces the raw network error text', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      resolveWithTimers(fetchWithRetry('/api/thing', { fallbackMessage: 'Unable to load.' })),
    ).rejects.toThrow('Unable to load.')
  })

  it('does not retry after the caller aborts', async () => {
    const controller = new AbortController()
    controller.abort()
    const abortError = new DOMException('Aborted', 'AbortError')
    const fetchMock = vi.fn().mockRejectedValue(abortError)
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      resolveWithTimers(fetchWithRetry('/api/thing', { signal: controller.signal })),
    ).rejects.toBe(abortError)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
