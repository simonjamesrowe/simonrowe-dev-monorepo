/**
 * Types for the site-wide narration audio player — the docked bar and the per-card controls.
 *
 * Distinct from `BlogNarrationResponse` in `types/blog.ts`, which is the *wire* contract of a
 * single narration endpoint. These describe what the player holds.
 */

/** The two kinds of content that can have narration audio today. */
export type NarrationAudioContentType = 'BLOG' | 'ARTICLE_SUMMARY'

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
}
