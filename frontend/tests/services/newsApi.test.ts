import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchNews } from '../../src/services/newsApi'

vi.mock('../../src/config/api', () => ({
  API_BASE_URL: '',
}))

function requestedUrl(): string {
  return vi.mocked(fetch).mock.calls[0][0] as string
}

describe('newsApi', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ content: [] }),
    } as Response))
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('fetchNews', () => {
    it('sends only paging when nothing is filtered', async () => {
      await fetchNews(0, 24)

      expect(requestedUrl()).toBe('/api/news?page=0&size=24')
    })

    /**
     * Repeated rather than comma-joined: a source name may legitimately contain a comma,
     * and a joined value would split it into two sources that match nothing.
     */
    it('repeats the source parameter once per selected source', async () => {
      await fetchNews(0, 24, { sources: ['Claude Blog', 'Spring Blog'] })

      expect(requestedUrl()).toBe(
        '/api/news?page=0&size=24&source=Claude+Blog&source=Spring+Blog')
    })

    it('sends the search term as q', async () => {
      await fetchNews(1, 24, { sources: [], query: 'ai sdlc' })

      expect(requestedUrl()).toBe('/api/news?page=1&size=24&q=ai+sdlc')
    })

    /** A box holding only spaces is an empty box, not a filter that matches nothing. */
    it('omits a blank search term', async () => {
      await fetchNews(0, 24, { query: '   ' })

      expect(requestedUrl()).toBe('/api/news?page=0&size=24')
    })

    it('trims the search term rather than sending the visitor trailing space', async () => {
      await fetchNews(0, 24, { query: '  sdlc ' })

      expect(requestedUrl()).toBe('/api/news?page=0&size=24&q=sdlc')
    })
  })
})
