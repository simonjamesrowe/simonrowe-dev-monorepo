import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import { narrationMediaUrl } from './narrationMedia'
import { NarrationAudioContext, type NarrationAudioApi } from './narrationAudioContext'
import { LONG_POLL_SECONDS, MAX_LONG_POLLS } from './useNarration'
import { useAuth } from '../../auth/useAuth'
import { track as trackEvent } from '../../analytics/analytics'
import { useEnsureAuthenticated } from '../../hooks/useEnsureAuthenticated'
import { loadPlaylist, updatePlaylist } from '../../hooks/playlistStore'
import { fetchArticleSummary } from '../../services/articleSummaryApi'
import {
  fetchNarrationStatus,
  fetchReadyNarrations,
  postArticleSummary,
  postBlogNarration,
  postSummaryNarration,
} from '../../services/narrationApi'
import { markPlaylistListened, savePlaylistProgress } from '../../services/playlistApi'
import type { ArticleSummaryResponse } from '../../types/articleSummary'
import type { BlogNarrationResponse } from '../../types/blog'
import type {
  ChainStage,
  CompletedChain,
  ListenRequest,
  NarrationAudioContentType,
  NarrationAudioError,
  NarrationTrack,
  ReadyNarration,
} from '../../types/narrationAudio'

const CONTENT_TYPES: NarrationAudioContentType[] = ['BLOG', 'ARTICLE_SUMMARY']

const DEFAULT_RATE = 1

/** How often a playlist item's position is saved while it plays. */
const PROGRESS_SAVE_INTERVAL_MS = 15_000

/** "Previous" restarts the current track unless it is closer to the start than this. */
const RESTART_THRESHOLD_SECONDS = 5

/** A saved position this close to either end is not worth resuming from. */
const RESUME_MARGIN_SECONDS = 5

const SKIP_BACK_SECONDS = 15
const SKIP_FORWARD_SECONDS = 30

const JOURNEY_NOTICE = "Your journey's up, so the playlist is paused."

/** The analytics name for each narrated content type. */
const ANALYTICS_CONTENT_TYPE = { BLOG: 'blog', ARTICLE_SUMMARY: 'article' } as const satisfies Record<
  NarrationAudioContentType,
  string
>

/** BLOG and ARTICLE_SUMMARY ids come from different collections and could collide. */
function keyFor(contentType: NarrationAudioContentType, contentId: string): string {
  return `${contentType}:${contentId}`
}

/** Merges a batch of ready rows into the map, keyed so the two content types cannot collide. */
function withRows(
  previous: Map<string, ReadyNarration>,
  contentType: NarrationAudioContentType,
  rows: ReadyNarration[],
): Map<string, ReadyNarration> {
  const next = new Map(previous)
  for (const row of rows) {
    next.set(keyFor(contentType, row.contentId), row)
  }
  return next
}

function revokeAll(cache: Map<string, string>) {
  for (const objectUrl of cache.values()) URL.revokeObjectURL(objectUrl)
  cache.clear()
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

function isPending(response: BlogNarrationResponse): boolean {
  return response.state === 'QUEUED' || response.state === 'PROCESSING'
}

/**
 * How a finished narration response reads in the bar.
 *
 * `UNAVAILABLE` is where a spent monthly budget arrives: the backend maps
 * `failureCode: BUDGET_EXHAUSTED` onto it (see `NarrationResponse.from`). Retrying cannot help
 * until the month turns over, so it must not offer a retry.
 */
function narrationError(response: BlogNarrationResponse): NarrationAudioError {
  if (response.state === 'UNAVAILABLE') {
    return { message: 'Audio is unavailable this month.', retryable: false }
  }
  if (response.state === 'INELIGIBLE') {
    return { message: 'This item cannot be narrated.', retryable: false }
  }
  return {
    message: response.message?.trim()
      ? response.message
      : 'Audio could not be prepared.',
    retryable: response.retryable,
  }
}

/** A failed summary. `INSUFFICIENT_SOURCE_TEXT` is a property of the article, not a hiccup. */
function summaryError(response: ArticleSummaryResponse): NarrationAudioError {
  if (response.state === 'FAILED' && response.failureCode === 'INSUFFICIENT_SOURCE_TEXT') {
    return {
      message: "There isn't enough of this article to summarise.",
      retryable: false,
    }
  }
  return {
    message: response.message?.trim() ? response.message : 'The summary could not be generated.',
    retryable: response.state === 'FAILED' ? response.retryable : false,
  }
}

/**
 * How a refused chain step reads. A 429 is retryable and carries the server's own wait; anything
 * else is reported with whatever the server said, and is worth one more try.
 */
function postFailureError(
  outcome: { rateLimited: true; retryAfterSeconds: number } | { rateLimited: false; message: string },
): NarrationAudioError {
  return outcome.rateLimited
    ? rateLimitError(outcome.retryAfterSeconds)
    : { message: outcome.message, retryable: true }
}

function rateLimitError(retryAfterSeconds: number): NarrationAudioError {
  return {
    message: `Too many requests. Try again in ${retryAfterSeconds} seconds.`,
    retryable: true,
  }
}

interface NarrationAudioProviderProps {
  children: React.ReactNode
}

/**
 * Owns all listing-initiated narration playback for the whole site.
 *
 * ## Why it is mounted where it is
 *
 * `App.tsx` wraps every public route in its *own* `<PublicLayout>` element, so React reconciles
 * them as different elements and `PublicLayout` — along with every provider inside it — remounts
 * on navigation. This provider therefore has to sit **above `<Routes>`**, and **inside
 * `AuthProvider`** because the generation chain needs a session. That is a single valid position;
 * moving it into `PublicLayout` would destroy the track on the first route change, which is
 * precisely the thing a shared player exists to prevent.
 *
 * ## Why the audio element is detached
 *
 * The element is a `new Audio()` held in a ref and appended to `<body>` — never JSX. A rendered
 * `<audio>` would live in the remounting tree and stop playing on navigation for the same reason.
 * `<body>` rather than fully detached, because `document.querySelectorAll('audio')` only walks the
 * document: a detached element would be invisible to `NarrationPanel`'s "pause every other audio
 * on the page" handler, and the bar and a detail-page player could talk over each other. Sitting
 * in `<body>` keeps it outside everything React reconciles while leaving both players able to find
 * each other — and this provider installs the mirror image of that handler, so starting either one
 * pauses the other.
 *
 * ## What it publishes rather than duplicates
 *
 * Two facts change when a generation chain completes, and each already has one owner:
 * - *Audio became ready* — this provider holds the ready map, so it writes the finished track
 *   there. Cards read the map through here, so a card flips to `▶ 12 min` whether or not the bar
 *   is still on screen. That is what makes dismissing the bar mid-generation safe.
 * - *A summary became ready* — `useArticleSummaries` owns that, and it is mounted *below* this
 *   provider, so this provider cannot write to it. It exposes `lastCompleted` and the news page
 *   relays it via `noteSummarised`.
 */
export function NarrationAudioProvider({ children }: NarrationAudioProviderProps) {
  const [readyMap, setReadyMap] = useState<Map<string, ReadyNarration>>(new Map())
  const [track, setTrack] = useState<NarrationTrack | null>(null)
  const [stage, setStage] = useState<ChainStage>('idle')
  const [playing, setPlaying] = useState(false)
  const [position, setPosition] = useState(0)
  const [duration, setDuration] = useState(0)
  const [rate, setRate] = useState(DEFAULT_RATE)
  const [error, setError] = useState<NarrationAudioError | null>(null)
  const [delayed, setDelayed] = useState(false)
  const [lastCompleted, setLastCompleted] = useState<CompletedChain | null>(null)
  const [upNext, setUpNextState] = useState<NarrationTrack[]>([])
  const [journeyEndsAt, setJourneyEndsAt] = useState<number | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const ensureAuthenticated = useEnsureAuthenticated()
  const { getAccessToken, isAuthenticated } = useAuth()

  // The audio element's listeners are installed once, so everything they read is mirrored
  // into a ref rather than captured.
  const upNextRef = useRef<NarrationTrack[]>([])
  /** Tracks already played in this session, most recent last, for "previous". */
  const historyRef = useRef<NarrationTrack[]>([])
  /** A position to seek to once the next source's metadata has loaded. */
  const pendingSeekRef = useRef<number | null>(null)
  const lastProgressSaveRef = useRef(0)
  const journeyEndsAtRef = useRef<number | null>(null)
  journeyEndsAtRef.current = journeyEndsAt
  const authRef = useRef({ isAuthenticated, getAccessToken })
  authRef.current = { isAuthenticated, getAccessToken }
  /** The next track's audio, fetched ahead so a patchy signal does not stall the change. */
  const prefetchedRef = useRef<Map<string, string>>(new Map())
  /** Late-bound so the once-installed listeners always call the current versions. */
  const handlersRef = useRef<{
    finished: () => void
    saveProgress: (positionSeconds: number) => void
  }>({ finished: () => {}, saveProgress: () => {} })

  const replaceUpNext = useCallback((tracks: NarrationTrack[]) => {
    upNextRef.current = tracks
    setUpNextState(tracks)
  }, [])

  const audioRef = useRef<HTMLAudioElement | null>(null)
  /** The rate survives track changes, so it is applied to each new source as it loads. */
  const rateRef = useRef(DEFAULT_RATE)
  rateRef.current = rate
  /** Mirrors `track` for the audio element's listeners, which are installed once. */
  const trackRef = useRef<NarrationTrack | null>(null)
  trackRef.current = track

  /**
   * One controller per chain, replaced on each `listen()`. Starting a chain for a different item
   * therefore abandons the one in flight — the reader's most recent intent wins.
   */
  const chainControllerRef = useRef<AbortController | null>(null)
  /**
   * The track the reader closed the bar on. Suppresses auto-play without cancelling the work:
   * the POST is already paid for and there is no cancellation API, so the audio still gets
   * recorded and the card still becomes playable — it just does not start talking at someone
   * who deliberately shut the player.
   */
  const dismissedKeyRef = useRef<string | null>(null)
  /** The last request, so `retry` and `recheck` know what to act on. */
  const lastRequestRef = useRef<ListenRequest | null>(null)

  // The bulk read: one call per content type, per provider mount — which, because the provider
  // sits above <Routes>, means once per full page load rather than once per navigation.
  useEffect(() => {
    const controller = new AbortController()

    const load = async (contentType: NarrationAudioContentType) => {
      try {
        const rows = await fetchReadyNarrations(contentType, controller.signal)
        setReadyMap((previous) => withRows(previous, contentType, rows))
      } catch {
        // Leave the map empty for this content type — every card just reads "Listen". A failed
        // availability check must never stop a listing rendering or surface an error.
      }
    }

    for (const contentType of CONTENT_TYPES) {
      void load(contentType)
    }
    return () => controller.abort()
  }, [])

  /** Creates the detached element on first use and wires it to provider state. */
  const audioElement = useCallback((): HTMLAudioElement => {
    if (audioRef.current) return audioRef.current

    const audio = new Audio()
    audio.preload = 'metadata'
    audio.addEventListener('play', () => {
      setPlaying(true)
      // The mirror image of NarrationPanel's handler: whichever player starts, the other stops.
      document.querySelectorAll('audio').forEach((other) => {
        if (other !== audio) other.pause()
      })
    })
    audio.addEventListener('pause', () => {
      setPlaying(false)
      // The browser fires 'pause' just before 'ended'; the end is recorded as "heard" instead.
      const atEnd = audio.ended
        || (Number.isFinite(audio.duration) && audio.currentTime >= audio.duration - 0.5)
      if (!atEnd) handlersRef.current.saveProgress(audio.currentTime)
    })
    audio.addEventListener('ended', () => {
      setPlaying(false)
      handlersRef.current.finished()
    })
    audio.addEventListener('timeupdate', () => {
      setPosition(audio.currentTime)
      const endsAt = journeyEndsAtRef.current
      if (endsAt !== null && Date.now() >= endsAt) {
        audio.pause()
        setJourneyEndsAt(null)
        setNotice(JOURNEY_NOTICE)
        return
      }
      if (Date.now() - lastProgressSaveRef.current >= PROGRESS_SAVE_INTERVAL_MS) {
        handlersRef.current.saveProgress(audio.currentTime)
      }
    })
    audio.addEventListener('loadedmetadata', () => {
      audio.playbackRate = rateRef.current
      const length = Number.isFinite(audio.duration) ? audio.duration : 0
      setDuration(length)
      const resumeAt = pendingSeekRef.current
      pendingSeekRef.current = null
      if (resumeAt !== null && resumeAt < length - RESUME_MARGIN_SECONDS) {
        audio.currentTime = resumeAt
        setPosition(resumeAt)
      }
    })
    // The file can be gone even though a narration row says READY — a narration deleted, or a
    // restore that dropped and reimported collections over the top of the uploads directory.
    // Reporting the truth beats a play button that does nothing.
    audio.addEventListener('error', () => {
      if (!audio.src) return
      const dead = trackRef.current
      setPlaying(false)
      setStage('idle')
      setTrack(null)
      setPosition(0)
      setDuration(0)
      setError({ message: 'This audio is no longer available.', retryable: false })
      // Forget it, or the card keeps advertising a duration for a file that 404s.
      if (dead) {
        setReadyMap((previous) => {
          const next = new Map(previous)
          next.delete(keyFor(dead.contentType, dead.contentId))
          return next
        })
      }
    })
    // Appended to <body>, deliberately, and *not* rendered as JSX.
    //
    // JSX is what has to be avoided: an <audio> inside the route tree is unmounted on the first
    // navigation, which is the exact failure the docked player exists to prevent. But a purely
    // detached element is invisible to `document.querySelectorAll('audio')`, which only walks
    // the document — so `NarrationPanel`'s "pause every other audio on the page" would silently
    // fail to pause this one, and a reader could end up with the bar and a detail-page player
    // talking over each other. Living in <body>, outside anything React reconciles, satisfies
    // both: React never removes it, and both players can find each other.
    document.body.appendChild(audio)
    audioRef.current = audio
    return audio
  }, [])

  useEffect(() => () => {
    audioRef.current?.pause()
    audioRef.current?.remove()
    chainControllerRef.current?.abort()
    revokeAll(prefetchedRef.current)
  }, [])

  /** Points the audio element at a ready track and starts it. */
  const loadAndPlay = useCallback((next: NarrationTrack) => {
    if (!next.audioUrl) return
    const audio = audioElement()
    pendingSeekRef.current = next.resumeAt && next.resumeAt > RESUME_MARGIN_SECONDS
      ? next.resumeAt
      : null
    lastProgressSaveRef.current = Date.now()
    audio.src = prefetchedRef.current.get(next.audioUrl) ?? narrationMediaUrl(next.audioUrl)
    audio.playbackRate = rateRef.current
    setPosition(0)
    setDuration(next.durationSeconds ?? 0)
    // Optional-chained because jsdom's HTMLMediaElement.play() returns undefined, not a promise.
    void audio.play()?.catch(() => {
      // A rejected play() is almost always an autoplay-policy block; the reader can press play in
      // the bar, which is a genuine user gesture. Nothing worth reporting.
    })
  }, [audioElement])

  /** Records a finished narration so the item's card flips to `▶ N min`. */
  const publishReady = useCallback((
    request: ListenRequest,
    response: BlogNarrationResponse & { state: 'READY' },
  ) => {
    setReadyMap((previous) => {
      const next = new Map(previous)
      next.set(keyFor(request.contentType, request.contentId), {
        contentId: request.contentId,
        audioUrl: response.audioUrl,
        durationSeconds: response.durationSeconds,
        timingsUrl: response.timingsUrl,
      })
      return next
    })
  }, [])

  /**
   * Waits for a narration to settle, reusing `useNarration`'s polling policy rather than
   * inventing a second one. Returns the settled response, or null when the wait was abandoned
   * (a new track took over) or outran the window.
   */
  const pollUntilSettled = useCallback(async (
    request: ListenRequest,
    initial: BlogNarrationResponse,
    controller: AbortController,
  ): Promise<BlogNarrationResponse | null> => {
    let current = initial

    for (let attempt = 0; attempt < MAX_LONG_POLLS; attempt += 1) {
      const next = await fetchNarrationStatus(request.contentType, request.contentId, {
        afterVersion: current.version,
        waitSeconds: LONG_POLL_SECONDS,
        signal: controller.signal,
      })
      if (controller.signal.aborted) return null

      current = next
      if (!isPending(next)) return next
    }

    // Out of long-polls rather than failed: the bar offers a manual re-check, mirroring
    // NarrationPanel's `delayed` state. Pointless for a bar the reader closed.
    const dismissed = dismissedKeyRef.current === keyFor(request.contentType, request.contentId)
    if (!controller.signal.aborted && !dismissed) setDelayed(true)
    return null
  }, [])

  /** Applies a settled narration: play it if the reader is still watching, record it either way. */
  const settleNarration = useCallback((
    request: ListenRequest,
    response: BlogNarrationResponse,
    summaryWasGenerated: boolean,
  ) => {
    const key = keyFor(request.contentType, request.contentId)

    const dismissed = dismissedKeyRef.current === key

    if (response.state !== 'READY') {
      setStage('idle')
      // A closed bar has nowhere to show a failure, and reviving it to complain about work the
      // reader walked away from would be worse than silence.
      if (!dismissed) setError(narrationError(response))
      return
    }

    publishReady(request, response)
    setLastCompleted({
      contentType: request.contentType,
      contentId: request.contentId,
      summaryWasGenerated,
    })

    // Auto-play only follows a chain the reader is still watching. Pressing Listen and
    // completing a sign-in is explicit consent; a bar they closed is not.
    if (dismissed) return

    const next: NarrationTrack = {
      ...request,
      audioUrl: response.audioUrl,
      durationSeconds: response.durationSeconds,
      timingsUrl: response.timingsUrl,
    }
    setTrack(next)
    setStage('ready')
    loadAndPlay(next)
  }, [loadAndPlay, publishReady])

  /**
   * The escalating chain for an item with no audio yet.
   *
   * Sign-in first, always: a dismissed popup issues no request and shows no error, exactly as
   * `useArticleSummaries` behaves today — a reader who changes their mind never triggers a paid
   * call and is never told off for it.
   */
  /** Reports a failure and returns the bar to rest. */
  const failWith = useCallback((problem: NarrationAudioError) => {
    setStage('idle')
    setError(problem)
  }, [])

  /**
   * The news-only first stage: make sure there is a summary to narrate.
   *
   * A public read decides which of the two news chains this is — the narration endpoint would
   * just 404 without a summary. Returns whether the chain may continue, and whether it had to
   * generate the summary itself (which the news page needs to know about).
   */
  const ensureSummary = useCallback(async (
    request: ListenRequest,
    controller: AbortController,
  ): Promise<{ proceed: boolean; generated: boolean }> => {
    const existing = await fetchArticleSummary(request.contentId, { signal: controller.signal })
    if (controller.signal.aborted) return { proceed: false, generated: false }
    if (existing.state === 'READY') return { proceed: true, generated: false }

    setStage('summarising')
    const outcome = await postArticleSummary(
      getAccessToken, request.contentId, controller.signal)
    if (controller.signal.aborted) return { proceed: false, generated: false }

    if (!outcome.ok) {
      failWith(postFailureError(outcome))
      return { proceed: false, generated: false }
    }
    if (outcome.value.state !== 'READY') {
      failWith(summaryError(outcome.value))
      return { proceed: false, generated: false }
    }
    return { proceed: true, generated: true }
  }, [failWith, getAccessToken])

  /** The second stage: queue the render and wait for it. */
  const narrate = useCallback(async (
    request: ListenRequest,
    controller: AbortController,
    summaryWasGenerated: boolean,
  ): Promise<void> => {
    setStage('narrating')
    const queued = request.contentType === 'BLOG'
      ? await postBlogNarration(getAccessToken, request.contentId, controller.signal)
      : await postSummaryNarration(getAccessToken, request.contentId, controller.signal)
    if (controller.signal.aborted) return

    if (!queued.ok) {
      failWith(postFailureError(queued))
      return
    }
    if (!isPending(queued.value)) {
      settleNarration(request, queued.value, summaryWasGenerated)
      return
    }

    const settled = await pollUntilSettled(request, queued.value, controller)
    if (settled) settleNarration(request, settled, summaryWasGenerated)
  }, [failWith, getAccessToken, pollUntilSettled, settleNarration])

  const runChain = useCallback(async (
    request: ListenRequest,
    controller: AbortController,
  ): Promise<void> => {
    if (!(await ensureAuthenticated())) return
    if (controller.signal.aborted) return

    setTrack({ ...request })

    try {
      let summaryWasGenerated = false
      if (request.contentType === 'ARTICLE_SUMMARY') {
        const summary = await ensureSummary(request, controller)
        if (!summary.proceed) return
        summaryWasGenerated = summary.generated
      }
      await narrate(request, controller, summaryWasGenerated)
    } catch (error) {
      if (controller.signal.aborted || isAbortError(error)) return
      failWith({
        message: error instanceof Error && error.message
          ? error.message
          : 'Audio could not be prepared. Please try again.',
        retryable: true,
      })
    }
  }, [ensureAuthenticated, ensureSummary, failWith, narrate])

  const listen = useCallback((request: ListenRequest) => {
    // A new track abandons whatever was in flight — the reader's latest intent wins.
    chainControllerRef.current?.abort()
    const controller = new AbortController()
    chainControllerRef.current = controller

    const key = keyFor(request.contentType, request.contentId)
    dismissedKeyRef.current = null
    lastRequestRef.current = request
    setError(null)
    setDelayed(false)
    setNotice(null)
    // A single item replaces any list that was playing.
    historyRef.current = []
    replaceUpNext([])

    const ready = readyMap.get(key)
    trackEvent('Listen Pressed', {
      contentType: ANALYTICS_CONTENT_TYPE[request.contentType],
      ready: Boolean(ready),
    })
    if (ready) {
      const next: NarrationTrack = {
        ...request,
        audioUrl: ready.audioUrl,
        durationSeconds: ready.durationSeconds,
        timingsUrl: ready.timingsUrl,
      }
      setTrack(next)
      setStage('ready')
      loadAndPlay(next)
      return
    }

    // Switching to an item that has to be generated: stop whatever is playing first.
    // Otherwise the previous track stays audible under a bar now labelled with a different
    // item, and the transport controls a track the reader is no longer being shown — press
    // Pause and you pause a post the bar is not naming. `removeAttribute` rather than
    // `src = ''`, which some browsers resolve to the document URL and then fail to load.
    audioRef.current?.pause()
    audioRef.current?.removeAttribute('src')
    setPlaying(false)
    setPosition(0)
    setDuration(0)

    void runChain(request, controller)
  }, [loadAndPlay, readyMap, replaceUpNext, runChain])

  const togglePlay = useCallback(() => {
    const audio = audioRef.current
    if (!audio || !audio.src) return
    if (audio.paused) {
      void audio.play()?.catch(() => setPlaying(false))
    } else {
      audio.pause()
    }
  }, [])

  const seek = useCallback((seconds: number) => {
    const audio = audioRef.current
    if (!audio || !audio.src) return
    audio.currentTime = seconds
    setPosition(seconds)
  }, [])

  const changeRate = useCallback((next: number) => {
    setRate(next)
    rateRef.current = next
    if (audioRef.current) audioRef.current.playbackRate = next
  }, [])

  /**
   * Closes the bar.
   *
   * Mid-chain this suppresses auto-play and takes the bar off screen, but it deliberately does
   * **not** abort the chain. Two reasons: the POST is already paid for and there is no
   * cancellation API, and the poll is what learns the audio is ready. Aborting it would leave
   * the card stuck on "Listen" until the next full page load even though the MP3 exists — the
   * opposite of what dismissing is supposed to be safe about. So the loop runs on, records the
   * result (see `settleNarration`), and simply does not start playing.
   */
  const dismiss = useCallback(() => {
    if (track) dismissedKeyRef.current = keyFor(track.contentType, track.contentId)
    audioRef.current?.pause()
    setTrack(null)
    setStage('idle')
    setPlaying(false)
    setPosition(0)
    setDuration(0)
    setError(null)
    setDelayed(false)
    setNotice(null)
    setJourneyEndsAt(null)
    historyRef.current = []
    replaceUpNext([])
  }, [replaceUpNext, track])

  const retry = useCallback(() => {
    const request = lastRequestRef.current
    if (request) listen(request)
  }, [listen])

  /** Re-checks a generation that outran the polling window, without re-POSTing. */
  const recheck = useCallback(() => {
    const request = lastRequestRef.current
    if (!request) return

    chainControllerRef.current?.abort()
    const controller = new AbortController()
    chainControllerRef.current = controller
    setDelayed(false)
    setError(null)
    setStage('narrating')

    void (async () => {
      try {
        const current = await fetchNarrationStatus(request.contentType, request.contentId, {
          signal: controller.signal,
        })
        if (controller.signal.aborted) return
        if (!isPending(current)) {
          settleNarration(request, current, false)
          return
        }
        const settled = await pollUntilSettled(request, current, controller)
        if (settled) settleNarration(request, settled, false)
      } catch (error) {
        if (controller.signal.aborted || isAbortError(error)) return
        setStage('idle')
        setError({ message: 'Audio status could not be checked.', retryable: true })
      }
    })()
  }, [pollUntilSettled, settleNarration])

  /** Saves where a playlist item got to, for a signed-in listener. Best-effort. */
  const saveProgress = useCallback((positionSeconds: number) => {
    const current = trackRef.current
    lastProgressSaveRef.current = Date.now()
    if (!current?.fromPlaylist || !authRef.current.isAuthenticated) return
    updatePlaylist((data) => ({
      ...data,
      items: data.items.map((item) => item.contentType === current.contentType
        && item.contentId === current.contentId
        ? { ...item, progressSeconds: positionSeconds } : item),
    }))
    void savePlaylistProgress(authRef.current.getAccessToken, current, positionSeconds)
      .catch(() => {
        // A lost save costs a resume point, not the listening. Nothing worth interrupting for.
      })
  }, [])

  /** Plays a track from a list, resuming where it was left. */
  const startTrack = useCallback((next: NarrationTrack) => {
    chainControllerRef.current?.abort()
    dismissedKeyRef.current = null
    lastRequestRef.current = null
    setError(null)
    setDelayed(false)
    setTrack(next)
    setStage('ready')
    loadAndPlay(next)
  }, [loadAndPlay])

  /** Moves to the next track, or stops at the end of the list. Returns whether it moved. */
  const advance = useCallback((): boolean => {
    const [next, ...rest] = upNextRef.current
    if (!next) return false
    const current = trackRef.current
    if (current) historyRef.current = [...historyRef.current, current]
    replaceUpNext(rest)
    startTrack(next)
    return true
  }, [replaceUpNext, startTrack])

  /** A track played to its end: record it as heard, then carry on down the list. */
  const finished = useCallback(() => {
    const current = trackRef.current
    if (current) {
      trackEvent('Narration Completed', {
        contentType: ANALYTICS_CONTENT_TYPE[current.contentType],
        fromPlaylist: Boolean(current.fromPlaylist),
      })
    }
    if (current?.fromPlaylist && authRef.current.isAuthenticated) {
      void markPlaylistListened(authRef.current.getAccessToken, current)
        .then(() => loadPlaylist(true))
        .catch(() => {
          // It stays queued and will simply be offered again. No need to interrupt.
        })
    }
    advance()
  }, [advance])

  handlersRef.current = { finished, saveProgress }

  const playQueue = useCallback((tracks: NarrationTrack[], startIndex: number) => {
    const start = tracks[startIndex]
    if (!start?.audioUrl) return
    const current = trackRef.current
    if (current?.fromPlaylist && audioRef.current?.src) saveProgress(audioRef.current.currentTime)
    setNotice(null)
    historyRef.current = tracks.slice(0, startIndex)
    replaceUpNext(tracks.slice(startIndex + 1).filter((track) => Boolean(track.audioUrl)))
    startTrack(start)
  }, [replaceUpNext, saveProgress, startTrack])

  const setUpNext = useCallback((tracks: NarrationTrack[]) => {
    replaceUpNext(tracks.filter((track) => Boolean(track.audioUrl)))
  }, [replaceUpNext])

  const next = useCallback(() => {
    if (audioRef.current?.src) saveProgress(audioRef.current.currentTime)
    advance()
  }, [advance, saveProgress])

  const previous = useCallback(() => {
    const audio = audioRef.current
    const current = trackRef.current
    const history = historyRef.current
    if (!audio || !current) return
    if (audio.currentTime > RESTART_THRESHOLD_SECONDS || history.length === 0) {
      audio.currentTime = 0
      setPosition(0)
      return
    }
    const before = history[history.length - 1]
    historyRef.current = history.slice(0, -1)
    replaceUpNext([{ ...current, resumeAt: undefined }, ...upNextRef.current])
    startTrack({ ...before, resumeAt: undefined })
  }, [replaceUpNext, startTrack])

  const skipBy = useCallback((seconds: number) => {
    const audio = audioRef.current
    if (!audio || !audio.src) return
    const length = Number.isFinite(audio.duration) ? audio.duration : Number.POSITIVE_INFINITY
    const target = Math.min(Math.max(0, audio.currentTime + seconds), length)
    audio.currentTime = target
    setPosition(target)
  }, [])

  const setJourney = useCallback((minutes: number | null) => {
    setNotice(null)
    setJourneyEndsAt(minutes === null ? null : Date.now() + minutes * 60_000)
  }, [])

  const dismissNotice = useCallback(() => setNotice(null), [])

  // The phone's lock screen and headphone buttons. Feature-detected: not every browser has it,
  // and jsdom has none of it.
  useEffect(() => {
    if (!('mediaSession' in navigator)) return
    const session = navigator.mediaSession
    const handlers: [MediaSessionAction, MediaSessionActionHandler][] = [
      ['play', () => { void audioRef.current?.play()?.catch(() => setPlaying(false)) }],
      ['pause', () => audioRef.current?.pause()],
      ['previoustrack', () => previous()],
      ['nexttrack', () => next()],
      ['seekbackward', () => skipBy(-SKIP_BACK_SECONDS)],
      ['seekforward', () => skipBy(SKIP_FORWARD_SECONDS)],
      ['seekto', (details) => {
        if (details.seekTime !== undefined) seek(details.seekTime)
      }],
    ]
    for (const [action, handler] of handlers) {
      try {
        session.setActionHandler(action, handler)
      } catch {
        // An action this browser does not support. The rest still work.
      }
    }
    return () => {
      for (const [action] of handlers) {
        try {
          session.setActionHandler(action, null)
        } catch {
          // As above.
        }
      }
    }
  }, [next, previous, seek, skipBy])

  useEffect(() => {
    if (!('mediaSession' in navigator) || typeof MediaMetadata === 'undefined') return
    navigator.mediaSession.metadata = track?.audioUrl
      ? new MediaMetadata({
        title: track.title,
        artist: track.sourceName ?? 'Simon Rowe',
        album: track.fromPlaylist ? 'Playlist · simonrowe.dev' : 'simonrowe.dev',
        artwork: track.imageUrl ? [{ src: narrationMediaUrl(track.imageUrl) }] : [],
      })
      : null
  }, [track])

  useEffect(() => {
    if (!('mediaSession' in navigator)) return
    navigator.mediaSession.playbackState = playing ? 'playing' : 'paused'
  }, [playing])

  // Fetch the next track's audio ahead of time, so moving on does not wait on the network.
  // Only the current and next tracks are kept; anything else is released.
  useEffect(() => {
    const cache = prefetchedRef.current
    const keep = new Set([track?.audioUrl, upNext[0]?.audioUrl].filter(Boolean) as string[])
    for (const [url, objectUrl] of cache) {
      // The playing source is never released from under the element.
      if (!keep.has(url) && audioRef.current?.src !== objectUrl) {
        URL.revokeObjectURL(objectUrl)
        cache.delete(url)
      }
    }
    const nextUrl = upNext[0]?.audioUrl
    if (!nextUrl || cache.has(nextUrl) || typeof URL.createObjectURL !== 'function') return
    const controller = new AbortController()
    void fetch(narrationMediaUrl(nextUrl), { signal: controller.signal })
      .then((response) => (response.ok ? response.blob() : null))
      .then((blob) => {
        if (blob && !controller.signal.aborted) cache.set(nextUrl, URL.createObjectURL(blob))
      })
      .catch(() => {
        // Best-effort. Without it the next track streams like any other.
      })
    return () => controller.abort()
  }, [track, upNext])

  const readyFor = useCallback(
    (contentType: NarrationAudioContentType, contentId: string) =>
      readyMap.get(keyFor(contentType, contentId)),
    [readyMap],
  )

  const stageFor = useCallback(
    (contentType: NarrationAudioContentType, contentId: string): ChainStage =>
      track?.contentType === contentType && track.contentId === contentId ? stage : 'idle',
    [stage, track],
  )

  const currentTime = useCallback(() => audioRef.current?.currentTime ?? 0, [])

  const value = useMemo<NarrationAudioApi>(() => ({
    currentTime,
    track,
    stage,
    playing,
    position,
    duration,
    rate,
    error,
    delayed,
    lastCompleted,
    readyFor,
    stageFor,
    listen,
    togglePlay,
    seek,
    setRate: changeRate,
    dismiss,
    retry,
    recheck,
    upNext,
    journeyEndsAt,
    notice,
    playQueue,
    setUpNext,
    next,
    previous,
    skipBy,
    setJourney,
    dismissNotice,
  }), [
    changeRate, currentTime, delayed, dismiss, dismissNotice, duration, error, journeyEndsAt, lastCompleted,
    listen, next, notice, playQueue, playing, position, previous, rate, readyFor, recheck, retry,
    seek, setJourney, setUpNext, skipBy, stage, stageFor, togglePlay, track, upNext,
  ])

  return (
    <NarrationAudioContext.Provider value={value}>
      {children}
    </NarrationAudioContext.Provider>
  )
}
