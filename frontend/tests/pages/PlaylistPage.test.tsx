import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetPlaylistStore } from '../../src/hooks/playlistStore'
import { PlaylistPage } from '../../src/pages/PlaylistPage'
import type { PlaylistEntry } from '../../src/types/playlist'
import { NarrationAudioStub } from '../testUtils/NarrationAudioStub'
import { narrationAudioStub } from '../testUtils/narrationAudioValue'

vi.mock('../../src/config/api', () => ({ API_BASE_URL: 'http://backend' }))

vi.mock('../../src/services/playlistApi', () => ({
  fetchPlaylist: vi.fn(),
  addToPlaylist: vi.fn().mockResolvedValue(undefined),
  removeFromPlaylist: vi.fn().mockResolvedValue(undefined),
  playNextInPlaylist: vi.fn().mockResolvedValue(undefined),
  requestPlaylistAudio: vi.fn().mockResolvedValue([]),
  updatePlaylistSettings: vi.fn().mockResolvedValue(true),
  clearListened: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('../../src/services/favouritesApi', () => ({
  addFavourite: vi.fn().mockResolvedValue(undefined),
  removeFavourite: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('../../src/services/articleSummaryApi', () => ({
  fetchArticleSummary: vi.fn().mockResolvedValue({ state: 'NOT_REQUESTED' }),
}))

const auth = { isAuthenticated: true }
const getAccessToken = vi.fn(() => Promise.resolve('token'))
vi.mock('../../src/auth/useAuth', () => ({
  useAuth: () => ({ isAuthenticated: auth.isAuthenticated, getAccessToken, loginWithPopup: vi.fn() }),
}))
vi.mock('../../src/hooks/useEnsureAuthenticated', () => ({
  useEnsureAuthenticated: () => () => Promise.resolve(auth.isAuthenticated),
}))

import { addFavourite } from '../../src/services/favouritesApi'
import {
  fetchPlaylist,
  playNextInPlaylist,
  requestPlaylistAudio,
} from '../../src/services/playlistApi'

function entry(id: string, overrides: Partial<PlaylistEntry> = {}): PlaylistEntry {
  return {
    contentType: 'ARTICLE_SUMMARY',
    contentId: id,
    status: 'QUEUED',
    progressSeconds: 0,
    addedVia: 'SUMMARISED',
    addedAt: '2026-10-12T08:00:00Z',
    voiceRequested: false,
    title: `Title ${id}`,
    href: `https://example.com/${id}`,
    external: true,
    sourceName: 'TLDR AI',
    favourite: false,
    summaryState: 'READY',
    audio: { state: 'READY', audioUrl: `/uploads/narrations/${id}/narration.mp3`, durationSeconds: 240 },
    ...overrides,
  }
}

const READY_ONE = entry('a-1', { favourite: true })
const READY_TWO = entry('a-2')
const WAITING = entry('a-3', { audio: { state: 'NOT_REQUESTED' }, estimatedSeconds: 180 })
const WAITING_TOO = entry('a-4', { audio: { state: 'NOT_REQUESTED' }, estimatedSeconds: 240 })
const HEARD = entry('a-5', { status: 'LISTENED', listenedAt: '2026-10-12T07:00:00Z' })

function renderPage(items: PlaylistEntry[], audio = narrationAudioStub()) {
  vi.mocked(fetchPlaylist).mockResolvedValue({ items, autoVoice: false })
  render(
    <MemoryRouter>
      <NarrationAudioStub value={audio}>
        <PlaylistPage />
      </NarrationAudioStub>
    </MemoryRouter>,
  )
  return audio
}

describe('PlaylistPage', () => {
  beforeEach(() => {
    resetPlaylistStore()
    auth.isAuthenticated = true
    vi.mocked(addFavourite).mockClear()
    vi.mocked(playNextInPlaylist).mockClear()
    vi.mocked(requestPlaylistAudio).mockClear()
  })

  afterEach(() => {
    resetPlaylistStore()
  })

  it('sorts the playlist into up next, waiting for audio, and heard', async () => {
    renderPage([READY_ONE, WAITING, READY_TWO, HEARD])

    const queue = await screen.findByRole('region', { name: /Up next/ })
    expect(within(queue).getAllByRole('button', { name: /^Play Title/ })).toHaveLength(2)
    expect(screen.getByRole('region', { name: /Summarised, not yet audio/ }))
      .toHaveTextContent('Title a-3')
    expect(screen.getByText('1 listened')).toBeInTheDocument()
    expect(screen.getByText(/8 min/)).toBeInTheDocument()
  })

  it("shows each item's own date, with the year only when it is not this one", async () => {
    const thisYear = new Date().getFullYear()
    renderPage([
      entry('a-1', { publishedAt: `${thisYear}-10-09T13:25:13Z` }),
      entry('a-2', { publishedAt: `${thisYear - 1}-03-02T09:00:00Z` }),
    ])

    expect(await screen.findByText('Summary · 9 Oct')).toBeInTheDocument()
    expect(screen.getByText(`Summary · 2 Mar ${thisYear - 1}`)).toBeInTheDocument()
  })

  it('plays the whole queue from the row pressed', async () => {
    const audio = renderPage([READY_ONE, READY_TWO])

    await userEvent.click(await screen.findByRole('button', { name: 'Play Title a-2' }))

    expect(audio.playQueue).toHaveBeenCalledWith(
      [expect.objectContaining({ contentId: 'a-1', fromPlaylist: true }),
        expect.objectContaining({ contentId: 'a-2' })],
      1,
    )
  })

  it('hearts an article from its row', async () => {
    renderPage([READY_ONE, READY_TWO])

    const heart = (await screen.findAllByRole('button', { name: 'Add to favourites' }))[0]
    await userEvent.click(heart)

    expect(addFavourite).toHaveBeenCalledWith(getAccessToken, 'news', 'a-2')
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Remove from favourites' }))
      .toHaveLength(2))
  })

  it('puts a row first with "Play next", and says so', async () => {
    renderPage([READY_ONE, READY_TWO])

    await userEvent.click(await screen.findByRole('button', { name: 'More for Title a-2' }))
    await userEvent.click(screen.getByRole('button', { name: 'Play next' }))

    expect(playNextInPlaylist).toHaveBeenCalledWith(
      getAccessToken, expect.objectContaining({ contentId: 'a-2' }))
    const rows = within(screen.getByRole('region', { name: /Up next/ })).getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('Title a-2')
    expect(rows[0]).toHaveTextContent('Plays next')
  })

  it('has the player go to a pinned item next, then on down the list', async () => {
    const pinned = entry('a-9', { pinnedAt: '2026-10-12T09:00:00Z' })
    const audio = narrationAudioStub({
      track: { ...READY_ONE, audioUrl: READY_ONE.audio.audioUrl, fromPlaylist: true },
      playing: true,
    })
    // Server order: the pin first, then by date; a-1 is playing.
    renderPage([pinned, READY_ONE, READY_TWO], audio)

    await waitFor(() => expect(audio.setUpNext).toHaveBeenCalled())
    const following = vi.mocked(audio.setUpNext).mock.calls.at(-1)?.[0]
    expect(following?.map((track) => track.contentId)).toEqual(['a-9', 'a-2'])
  })

  it('makes audio for what is ticked, in one request', async () => {
    renderPage([READY_ONE, WAITING, WAITING_TOO])

    await userEvent.click(await screen.findByRole('checkbox', { name: /Title a-3/ }))
    await userEvent.click(screen.getByRole('checkbox', { name: /Title a-4/ }))
    const make = screen.getByRole('button', { name: 'Make audio for 2 · ~7 min' })
    await userEvent.click(make)

    expect(requestPlaylistAudio).toHaveBeenCalledWith(getAccessToken, [
      expect.objectContaining({ contentId: 'a-3' }),
      expect.objectContaining({ contentId: 'a-4' }),
    ])
  })

  it('says why an item cannot have audio instead of offering it', async () => {
    renderPage([entry('a-6', {
      audio: { state: 'NOT_REQUESTED' }, summaryState: 'FAILED', summaryRetryable: false,
    })])

    expect(await screen.findByText("Can't be summarised")).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  describe('signed out', () => {
    beforeEach(() => {
      auth.isAuthenticated = false
    })

    it('can listen but sees nothing that changes the list', async () => {
      const audio = renderPage([READY_ONE, READY_TWO, WAITING])

      await userEvent.click(await screen.findByRole('button', { name: 'Play Title a-1' }))
      expect(audio.playQueue).toHaveBeenCalled()

      expect(screen.queryByRole('button', { name: /More for/ })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /favourites/ })).not.toBeInTheDocument()
      expect(screen.queryByRole('region', { name: /not yet audio/ })).not.toBeInTheDocument()
      // A heart someone else gave still shows, it just cannot be pressed.
      expect(screen.getByRole('img', { name: 'In favourites' })).toBeInTheDocument()
      expect(screen.getByText(/Anyone can listen/)).toBeInTheDocument()
    })
  })

  it('points somewhere useful when there is nothing on it', async () => {
    renderPage([])

    expect(await screen.findByText('Nothing to listen to yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'News & Events' })).toHaveAttribute('href', '/news-events')
  })

  it('toggles the current row rather than restarting it', async () => {
    const audio = narrationAudioStub({
      track: { ...READY_ONE, audioUrl: READY_ONE.audio.audioUrl, fromPlaylist: true },
      playing: true,
    })
    renderPage([READY_ONE, READY_TWO], audio)

    await userEvent.click(await screen.findByRole('button', { name: 'Pause Title a-1' }))

    expect(audio.togglePlay).toHaveBeenCalledOnce()
    expect(audio.playQueue).not.toHaveBeenCalled()
  })
})
