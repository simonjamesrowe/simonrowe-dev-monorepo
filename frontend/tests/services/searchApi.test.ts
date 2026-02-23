import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { blogSearch, siteSearch } from '../../src/services/searchApi'

describe('searchApi', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('siteSearch', () => {
    it('fetches from /api/search with encoded query', async () => {
      const mockResponse = { blogs: [], jobs: [], skills: [] }
      vi.mocked(fetch).mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockResponse),
      } as Response)

      const result = await siteSearch('java spring')

      expect(fetch).toHaveBeenCalledWith(
        '/api/search?q=java%20spring',
        { signal: undefined },
      )
      expect(result).toEqual(mockResponse)
    })

    it('throws on non-ok response', async () => {
      vi.mocked(fetch).mockResolvedValue({
        ok: false,
        status: 500,
      } as Response)

      await expect(siteSearch('test')).rejects.toThrow('Search request failed')
    })

    it('passes abort signal to fetch', async () => {
      vi.mocked(fetch).mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({}),
      } as Response)

      const controller = new AbortController()
      await siteSearch('test', controller.signal)

      expect(fetch).toHaveBeenCalledWith(
        '/api/search?q=test',
        { signal: controller.signal },
      )
    })
  })

  describe('blogSearch', () => {
    it('fetches from /api/search/blogs with encoded query', async () => {
      const mockResults = [
        { title: 'Blog 1', shortDescription: 'desc', image: null, publishedDate: '2025-01-01', url: '/blogs/1' },
      ]
      vi.mocked(fetch).mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockResults),
      } as Response)

      const result = await blogSearch('spring')

      expect(fetch).toHaveBeenCalledWith(
        '/api/search/blogs?q=spring',
        { signal: undefined },
      )
      expect(result).toEqual(mockResults)
    })

    it('throws on non-ok response', async () => {
      vi.mocked(fetch).mockResolvedValue({
        ok: false,
        status: 400,
      } as Response)

      await expect(blogSearch('x')).rejects.toThrow('Blog search request failed')
    })
  })
})
