import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { NarrationAudioProvider } from '../../../src/components/narration/NarrationAudioProvider'
import { useNarrationAudio } from '../../../src/components/narration/useNarrationAudio'

vi.mock('../../../src/config/api', () => ({ API_BASE_URL: 'http://backend' }))

vi.mock('../../../src/services/narrationApi', () => ({
  fetchReadyNarrations: vi.fn(),
  fetchNarrationStatus: vi.fn(),
  postArticleSummary: vi.fn(),
  postBlogNarration: vi.fn(),
  postSummaryNarration: vi.fn(),
  READY_FALLBACK_MESSAGE: 'Audio availability could not be checked.',
  CHAIN_FALLBACK_MESSAGE: 'Audio could not be prepared. Please try again.',
}))

vi.mock('../../../src/services/articleSummaryApi', () => ({
  fetchArticleSummary: vi.fn(),
}))

// The sign-in gate is a switch here; `useEnsureAuthenticated` has its own tests.
const ensureAuthenticated = vi.fn<() => Promise<boolean>>()

vi.mock('../../../src/hooks/useEnsureAuthenticated', () => ({
  useEnsureAuthenticated: () => ensureAuthenticated,
}))

const getAccessToken = vi.fn<() => Promise<string>>()

vi.mock('../../../src/auth/useAuth', () => ({
  useAuth: () => ({ isAuthenticated: true, getAccessToken, loginWithPopup: vi.fn() }),
}))

import { fetchArticleSummary } from '../../../src/services/articleSummaryApi'
import {
  fetchNarrationStatus,
  fetchReadyNarrations,
  postArticleSummary,
  postBlogNarration,
  postSummaryNarration,
} from '../../../src/services/narrationApi'

const readyBlog = {
  contentId: 'blog-1',
  audioUrl: '/uploads/narrations/aaa/narration.mp3',
  durationSeconds: 734,
}

/** The elements the provider created, captured so tests can assert on real playback state. */
let audioElements: FakeAudio[] = []

type FakeAudio = HTMLAudioElement & {
  play: ReturnType<typeof vi.fn>
  pause: ReturnType<typeof vi.fn>
}

/**
 * A real `<audio>` element with `play`/`pause` spied.
 *
 * jsdom does not implement media playback, but it has to be a genuine element rather than a bare
 * `EventTarget`: the provider appends it to `<body>` precisely so a document-wide audio query can
 * find it, and a stub that is not a `Node` would hide that from the tests.
 */
function makeFakeAudio(): FakeAudio {
  const audio = document.createElement('audio') as FakeAudio
  let paused = true
  Object.defineProperty(audio, 'paused', { get: () => paused })
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

/** Exercises the provider through the same context surface a card and the bar use. */
function Probe({ contentType = 'BLOG' as const, contentId = 'blog-1' }) {
  const {
    readyFor, stageFor, listen, track, stage, playing, error, delayed, dismiss, lastCompleted,
  } = useNarrationAudio()
  const ready = readyFor(contentType, contentId)

  return (
    <div>
      <span data-testid="ready">{ready ? String(ready.durationSeconds) : 'none'}</span>
      <span data-testid="stage">{stage}</span>
      <span data-testid="card-stage">{stageFor(contentType, contentId)}</span>
      <span data-testid="other-card-stage">{stageFor(contentType, 'other')}</span>
      <span data-testid="track">{track?.contentId ?? 'none'}</span>
      <span data-testid="playing">{String(playing)}</span>
      <span data-testid="error">{error?.message ?? 'none'}</span>
      <span data-testid="retryable">{error ? String(error.retryable) : 'none'}</span>
      <span data-testid="delayed">{String(delayed)}</span>
      <span data-testid="completed">
        {lastCompleted
          ? `${lastCompleted.contentId}:${String(lastCompleted.summaryWasGenerated)}`
          : 'none'}
      </span>
      <button
        onClick={() => listen({
          contentType,
          contentId,
          title: 'Kafka Without Surprises',
          href: '/blogs/blog-1',
        })}
        type="button"
      >
        listen
      </button>
      <button
        onClick={() => listen({
          contentType,
          contentId: 'other',
          title: 'Another post',
          href: '/blogs/other',
        })}
        type="button"
      >
        listen other
      </button>
      <button onClick={dismiss} type="button">dismiss</button>
    </div>
  )
}

function renderProvider(props: Partial<React.ComponentProps<typeof Probe>> = {}) {
  return render(
    <NarrationAudioProvider>
      <Probe {...props} />
    </NarrationAudioProvider>,
  )
}

describe('NarrationAudioProvider', () => {
  beforeEach(() => {
    audioElements = []
    vi.stubGlobal('Audio', vi.fn(() => {
      const audio = makeFakeAudio()
      audioElements.push(audio)
      return audio
    }))
    vi.mocked(fetchReadyNarrations).mockReset()
    vi.mocked(fetchReadyNarrations).mockResolvedValue([])
    vi.mocked(fetchNarrationStatus).mockReset()
    vi.mocked(postArticleSummary).mockReset()
    vi.mocked(postBlogNarration).mockReset()
    vi.mocked(postSummaryNarration).mockReset()
    vi.mocked(fetchArticleSummary).mockReset()
    ensureAuthenticated.mockReset()
    ensureAuthenticated.mockResolvedValue(true)
    getAccessToken.mockReset()
    getAccessToken.mockResolvedValue('test-token')
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('the bulk ready lookup', () => {
    // One call per content type per mount — and because the provider sits above <Routes>, that
    // is once per page load rather than once per navigation.
    it('reads both content types exactly once on mount', async () => {
      renderProvider()

      await waitFor(() => {
        expect(fetchReadyNarrations).toHaveBeenCalledTimes(2)
      })
      const requested = vi.mocked(fetchReadyNarrations).mock.calls.map(([type]) => type)
      expect(requested).toEqual(['BLOG', 'ARTICLE_SUMMARY'])
    })

    it('exposes what it read, keyed by content type and id', async () => {
      vi.mocked(fetchReadyNarrations).mockImplementation((contentType) =>
        Promise.resolve(contentType === 'BLOG' ? [readyBlog] : []))

      renderProvider()

      await waitFor(() => {
        expect(screen.getByTestId('ready')).toHaveTextContent('734')
      })
    })

    // A failed availability check must never stop a listing rendering or surface an error.
    it('leaves every item cold and reports nothing when the lookup fails', async () => {
      vi.mocked(fetchReadyNarrations).mockRejectedValue(new Error('boom'))

      renderProvider()

      await waitFor(() => {
        expect(fetchReadyNarrations).toHaveBeenCalledTimes(2)
      })
      expect(screen.getByTestId('ready')).toHaveTextContent('none')
      expect(screen.getByTestId('stage')).toHaveTextContent('idle')
    })
  })

  describe('playing audio that already exists', () => {
    beforeEach(() => {
      vi.mocked(fetchReadyNarrations).mockImplementation((contentType) =>
        Promise.resolve(contentType === 'BLOG' ? [readyBlog] : []))
    })

    it('starts immediately with no further network call', async () => {
      renderProvider()
      await waitFor(() => {
        expect(screen.getByTestId('ready')).toHaveTextContent('734')
      })

      await userEvent.click(screen.getByRole('button', { name: 'listen' }))

      expect(screen.getByTestId('stage')).toHaveTextContent('ready')
      expect(screen.getByTestId('track')).toHaveTextContent('blog-1')
      expect(screen.getByTestId('playing')).toHaveTextContent('true')
      // Still just the two mount-time reads: pressing a ready card costs no request at all.
      expect(fetchReadyNarrations).toHaveBeenCalledTimes(2)
    })

    it('loads the audio from the backend origin', async () => {
      renderProvider()
      await waitFor(() => {
        expect(screen.getByTestId('ready')).toHaveTextContent('734')
      })

      await userEvent.click(screen.getByRole('button', { name: 'listen' }))

      expect(audioElements).toHaveLength(1)
      expect(audioElements[0].src)
        .toBe('http://backend/uploads/narrations/aaa/narration.mp3')
      expect(audioElements[0].play).toHaveBeenCalledOnce()
    })

    // A card renders its own in-flight state from provider state keyed on its content id, so a
    // list re-render cannot lose it. An unrelated card must stay idle.
    it('reports the stage only for the item that is the current track', async () => {
      renderProvider()
      await waitFor(() => {
        expect(screen.getByTestId('ready')).toHaveTextContent('734')
      })

      expect(screen.getByTestId('card-stage')).toHaveTextContent('idle')
      await userEvent.click(screen.getByRole('button', { name: 'listen' }))
      expect(screen.getByTestId('card-stage')).toHaveTextContent('ready')
    })

    it('stops playback and clears the track when dismissed', async () => {
      renderProvider()
      await waitFor(() => {
        expect(screen.getByTestId('ready')).toHaveTextContent('734')
      })
      await userEvent.click(screen.getByRole('button', { name: 'listen' }))

      await userEvent.click(screen.getByRole('button', { name: 'dismiss' }))

      expect(screen.getByTestId('track')).toHaveTextContent('none')
      expect(screen.getByTestId('stage')).toHaveTextContent('idle')
      expect(screen.getByTestId('playing')).toHaveTextContent('false')
      expect(audioElements[0].pause).toHaveBeenCalled()
    })

    /**
     * The provider's element is detached but still a real `<audio>` to
     * `document.querySelectorAll`, which is what keeps `NarrationPanel`'s "pause the others"
     * behaviour working in both directions. This asserts the provider's half of that.
     */
    it('pauses any other audio element on the page when it starts', async () => {
      const other = document.createElement('audio')
      other.pause = vi.fn()
      document.body.appendChild(other)

      renderProvider()
      await waitFor(() => {
        expect(screen.getByTestId('ready')).toHaveTextContent('734')
      })

      await userEvent.click(screen.getByRole('button', { name: 'listen' }))

      expect(other.pause).toHaveBeenCalled()
      document.body.removeChild(other)
    })

    /**
     * The other half of that, and the reason the element lives in `<body>` rather than being
     * fully detached: `document.querySelectorAll('audio')` only walks the document, so a
     * detached element would be invisible to `NarrationPanel`'s pause-the-others handler and the
     * bar could end up talking over a detail-page player.
     */
    it('is reachable by a document-wide audio query, so the panel can pause it', async () => {
      renderProvider()
      await waitFor(() => {
        expect(screen.getByTestId('ready')).toHaveTextContent('734')
      })
      await userEvent.click(screen.getByRole('button', { name: 'listen' }))

      const found = Array.from(document.querySelectorAll('audio'))
      expect(found).toContain(audioElements[0])

      // Exactly what NarrationPanel's handler does when its own player starts.
      await act(async () => {
        document.querySelectorAll('audio').forEach((audio) => audio.pause())
      })
      expect(audioElements[0].pause).toHaveBeenCalled()
      expect(screen.getByTestId('playing')).toHaveTextContent('false')
    })

    it('removes its element from the document on unmount', async () => {
      const { unmount } = renderProvider()
      await waitFor(() => {
        expect(screen.getByTestId('ready')).toHaveTextContent('734')
      })
      await userEvent.click(screen.getByRole('button', { name: 'listen' }))
      expect(document.querySelectorAll('audio')).toHaveLength(1)

      unmount()

      expect(document.querySelectorAll('audio')).toHaveLength(0)
    })
  })

  describe('the generation chain', () => {
    const queued = { state: 'QUEUED', version: 1, retryable: false, message: 'Queued' } as const
    const readyResponse = {
      state: 'READY',
      version: 3,
      retryable: false,
      message: 'Ready',
      audioUrl: '/uploads/narrations/new/narration.mp3',
      durationSeconds: 300,
    } as const

    describe('sign-in', () => {
      // A reader who changes their mind must never trigger a paid call, and must never be told
      // off for it either.
      it('issues no request and shows no error when the popup is dismissed', async () => {
        ensureAuthenticated.mockResolvedValue(false)

        renderProvider()
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))

        expect(postBlogNarration).not.toHaveBeenCalled()
        expect(screen.getByTestId('error')).toHaveTextContent('none')
        expect(screen.getByTestId('stage')).toHaveTextContent('idle')
      })
    })

    describe('a blog with no audio', () => {
      it('signs in, requests narration, polls and auto-plays', async () => {
        vi.mocked(postBlogNarration).mockResolvedValue({ ok: true, value: queued })
        vi.mocked(fetchNarrationStatus).mockResolvedValue(readyResponse)

        renderProvider()
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))

        await waitFor(() => {
          expect(screen.getByTestId('stage')).toHaveTextContent('ready')
        })
        expect(ensureAuthenticated).toHaveBeenCalledOnce()
        expect(postBlogNarration).toHaveBeenCalledWith(
          getAccessToken, 'blog-1', expect.any(AbortSignal))
        // The narration POST is the only write: no summary step for a blog.
        expect(postArticleSummary).not.toHaveBeenCalled()
        expect(screen.getByTestId('playing')).toHaveTextContent('true')
      })

      // The polling policy is imported from useNarration rather than reinvented.
      it('long-polls with the shared waitSeconds bound', async () => {
        vi.mocked(postBlogNarration).mockResolvedValue({ ok: true, value: queued })
        vi.mocked(fetchNarrationStatus).mockResolvedValue(readyResponse)

        renderProvider()
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))

        await waitFor(() => expect(fetchNarrationStatus).toHaveBeenCalled())
        expect(fetchNarrationStatus).toHaveBeenCalledWith('BLOG', 'blog-1', {
          afterVersion: 1,
          waitSeconds: 25,
          signal: expect.any(AbortSignal),
        })
      })

      it('plays straight away when the POST reuses an existing narration', async () => {
        vi.mocked(postBlogNarration).mockResolvedValue({ ok: true, value: readyResponse })

        renderProvider()
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))

        await waitFor(() => {
          expect(screen.getByTestId('stage')).toHaveTextContent('ready')
        })
        expect(fetchNarrationStatus).not.toHaveBeenCalled()
      })

      it('records the finished audio so the card flips to a duration', async () => {
        vi.mocked(postBlogNarration).mockResolvedValue({ ok: true, value: readyResponse })

        renderProvider()
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        expect(screen.getByTestId('ready')).toHaveTextContent('none')

        await userEvent.click(screen.getByRole('button', { name: 'listen' }))

        await waitFor(() => {
          expect(screen.getByTestId('ready')).toHaveTextContent('300')
        })
      })

      // Only this card shows a stage; the rest of the list stays idle.
      it('reports the in-flight stage against the item being generated', async () => {
        let release: (value: { ok: true; value: typeof queued }) => void = () => {}
        vi.mocked(postBlogNarration).mockImplementation(() => new Promise((resolve) => {
          release = resolve
        }))

        renderProvider()
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))

        await waitFor(() => {
          expect(screen.getByTestId('card-stage')).toHaveTextContent('narrating')
        })
        expect(screen.getByTestId('other-card-stage')).toHaveTextContent('idle')
        // Let the pending promise resolve inside act, so unmount is not racing a state update.
        await act(async () => { release({ ok: true, value: queued }) })
      })
    })

    describe('a news article', () => {
      it('narrates an existing summary without regenerating it', async () => {
        vi.mocked(fetchArticleSummary).mockResolvedValue({
          state: 'READY', version: 2, retryable: false, message: '', body: 'Prose.',
        })
        vi.mocked(postSummaryNarration).mockResolvedValue({ ok: true, value: readyResponse })

        renderProvider({ contentType: 'ARTICLE_SUMMARY', contentId: 'article-9' })
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))

        await waitFor(() => {
          expect(screen.getByTestId('stage')).toHaveTextContent('ready')
        })
        expect(postArticleSummary).not.toHaveBeenCalled()
        expect(postSummaryNarration).toHaveBeenCalledWith(
          getAccessToken, 'article-9', expect.any(AbortSignal))
        // No summary was generated, so the news page has nothing to flip.
        expect(screen.getByTestId('completed')).toHaveTextContent('article-9:false')
      })

      it('summarises first when there is no summary, then narrates', async () => {
        vi.mocked(fetchArticleSummary).mockResolvedValue({
          state: 'NOT_REQUESTED', version: 0, retryable: false, message: '',
        })
        vi.mocked(postArticleSummary).mockResolvedValue({
          ok: true,
          value: { state: 'READY', version: 1, retryable: false, message: '', body: 'Prose.' },
        })
        vi.mocked(postSummaryNarration).mockResolvedValue({ ok: true, value: readyResponse })

        renderProvider({ contentType: 'ARTICLE_SUMMARY', contentId: 'article-9' })
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))

        await waitFor(() => {
          expect(screen.getByTestId('stage')).toHaveTextContent('ready')
        })
        expect(postArticleSummary).toHaveBeenCalledWith(
          getAccessToken, 'article-9', expect.any(AbortSignal))
        expect(postSummaryNarration).toHaveBeenCalledOnce()
        // Published so the news page can flip the card's summary control.
        expect(screen.getByTestId('completed')).toHaveTextContent('article-9:true')
      })

      it('passes through the summarising stage on its way to narrating', async () => {
        vi.mocked(fetchArticleSummary).mockResolvedValue({
          state: 'NOT_REQUESTED', version: 0, retryable: false, message: '',
        })
        let releaseSummary: (value: unknown) => void = () => {}
        vi.mocked(postArticleSummary).mockImplementation(() => new Promise((resolve) => {
          releaseSummary = resolve as (value: unknown) => void
        }))

        renderProvider({ contentType: 'ARTICLE_SUMMARY', contentId: 'article-9' })
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))

        await waitFor(() => {
          expect(screen.getByTestId('stage')).toHaveTextContent('summarising')
        })
        await act(async () => {
          releaseSummary({
            ok: true,
            value: { state: 'FAILED', version: 1, retryable: true, message: 'nope',
              failureCode: 'MODEL_ERROR' },
          })
        })
      })
    })

    describe('switching tracks', () => {
      it('abandons the chain in flight when a different item is pressed', async () => {
        vi.mocked(postBlogNarration).mockResolvedValue({ ok: true, value: queued })
        let firstSignal: AbortSignal | undefined
        vi.mocked(fetchNarrationStatus).mockImplementation((_type, contentId, options) => {
          if (contentId === 'blog-1') {
            firstSignal = options?.signal
            return new Promise(() => {})
          }
          return Promise.resolve(readyResponse)
        })

        renderProvider()
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))
        await waitFor(() => expect(firstSignal).toBeDefined())

        await userEvent.click(screen.getByRole('button', { name: 'listen other' }))

        expect(firstSignal!.aborted).toBe(true)
        await waitFor(() => {
          expect(screen.getByTestId('track')).toHaveTextContent('other')
        })
      })
    })

    /**
     * The load-bearing case for "dismissing the bar mid-generation is safe": the work is already
     * paid for and cannot be cancelled, so it still completes and the card still becomes
     * playable — it just must not start talking at someone who closed the player.
     */
    describe('dismissing mid-chain', () => {
      it('suppresses auto-play but still marks the track ready', async () => {
        vi.mocked(postBlogNarration).mockResolvedValue({ ok: true, value: queued })
        let settle: (value: typeof readyResponse) => void = () => {}
        vi.mocked(fetchNarrationStatus).mockImplementation(() => new Promise((resolve) => {
          settle = resolve as (value: typeof readyResponse) => void
        }))

        renderProvider()
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))
        await waitFor(() => {
          expect(screen.getByTestId('stage')).toHaveTextContent('narrating')
        })

        await userEvent.click(screen.getByRole('button', { name: 'dismiss' }))
        expect(screen.getByTestId('track')).toHaveTextContent('none')

        await act(async () => { settle(readyResponse) })

        // Nothing auto-plays into a bar the reader closed...
        expect(screen.getByTestId('track')).toHaveTextContent('none')
        expect(screen.getByTestId('playing')).toHaveTextContent('false')
        // ...but the audio is still recorded, so the card flips to its duration with no
        // page reload. This is what makes dismissing mid-generation safe.
        expect(screen.getByTestId('ready')).toHaveTextContent('300')
        expect(screen.getByTestId('completed')).toHaveTextContent('blog-1:false')
      })

      // A closed bar has nowhere to show a failure, and reviving it to complain about work the
      // reader walked away from would be worse than silence.
      it('stays silent when a chain the reader closed goes on to fail', async () => {
        vi.mocked(postBlogNarration).mockResolvedValue({ ok: true, value: queued })
        let settle: (value: unknown) => void = () => {}
        vi.mocked(fetchNarrationStatus).mockImplementation(() => new Promise((resolve) => {
          settle = resolve as (value: unknown) => void
        }))

        renderProvider()
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))
        await waitFor(() => {
          expect(screen.getByTestId('stage')).toHaveTextContent('narrating')
        })
        await userEvent.click(screen.getByRole('button', { name: 'dismiss' }))

        await act(async () => {
          settle({
            state: 'FAILED', version: 4, retryable: true, message: 'Audio could not be prepared',
          })
        })

        expect(screen.getByTestId('error')).toHaveTextContent('none')
        expect(screen.getByTestId('track')).toHaveTextContent('none')
      })
    })

    /**
     * Found by hand against restored production data. Starting a chain used to leave the
     * previous track audible while the bar relabelled itself to the new item.
     */
    describe('switching from a playing track to one that needs generating', () => {
      it('stops the previous audio and clears it from the element', async () => {
        vi.mocked(fetchReadyNarrations).mockImplementation((contentType) =>
          Promise.resolve(contentType === 'BLOG' ? [readyBlog] : []))
        vi.mocked(postBlogNarration).mockImplementation(() => new Promise(() => {}))

        renderProvider()
        await waitFor(() => expect(screen.getByTestId('ready')).toHaveTextContent('734'))

        // Play the ready one...
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))
        expect(screen.getByTestId('playing')).toHaveTextContent('true')
        expect(audioElements[0].src).toContain('narration.mp3')

        // ...then press Listen on a cold one.
        await userEvent.click(screen.getByRole('button', { name: 'listen other' }))

        expect(audioElements[0].pause).toHaveBeenCalled()
        expect(screen.getByTestId('playing')).toHaveTextContent('false')
        // The element must not still hold the old track, or the transport would drive it.
        expect(audioElements[0].getAttribute('src')).toBeNull()
        // The bar has relabelled to the new item, so the two agree.
        expect(screen.getByTestId('track')).toHaveTextContent('other')
      })
    })

    describe('failures', () => {
      /**
       * The backend maps `failureCode: BUDGET_EXHAUSTED` onto `UNAVAILABLE`. Retrying cannot
       * help until the month turns over, so it must not offer one.
       */
      it('reports a spent monthly budget as not retryable', async () => {
        vi.mocked(postBlogNarration).mockResolvedValue({
          ok: true,
          value: {
            state: 'UNAVAILABLE', version: 0, retryable: false,
            message: 'Narration is temporarily unavailable',
          },
        })

        renderProvider()
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))

        await waitFor(() => {
          expect(screen.getByTestId('error'))
            .toHaveTextContent('Audio is unavailable this month.')
        })
        expect(screen.getByTestId('retryable')).toHaveTextContent('false')
        // The card returns to its resting state; failures live in the bar only.
        expect(screen.getByTestId('stage')).toHaveTextContent('idle')
        expect(screen.getByTestId('card-stage')).toHaveTextContent('idle')
      })

      it('passes through a retryable narration failure with the server wording', async () => {
        vi.mocked(postBlogNarration).mockResolvedValue({
          ok: true,
          value: {
            state: 'FAILED', version: 2, retryable: true,
            message: 'Audio could not be prepared',
          },
        })

        renderProvider()
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))

        await waitFor(() => {
          expect(screen.getByTestId('error')).toHaveTextContent('Audio could not be prepared')
        })
        expect(screen.getByTestId('retryable')).toHaveTextContent('true')
      })

      // A property of the article, not a hiccup: there is nothing to retry into working.
      it('reports insufficient source text as not retryable', async () => {
        vi.mocked(fetchArticleSummary).mockResolvedValue({
          state: 'NOT_REQUESTED', version: 0, retryable: false, message: '',
        })
        vi.mocked(postArticleSummary).mockResolvedValue({
          ok: true,
          value: {
            state: 'FAILED', version: 1, retryable: false,
            failureCode: 'INSUFFICIENT_SOURCE_TEXT',
            message: 'There is not enough of this article to summarise',
          },
        })

        renderProvider({ contentType: 'ARTICLE_SUMMARY', contentId: 'article-9' })
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))

        await waitFor(() => {
          expect(screen.getByTestId('error'))
            .toHaveTextContent("There isn't enough of this article to summarise.")
        })
        expect(screen.getByTestId('retryable')).toHaveTextContent('false')
        // Never got as far as spending on text-to-speech.
        expect(postSummaryNarration).not.toHaveBeenCalled()
      })

      it('reports a 429 on the narration step with the server Retry-After', async () => {
        vi.mocked(postBlogNarration).mockResolvedValue({
          ok: false, rateLimited: true, retryAfterSeconds: 42,
        })

        renderProvider()
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))

        await waitFor(() => {
          expect(screen.getByTestId('error'))
            .toHaveTextContent('Too many requests. Try again in 42 seconds.')
        })
        expect(screen.getByTestId('retryable')).toHaveTextContent('true')
      })

      it('reports a 429 on the summary step too', async () => {
        vi.mocked(fetchArticleSummary).mockResolvedValue({
          state: 'NOT_REQUESTED', version: 0, retryable: false, message: '',
        })
        vi.mocked(postArticleSummary).mockResolvedValue({
          ok: false, rateLimited: true, retryAfterSeconds: 12,
        })

        renderProvider({ contentType: 'ARTICLE_SUMMARY', contentId: 'article-9' })
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))

        await waitFor(() => {
          expect(screen.getByTestId('error'))
            .toHaveTextContent('Too many requests. Try again in 12 seconds.')
        })
        expect(screen.getByTestId('retryable')).toHaveTextContent('true')
      })

      // Out of long-polls is not a failure — the render may still be running server-side.
      it('flags a generation that outran the polling window rather than failing it', async () => {
        vi.mocked(postBlogNarration).mockResolvedValue({ ok: true, value: queued })
        vi.mocked(fetchNarrationStatus).mockResolvedValue(queued)

        renderProvider()
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))

        await waitFor(() => {
          expect(screen.getByTestId('delayed')).toHaveTextContent('true')
        })
        expect(screen.getByTestId('error')).toHaveTextContent('none')
        // Four long-polls, the same budget useNarration uses.
        expect(fetchNarrationStatus).toHaveBeenCalledTimes(4)
      })

      /**
       * A narration row can say READY while the file is gone — a deleted narration, or a restore
       * that dropped and reimported collections over the uploads directory.
       */
      it('reports a dead audio url and forgets the stale entry', async () => {
        vi.mocked(fetchReadyNarrations).mockImplementation((contentType) =>
          Promise.resolve(contentType === 'BLOG' ? [readyBlog] : []))

        renderProvider()
        await waitFor(() => {
          expect(screen.getByTestId('ready')).toHaveTextContent('734')
        })
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))

        await act(async () => {
          audioElements[0].dispatchEvent(new Event('error'))
        })

        expect(screen.getByTestId('error'))
          .toHaveTextContent('This audio is no longer available.')
        expect(screen.getByTestId('retryable')).toHaveTextContent('false')
        expect(screen.getByTestId('track')).toHaveTextContent('none')
        // Forgotten, or the card would keep advertising a duration for a file that 404s.
        expect(screen.getByTestId('ready')).toHaveTextContent('none')
      })
    })

    describe('retry', () => {
      it('re-runs the chain for the item that failed', async () => {
        vi.mocked(postBlogNarration)
          .mockResolvedValueOnce({ ok: false, rateLimited: true, retryAfterSeconds: 5 })
          .mockResolvedValueOnce({ ok: true, value: readyResponse })

        const Retrier = () => {
          const { retry, error } = useNarrationAudio()
          return error
            ? <button onClick={retry} type="button">retry</button>
            : null
        }

        render(
          <NarrationAudioProvider>
            <Probe />
            <Retrier />
          </NarrationAudioProvider>,
        )
        await waitFor(() => expect(fetchReadyNarrations).toHaveBeenCalledTimes(2))
        await userEvent.click(screen.getByRole('button', { name: 'listen' }))
        await waitFor(() => {
          expect(screen.getByRole('button', { name: 'retry' })).toBeInTheDocument()
        })

        await userEvent.click(screen.getByRole('button', { name: 'retry' }))

        await waitFor(() => {
          expect(screen.getByTestId('stage')).toHaveTextContent('ready')
        })
        expect(postBlogNarration).toHaveBeenCalledTimes(2)
        expect(screen.getByTestId('error')).toHaveTextContent('none')
      })
    })
  })
})
