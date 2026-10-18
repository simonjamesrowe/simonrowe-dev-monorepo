/**
 * Types for the site-wide narration audio player — the docked bar and the per-card controls.
 *
 * Distinct from `BlogNarrationResponse` in `types/blog.ts`, which is the *wire* contract of a
 * single narration endpoint. These describe what the player holds.
 */

/** The two kinds of content the docked player and its generation chain handle. */
export type NarrationAudioContentType = 'BLOG' | 'ARTICLE_SUMMARY'

/**
 * What the bulk `GET /api/narrations/ready` read accepts.
 *
 * Wider than `NarrationAudioContentType` on purpose. Guided-tour steps have ready audio but no
 * per-item status endpoint and no client-driven generation chain — they are rendered server-side
 * by `TourNarrationSweep`, because a public POST that triggers text-to-speech would expose the
 * shared monthly budget. Widening `NarrationAudioContentType` instead would let `TOUR_STEP` reach
 * `fetchNarrationStatus`, whose two-way branch would silently request it from the news summary
 * path.
 */
export type ReadyNarrationContentType = NarrationAudioContentType | 'TOUR_STEP'

/**
 * One item that is playable right now, as `GET /api/narrations/ready` returns it.
 *
 * For `ARTICLE_SUMMARY` the `contentId` is the aggregated *article* id, not the summary id —
 * which is what lets the news listing key straight off the ids it already holds.
 */
export interface ReadyNarration {
  contentId: string
  audioUrl: string
  durationSeconds: number
  /** When each word is spoken, for the read-along. Absent for audio made before it existed. */
  timingsUrl?: string
}

/** What the docked bar is currently on. */
export interface NarrationTrack {
  contentType: NarrationAudioContentType
  contentId: string
  title: string
  /**
   * Where the bar's title links to. Supplied by the caller because only the card knows: a blog
   * track links internally to `/blogs/{id}`, a news track links out to the article's own URL.
   */
  href: string
  /** True when `href` leaves the site, so the link needs `target`/`rel`. */
  external?: boolean
  /** Known immediately for a ready track; filled in when a generation chain completes. */
  audioUrl?: string
  durationSeconds?: number
  /** When each word of `audioUrl` is spoken, for the read-along. Only ever beside its audio. */
  timingsUrl?: string
  /** Artwork for the full player and the phone's lock screen. */
  imageUrl?: string
  /** The publisher, or "Simon Rowe" for a post. Shown under the title. */
  sourceName?: string
  /** Where to start, in seconds. A playlist item resumes where it was left. */
  resumeAt?: number
  /**
   * Played from the playlist. Its progress and "heard" mark are saved, for a signed-in
   * listener, so the next device picks up where this one stopped.
   */
  fromPlaylist?: boolean
}

/**
 * How far a generation chain has got.
 *
 * `'ready'` means there is audio to play — not that it is playing. `'idle'` covers both "nothing
 * started" and "the last attempt failed", which is why `error` is a separate field.
 */
export type ChainStage = 'idle' | 'summarising' | 'narrating' | 'ready'

export interface NarrationAudioError {
  message: string
  /** Whether offering a retry could plausibly help. A spent monthly budget cannot. */
  retryable: boolean
}

/** What a reader asked to listen to. */
export interface ListenRequest {
  contentType: NarrationAudioContentType
  contentId: string
  title: string
  href: string
  external?: boolean
}

/** The outcome of the most recently completed chain, for consumers that need to react to it. */
export interface CompletedChain {
  contentType: NarrationAudioContentType
  contentId: string
  /** True when the chain had to produce the article's summary before narrating it. */
  summaryWasGenerated: boolean
}

export interface NarrationAudioState {
  track: NarrationTrack | null
  stage: ChainStage
  playing: boolean
  /** Seconds elapsed, mirrored from the audio element. */
  position: number
  /** Total seconds, mirrored from the audio element once metadata loads. */
  duration: number
  rate: number
  error: NarrationAudioError | null
  /** True once polling gave up and the reader should re-check manually. */
  delayed: boolean
  /** What plays after the current track, in order. Empty outside the playlist. */
  upNext: NarrationTrack[]
  /** When playback pauses for the end of a journey, in epoch milliseconds, or null. */
  journeyEndsAt: number | null
  /** A passing message that is not an error, such as "your journey's up". */
  notice: string | null
}
