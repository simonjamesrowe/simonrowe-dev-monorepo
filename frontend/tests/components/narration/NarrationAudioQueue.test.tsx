import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { NarrationAudioProvider } from '../../../src/components/narration/NarrationAudioProvider'
import { useNarrationAudio } from '../../../src/components/narration/useNarrationAudio'
import type { NarrationTrack } from '../../../src/types/narrationAudio'

vi.mock('../../../src/config/api', () => ({ API_BASE_URL: 'http://backend' }))

vi.mock('../../../src/services/narrationApi', () => ({
  fetchReadyNarrations: vi.fn().mockResolvedValue([]),
  fetchNarrationStatus: vi.fn(),
  postArticleSummary: vi.fn(),
  postBlogNarration: vi.fn(),
  postSummaryNarration: vi.fn(),
}))

vi.mock('../../../src/services/articleSummaryApi', () => ({ fetchArticleSummary: vi.fn() }))

vi.mock('../../../src/services/playlistApi', () => ({
  fetchPlaylist: vi.fn().mockResolvedValue({ items: [], autoVoice: false }),
  markPlaylistListened: vi.fn().mockResolvedValue(undefined),
  savePlaylistProgress: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('../../../src/hooks/useEnsureAuthenticated', () => ({
  useEnsureAuthenticated: () => () => Promise.resolve(true),
}))

const auth = { isAuthenticated: true }
const getAccessToken = vi.fn(() => Promise.resolve('token'))
vi.mock('../../../src/auth/useAuth', () => ({
  useAuth: () => ({ isAuthenticated: auth.isAuthenticated, getAccessToken, loginWithPopup: vi.fn() }),
}))

import { markPlaylistListened, savePlaylistProgress } from '../../../src/services/playlistApi'

type FakeAudio = HTMLAudioElement & { play: ReturnType<typeof vi.fn>; pause: ReturnType<typeof vi.fn> }
let audioElements: FakeAudio[] = []

function makeFakeAudio(): FakeAudio {
  const audio = document.createElement('audio') as FakeAudio
  let paused = true
  let time = 0
  let length = 300
  Object.defineProperty(audio, 'paused', { get: () => paused })
  Object.defineProperty(audio, 'currentTime', { get: () => time, set: (value: number) => { time = value } })
  Object.defineProperty(audio, 'duration', { get: () => length, set: (value: number) => { length = value } })
  audio.play = vi.fn(() => {
    paused = false
    audio.dispatchEvent(new Event('play'))
    return Promise.resolve()
  })
  audio.pause = vi.fn(() => {
    paused = true
    audio.dispatchEvent(new Event('pause'))
  })
  return audio
}

function track(id: string, extra: Partial<NarrationTrack> = {}): NarrationTrack {
  return {
    contentType: 'ARTICLE_SUMMARY',
    contentId: id,
    title: `Article ${id}`,
    href: `https://example.com/${id}`,
    external: true,
    audioUrl: `/uploads/narrations/${id}/narration.mp3`,
    durationSeconds: 300,
    fromPlaylist: true,
    ...extra,
  }
}

const LIST = [track('a'), track('b', { resumeAt: 120 }), track('c')]

function Probe() {
  const { track: current, upNext, playQueue, next, previous, setJourney, notice } = useNarrationAudio()
  return (
    <div>
      <span data-testid="track">{current?.contentId ?? 'none'}</span>
      <span data-testid="up-next">{upNext.map((item) => item.contentId).join(',')}</span>
      <span data-testid="notice">{notice ?? 'none'}</span>
      <button onClick={() => playQueue(LIST, 0)} type="button">play list</button>
      <button onClick={() => playQueue(LIST, 1)} type="button">play second</button>
      <button onClick={next} type="button">next</button>
      <button onClick={previous} type="button">previous</button>
      <button onClick={() => setJourney(20)} type="button">journey</button>
    </div>
  )
}

function audio(): FakeAudio {
  return audioElements[audioElements.length - 1]
}

describe('NarrationAudioProvider playing a list', () => {
  beforeEach(() => {
    audioElements = []
    auth.isAuthenticated = true
    vi.stubGlobal('Audio', vi.fn(() => {
      const element = makeFakeAudio()
      audioElements.push(element)
      return element
    }))
    vi.mocked(markPlaylistListened).mockClear()
    vi.mocked(savePlaylistProgress).mockClear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('plays from the chosen track and holds the rest as up next', async () => {
    render(<NarrationAudioProvider><Probe /></NarrationAudioProvider>)

    await userEvent.click(screen.getByRole('button', { name: 'play list' }))

    expect(screen.getByTestId('track')).toHaveTextContent('a')
    expect(screen.getByTestId('up-next')).toHaveTextContent('b,c')
    expect(audio().src).toBe('http://backend/uploads/narrations/a/narration.mp3')
    expect(audio().play).toHaveBeenCalled()
  })

  it('records a finished track as heard and carries on to the next', async () => {
    render(<NarrationAudioProvider><Probe /></NarrationAudioProvider>)
    await userEvent.click(screen.getByRole('button', { name: 'play list' }))

    act(() => { audio().dispatchEvent(new Event('ended')) })

    expect(screen.getByTestId('track')).toHaveTextContent('b')
    expect(screen.getByTestId('up-next')).toHaveTextContent('c')
    expect(markPlaylistListened).toHaveBeenCalledWith(
      getAccessToken, expect.objectContaining({ contentId: 'a' }))
  })

  // Anyone can play the playlist, but only a signed-in listener moves its shared state.
  it('does not mark anything heard for a signed-out listener', async () => {
    auth.isAuthenticated = false
    render(<NarrationAudioProvider><Probe /></NarrationAudioProvider>)
    await userEvent.click(screen.getByRole('button', { name: 'play list' }))

    act(() => {
      audio().currentTime = 40
      audio().pause()
      audio().dispatchEvent(new Event('ended'))
    })

    expect(markPlaylistListened).not.toHaveBeenCalled()
    expect(savePlaylistProgress).not.toHaveBeenCalled()
    expect(screen.getByTestId('track')).toHaveTextContent('b')
  })

  it('resumes a part-heard track where it was left', async () => {
    render(<NarrationAudioProvider><Probe /></NarrationAudioProvider>)

    await userEvent.click(screen.getByRole('button', { name: 'play second' }))
    act(() => { audio().dispatchEvent(new Event('loadedmetadata')) })

    expect(audio().currentTime).toBe(120)
  })

  it('saves the position when paused part way through', async () => {
    render(<NarrationAudioProvider><Probe /></NarrationAudioProvider>)
    await userEvent.click(screen.getByRole('button', { name: 'play list' }))

    act(() => {
      audio().currentTime = 42
      audio().pause()
    })

    expect(savePlaylistProgress).toHaveBeenCalledWith(
      getAccessToken, expect.objectContaining({ contentId: 'a' }), 42)
  })

  it('goes back to the previous track only when near the start of this one', async () => {
    render(<NarrationAudioProvider><Probe /></NarrationAudioProvider>)
    await userEvent.click(screen.getByRole('button', { name: 'play list' }))
    await userEvent.click(screen.getByRole('button', { name: 'next' }))
    expect(screen.getByTestId('track')).toHaveTextContent('b')

    act(() => { audio().currentTime = 30 })
    await userEvent.click(screen.getByRole('button', { name: 'previous' }))
    expect(screen.getByTestId('track')).toHaveTextContent('b')
    expect(audio().currentTime).toBe(0)

    await userEvent.click(screen.getByRole('button', { name: 'previous' }))
    expect(screen.getByTestId('track')).toHaveTextContent('a')
    expect(screen.getByTestId('up-next')).toHaveTextContent('b,c')
  })

  it('pauses when the journey is up, and says so', async () => {
    render(<NarrationAudioProvider><Probe /></NarrationAudioProvider>)
    await userEvent.click(screen.getByRole('button', { name: 'play list' }))
    const start = Date.now()
    await userEvent.click(screen.getByRole('button', { name: 'journey' }))

    const clock = vi.spyOn(Date, 'now').mockReturnValue(start + 21 * 60_000)
    act(() => { audio().dispatchEvent(new Event('timeupdate')) })

    await waitFor(() => expect(audio().pause).toHaveBeenCalled())
    expect(screen.getByTestId('notice')).toHaveTextContent("Your journey's up")
    clock.mockRestore()
  })
})
