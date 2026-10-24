import { act, render, screen, waitFor } from '@testing-library/react'
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

let isAdmin = false
vi.mock('../../../src/auth/useAdminRole', () => ({
  useAdminRole: () => isAdmin,
}))

vi.mock('../../../src/services/snippetsApi', () => ({
  fetchSnippetsFor: vi.fn(),
  fetchSnippetTags: vi.fn(),
  createSnippet: vi.fn(),
  updateSnippet: vi.fn(),
  deleteSnippet: vi.fn(),
}))

vi.mock('../../../src/hooks/useEnsureAuthenticated', () => ({
  useEnsureAuthenticated: () => () => Promise.resolve(false),
}))

import { fetchArticleSummary } from '../../../src/services/articleSummaryApi'
import { fetchBlogById } from '../../../src/services/blogApi'
import { createSnippet, fetchSnippetsFor, fetchSnippetTags } from '../../../src/services/snippetsApi'
import type { Snippet } from '../../../src/types/snippets'

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
  const audio = narrationAudioStub({ track })
  const view = render(
    <MemoryRouter>
      <NarrationAudioStub value={audio}>
        <NowPlayingPanel variant={variant} />
      </NarrationAudioStub>
    </MemoryRouter>,
  )
  return Object.assign(view, { audio })
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

/**
 * Selects from one word of the read-along to another, as a reader drags. Every word is its own
 * span there, so the selection crosses text nodes as it does in a browser.
 */
function selectWords(first: string, last: string) {
  const word = (text: string) =>
    screen.getByText(text, { selector: '.read-along__word' }).firstChild as Text
  const range = document.createRange()
  range.setStart(word(first), 0)
  const end = word(last)
  range.setEnd(end, end.length)
  const selection = document.getSelection()!
  selection.removeAllRanges()
  selection.addRange(range)
  act(() => { document.dispatchEvent(new Event('selectionchange')) })
}

describe('NowPlayingPanel snippets', () => {
  beforeEach(() => {
    isAdmin = true
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
    vi.mocked(fetchSnippetsFor).mockReset().mockResolvedValue([])
    vi.mocked(fetchSnippetTags).mockReset().mockResolvedValue([])
    vi.mocked(createSnippet).mockReset().mockImplementation(async (_token, request) => ({
      id: 's1',
      note: null,
      tags: [],
      inCurrentSummary: true,
      articleAvailable: true,
      ...request,
    }) as unknown as Snippet)
  })

  afterEach(() => {
    isAdmin = false
    document.getSelection()?.removeAllRanges()
    vi.restoreAllMocks()
  })

  it('keeps a passage of the summary being heard as a snippet of that summary', async () => {
    renderPanel(SUMMARY, 'card')
    await screen.findByRole('region', { name: 'Read along' })
    await waitFor(() =>
      expect(fetchSnippetsFor).toHaveBeenCalledWith(expect.any(Function), 'a-1', 'ARTICLE_SUMMARY'))

    selectWords('GenAI', 'conventions.')
    await userEvent.click(await screen.findByRole('button', { name: /Highlight/ }))

    await waitFor(() => expect(createSnippet).toHaveBeenCalledWith(expect.any(Function), {
      sourceType: 'ARTICLE_SUMMARY',
      contentId: 'a-1',
      exact: 'GenAI conventions.',
      prefix: 'Traces follow the ',
      suffix: '',
    }))
    expect(await screen.findByText('Snippet saved')).toBeInTheDocument()
  })

  it('keeps a passage of a post being heard as a snippet of the post', async () => {
    renderPanel(POST, 'card')
    await screen.findByRole('region', { name: 'Read along' })

    selectWords('demo', 'skills')
    await userEvent.click(await screen.findByRole('button', { name: /Highlight/ }))

    await waitFor(() => expect(createSnippet).toHaveBeenCalledWith(expect.any(Function),
      expect.objectContaining({ sourceType: 'BLOG', contentId: 'blog-1', exact: 'demo skills' })))
  })

  it('opens a highlight clicked in the text without moving the audio', async () => {
    vi.mocked(globalThis.fetch).mockResolvedValue(new Response(JSON.stringify({
      version: 1,
      words: ['Traces', 'follow', 'the', 'GenAI', 'conventions.']
        .map((w, index) => ({ w, s: index, n: 0 })),
    }), { status: 200 }))
    vi.mocked(fetchSnippetsFor).mockResolvedValue([{
      id: 's1',
      sourceType: 'ARTICLE_SUMMARY',
      contentId: 'a-1',
      exact: 'GenAI conventions.',
      prefix: 'Traces follow the ',
      suffix: '',
      note: 'For the talk',
      tags: [],
      inCurrentSummary: true,
      articleAvailable: true,
    } as unknown as Snippet])
    const { audio } = renderPanel(SUMMARY, 'card')
    const genai = await screen.findByText('GenAI', { selector: '.read-along__word--timed' })
    await screen.findByText('For the talk')
    // jsdom cannot say where a click lands in the text; a browser can.
    const doc = document as Document & { caretPositionFromPoint?: unknown }
    doc.caretPositionFromPoint = () => ({ offsetNode: genai.firstChild, offset: 1 })
    try {
      await userEvent.click(genai)
    } finally {
      delete doc.caretPositionFromPoint
    }

    expect(await screen.findByRole('dialog', { name: 'Snippet' })).toBeInTheDocument()
    expect(audio.seek).not.toHaveBeenCalled()
  })

  it('offers nothing to anyone but the admin', async () => {
    isAdmin = false
    renderPanel(SUMMARY, 'card')
    await screen.findByRole('region', { name: 'Read along' })

    expect(screen.queryByRole('region', { name: 'Your snippets' })).toBeNull()
    expect(fetchSnippetsFor).not.toHaveBeenCalled()
  })
})
