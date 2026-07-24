import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  addFavourite,
  getFavouriteIds,
  getFavourites,
  removeFavourite,
} from '../../src/services/favouritesApi'

vi.mock('../../src/config/api', () => ({
  API_BASE_URL: '',
}))

const getAccessToken = vi.fn()

function mockFetchOk(body?: unknown) {
  vi.mocked(fetch).mockResolvedValue({
    ok: true,
    json: () => Promise.resolve(body),
  } as Response)
}

describe('favouritesApi', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    getAccessToken.mockReset()
    getAccessToken.mockResolvedValue('test-token')
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('getFavouriteIds', () => {
    it('fetches ids without auth (favourites are public)', async () => {
      mockFetchOk(['a-1', 'a-2'])

      const result = await getFavouriteIds('news')

      expect(fetch).toHaveBeenCalledWith('/api/favourites/news/ids')
      expect(result).toEqual(['a-1', 'a-2'])
    })

    it('uses the events path for events', async () => {
      mockFetchOk([])

      await getFavouriteIds('events')

      expect(fetch).toHaveBeenCalledWith('/api/favourites/events/ids')
    })
  })

  describe('getFavourites', () => {
    it('fetches the paged listing with pagination params and no auth', async () => {
      const page = { content: [], totalElements: 0, totalPages: 0, number: 1, size: 5 }
      mockFetchOk(page)

      const result = await getFavourites('news', 1, 5)

      expect(fetch).toHaveBeenCalledWith('/api/favourites/news?page=1&size=5')
      expect(result).toEqual(page)
    })

    it('defaults to page 0 and size 20', async () => {
      mockFetchOk({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 })

      await getFavourites('events')

      expect(fetch).toHaveBeenCalledWith('/api/favourites/events?page=0&size=20')
    })
  })

  describe('addFavourite', () => {
    it('issues an authenticated PUT', async () => {
      mockFetchOk()

      await addFavourite(getAccessToken, 'news', 'a-1')

      expect(fetch).toHaveBeenCalledWith('/api/favourites/news/a-1', {
        method: 'PUT',
        headers: { Authorization: 'Bearer test-token' },
      })
    })

    it('throws the server message on failure', async () => {
      vi.mocked(fetch).mockResolvedValue({
        ok: false,
        json: () => Promise.resolve({ message: 'Content not found' }),
      } as Response)

      await expect(addFavourite(getAccessToken, 'news', 'missing')).rejects.toThrow(
        'Content not found',
      )
    })
  })

  describe('removeFavourite', () => {
    it('issues an authenticated DELETE', async () => {
      mockFetchOk()

      await removeFavourite(getAccessToken, 'events', 'e-1')

      expect(fetch).toHaveBeenCalledWith('/api/favourites/events/e-1', {
        method: 'DELETE',
        headers: { Authorization: 'Bearer test-token' },
      })
    })

    it('throws a fallback message when the error body is not JSON', async () => {
      vi.mocked(fetch).mockResolvedValue({
        ok: false,
        json: () => Promise.reject(new Error('no body')),
      } as Response)

      await expect(removeFavourite(getAccessToken, 'news', 'a-1')).rejects.toThrow(
        'Request failed.',
      )
    })
  })
})
