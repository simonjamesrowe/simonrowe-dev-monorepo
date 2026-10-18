import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { NowPlayingPanel } from '../../../src/components/playlist/NowPlayingPanel'
import type { NarrationTrack } from '../../../src/types/narrationAudio'
import { NarrationAudioStub } from '../../testUtils/NarrationAudioStub'
import { narrationAudioStub } from '../../testUtils/narrationAudioValue'

vi.mock('../../../src/config/api', () => ({ API_BASE_URL: 'http://backend' }))

vi.mock('../../../src/services/articleSummaryApi', () => ({
  fetchArticleSummary: vi.fn(),
}))

vi.mock('../../../src/services/blogApi', () => ({
  fetchBlogById: vi.fn(),
}))

vi.mock('../../../src/services/playlistApi', () => ({
  fetchPlaylist: vi.fn().mockResolvedValue({ items: [], autoVoice: false }),
}))

vi.mock('../../../src/auth/useAuth', () => ({
  useAuth: () => ({ isAuthenticated: false, getAccessToken: vi.fn(), loginWithPopup: vi.fn() }),
}))

vi.mock('../../../src/hooks/useEnsureAuthenticated', () => ({
  useEnsureAuthenticated: () => () => Promise.resolve(false),
}))

import { fetchArticleSummary } from '../../../src/services/articleSummaryApi'
import { fetchBlogById } from '../../../src/services/blogApi'

const SUMMARY: NarrationTrack = {
  contentType: 'ARTICLE_SUMMARY',
  contentId: 'a-1',
  title: 'Agent observability',
  href: 'https://example.com/a-1',
  external: true,
  audioUrl: '/uploads/narrations/n-1/audio-abc.mp3',
  timingsUrl: '/uploads/narrations/n-1/timings-abc.json',
  durationSeconds: 172,
}

const POST: NarrationTrack = {
  contentType: 'BLOG',
  contentId: 'blog-1',
  title: 'Demo skills',
  href: '/blogs/blog-1',
  audioUrl: '/uploads/narrations/n-2/narration.mp3',
  durationSeconds: 600,
}

function renderPanel(track: NarrationTrack, variant: 'card' | 'sheet') {
  return render(
    <MemoryRouter>
      <NarrationAudioStub value={narrationAudioStub({ track })}>
        <NowPlayingPanel variant={variant} />
      </NarrationAudioStub>
    </MemoryRouter>,
  )
}

describe('NowPlayingPanel read along', () => {
  beforeEach(() => {
    window.localStorage.clear()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ version: 1, words: [] }), { status: 200 }),
    )
    vi.mocked(fetchArticleSummary).mockResolvedValue({
      state: 'READY',
      body: 'Traces follow the GenAI conventions.',
    } as Awaited<ReturnType<typeof fetchArticleSummary>>)
    vi.mocked(fetchBlogById).mockResolvedValue({
      content: 'The two demo skills plan and record a film.',
    } as Awaited<ReturnType<typeof fetchBlogById>>)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('reads along with a summary on the wide card', async () => {
    renderPanel(SUMMARY, 'card')

    expect(await screen.findByRole('heading', { name: 'Read along' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Read along' }))
      .toHaveTextContent('Traces follow the GenAI conventions.')
    expect(fetchArticleSummary).toHaveBeenCalledWith('a-1', expect.anything())
  })

  it('shows the text of a post being heard', async () => {
    renderPanel(POST, 'card')

    expect(await screen.findByRole('region', { name: 'Read along' }))
      .toHaveTextContent('The two demo skills plan and record a film.')
    expect(fetchBlogById).toHaveBeenCalledWith('blog-1')
    // Audio made before read-along has no timings: the text is there, labelled for what it is.
    expect(screen.getByRole('heading', { name: 'The post' })).toBeInTheDocument()
  })

  it('swaps the artwork for the text on a phone, and remembers the choice', async () => {
    const view = renderPanel(SUMMARY, 'sheet')
    const toggle = await screen.findByRole('button', { name: 'Read along' })
    expect(toggle).toHaveAttribute('aria-pressed', 'false')
    expect(screen.queryByRole('region', { name: 'Read along' })).toBeNull()

    await userEvent.click(toggle)

    expect(toggle).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('region', { name: 'Read along' })).toBeInTheDocument()
    expect(window.localStorage.getItem('narration-read-along')).toBe('on')

    view.unmount()
    renderPanel(SUMMARY, 'sheet')
    await waitFor(() =>
      expect(screen.getByRole('region', { name: 'Read along' })).toBeInTheDocument())
  })
})
