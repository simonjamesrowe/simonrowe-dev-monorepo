/**
 * Wire types for the site's shared playlist (`/api/playlist`). One list for everybody, like
 * favourites: anyone can read and play it, and only a signed-in visitor can change it.
 *
 * Queued items arrive in play order: anything chosen to "Play next", then the content's own
 * date, newest first.
 */

import type { NarrationAudioContentType } from './narrationAudio'

export type PlaylistItemStatus = 'QUEUED' | 'LISTENED'

export type PlaylistSource = 'SUMMARISED' | 'FAVOURITED' | 'LISTENED' | 'ADDED' | 'BACKFILLED'

export type PlaylistAudioState =
  | 'NOT_REQUESTED'
  | 'QUEUED'
  | 'PROCESSING'
  | 'READY'
  | 'FAILED'
  | 'UNAVAILABLE'
  | 'INELIGIBLE'

export type PlaylistSummaryState = 'NOT_REQUESTED' | 'GENERATING' | 'READY' | 'FAILED'

export interface PlaylistAudio {
  state: PlaylistAudioState
  /** Site-relative path under `/uploads/`, only when `state` is `READY`. */
  audioUrl?: string
  durationSeconds?: number
  /** The read-along timings beside the audio, when it has them. */
  timingsUrl?: string
}

export interface PlaylistEntry {
  contentType: NarrationAudioContentType
  contentId: string
  status: PlaylistItemStatus
  progressSeconds: number
  addedVia: PlaylistSource
  addedAt: string
  listenedAt?: string
  voiceRequested: boolean
  /** When someone chose "Play next". Pinned items come first, the latest choice first. */
  pinnedAt?: string
  title: string
  href: string
  external: boolean
  sourceName: string
  imageUrl?: string
  publishedAt?: string
  /** Absent for a blog post, which cannot be hearted. */
  favourite?: boolean
  /** Absent for a blog post, which is read in full rather than summarised. */
  summaryState?: PlaylistSummaryState
  summaryRetryable?: boolean
  audio: PlaylistAudio
  /** A guess at the audio's length, only while there is no audio yet. */
  estimatedSeconds?: number
}

export interface PlaylistResponse {
  items: PlaylistEntry[]
  autoVoice: boolean
}

export type PlaylistAudioOutcome =
  | 'READY'
  | 'QUEUED'
  | 'PROCESSING'
  | 'SUMMARISING'
  | 'FAILED'
  | 'UNAVAILABLE'
  | 'INELIGIBLE'
  | 'NOT_IN_PLAYLIST'

export interface PlaylistAudioResult {
  contentType: NarrationAudioContentType
  contentId: string
  outcome: PlaylistAudioOutcome
}

export interface PlaylistItemKey {
  contentType: NarrationAudioContentType
  contentId: string
}

/** The most items one "make audio" request may name; mirrors the server's limit. */
export const MAX_AUDIO_ITEMS = 20
