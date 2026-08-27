import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  READY_FALLBACK_MESSAGE,
  fetchReadyNarrations,
} from '../../src/services/narrationApi'

vi.mock('../../src/config/api', () => ({
  API_BASE_URL: '',
}))

const readyBlogs = [
  { contentId: 'blog-1', audioUrl: '/uploads/narrations/aaa/narration.mp3', durationSeconds: 734 },
  { contentId: 'blog-2', audioUrl: '/uploads/narrations/bbb/narration.mp3', durationSeconds: 412 },
]

function mockFetchOk(body: unknown) {
  vi.mocked(fetch).mockResolvedValue({
    ok: true,
    json: () => Promise.resolve(body),
  } as Response)
}

describe('fetchReadyNarrations', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('requests the content type and returns the parsed rows, with no auth header', async () => {
    mockFetchOk(readyBlogs)

    const result = await fetchReadyNarrations('BLOG')

    expect(fetch).toHaveBeenCalledWith('/api/narrations/ready?contentType=BLOG', {
      headers: { Accept: 'application/json' },
      signal: undefined,
    })
    expect(result).toEqual(readyBlogs)
  })

  it('requests article summaries with their own content type', async () => {
    mockFetchOk([])

    await fetchReadyNarrations('ARTICLE_SUMMARY')

    expect(fetch).toHaveBeenCalledWith(
      '/api/narrations/ready?contentType=ARTICLE_SUMMARY',
      expect.anything(),
    )
  })

  it('treats an empty array as a normal answer, not an error', async () => {
    mockFetchOk([])

    await expect(fetchReadyNarrations('BLOG')).resolves.toEqual([])
  })

  it('rejects with the fallback message on a non-ok response', async () => {
    vi.mocked(fetch).mockResolvedValue({ ok: false, status: 500 } as Response)

    await expect(fetchReadyNarrations('BLOG')).rejects.toThrow(READY_FALLBACK_MESSAGE)
  })

  // A network failure must not leak "TypeError: Failed to fetch" towards the UI.
  it('rejects with an Error rather than the raw network failure', async () => {
    vi.mocked(fetch).mockRejectedValue(new TypeError('Failed to fetch'))

    await expect(fetchReadyNarrations('BLOG')).rejects.toThrow(READY_FALLBACK_MESSAGE)
  })

  it('rejects when the body is not an array', async () => {
    mockFetchOk({ nope: true })

    await expect(fetchReadyNarrations('BLOG')).rejects.toThrow(READY_FALLBACK_MESSAGE)
  })

  it('rethrows an abort so callers can distinguish it from a real failure', async () => {
    const controller = new AbortController()
    const abortError = new DOMException('Aborted', 'AbortError')
    vi.mocked(fetch).mockRejectedValue(abortError)
    controller.abort()

    await expect(fetchReadyNarrations('BLOG', controller.signal)).rejects.toBe(abortError)
  })
})
