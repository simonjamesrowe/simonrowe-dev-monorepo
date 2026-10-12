import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../src/config/api', () => ({ API_BASE_URL: 'http://backend' }))

import {
  addToPlaylist,
  fetchPlaylist,
  playNextInPlaylist,
  requestPlaylistAudio,
  savePlaylistProgress,
} from '../../src/services/playlistApi'

const getAccessToken = () => Promise.resolve('token-1')

function respond(status: number, body?: unknown, headers: Record<string, string> = {}) {
  return new Response(body === undefined ? null : JSON.stringify(body), { status, headers })
}

describe('playlistApi', () => {
  const fetchMock = vi.fn<typeof fetch>()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('reads the playlist without a token', async () => {
    fetchMock.mockResolvedValue(respond(200, { items: [], autoVoice: true }))

    await expect(fetchPlaylist()).resolves.toEqual({ items: [], autoVoice: true })

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('http://backend/api/playlist')
    expect(new Headers(init?.headers).get('Authorization')).toBeNull()
  })

  it('reports a failed read in words, not as a fetch error', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))

    await expect(fetchPlaylist()).rejects.toThrow('The playlist could not be loaded.')
  })

  it('names an item by its type segment and id, with the bearer token', async () => {
    fetchMock.mockResolvedValue(respond(204))

    await addToPlaylist(getAccessToken, { contentType: 'BLOG', contentId: 'b 1' })

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('http://backend/api/playlist/items/blogs/b%201')
    expect(init?.method).toBe('PUT')
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer token-1')
  })

  it('pins an item to play next', async () => {
    fetchMock.mockResolvedValue(respond(204))

    await playNextInPlaylist(getAccessToken, { contentType: 'ARTICLE_SUMMARY', contentId: 'a-1' })

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('http://backend/api/playlist/items/articles/a-1/play-next')
    expect(init?.method).toBe('POST')
  })

  it('sends only each item key when asking for audio', async () => {
    fetchMock.mockResolvedValue(respond(200, { results: [] }))
    const entry = { contentType: 'ARTICLE_SUMMARY' as const, contentId: 'a-1', title: 'extra' }

    await requestPlaylistAudio(getAccessToken, [entry])

    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({
      items: [{ contentType: 'ARTICLE_SUMMARY', contentId: 'a-1' }],
    })
  })

  it('rounds a saved position to a tenth of a second', async () => {
    fetchMock.mockResolvedValue(respond(204))

    await savePlaylistProgress(getAccessToken, { contentType: 'BLOG', contentId: 'b-1' }, 12.3456)

    expect(fetchMock.mock.calls[0][0]).toBe('http://backend/api/playlist/items/blogs/b-1/progress')
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({ positionSeconds: 12.3 })
  })

  it('returns each item\'s outcome from "make audio"', async () => {
    const results = [{ contentType: 'BLOG', contentId: 'b-1', outcome: 'QUEUED' }]
    fetchMock.mockResolvedValue(respond(200, { results }))

    await expect(requestPlaylistAudio(getAccessToken, [{ contentType: 'BLOG', contentId: 'b-1' }]))
      .resolves.toEqual(results)
  })

  it('passes on how long to wait when rate limited', async () => {
    fetchMock.mockResolvedValue(respond(429, { error: 'Rate limit exceeded' }, { 'Retry-After': '42' }))

    await expect(requestPlaylistAudio(getAccessToken, [{ contentType: 'BLOG', contentId: 'b-1' }]))
      .rejects.toThrow('Too many requests. Try again in 42 seconds.')
  })
})
