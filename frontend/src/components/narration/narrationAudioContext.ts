import { createContext } from 'react'

import type {
  ChainStage,
  ListenRequest,
  NarrationAudioContentType,
  NarrationAudioState,
  CompletedChain,
  ReadyNarration,
} from '../../types/narrationAudio'

/**
 * Everything a card or the docked bar can see and do.
 *
 * In its own module, separate from both the provider component and the hook, so neither file
 * exports a mix of components and values — `react-refresh/only-export-components` is a lint rule
 * here and CI runs `npm run lint` as a blocking step.
 */
export interface NarrationAudioApi extends NarrationAudioState {
  /** The ready audio for an item, or undefined when it has none yet. */
  readyFor: (
    contentType: NarrationAudioContentType,
    contentId: string,
  ) => ReadyNarration | undefined
  /**
   * The chain stage for a specific item — `'idle'` unless that item is the current track. This is
   * how a card renders its in-flight state without holding any of its own, so a filter change or
   * a "Load more" cannot lose it.
   */
  stageFor: (
    contentType: NarrationAudioContentType,
    contentId: string,
  ) => ChainStage
  /** Start listening: plays immediately when audio exists, otherwise runs the generation chain. */
  listen: (request: ListenRequest) => void
  /** The most recently completed chain, for consumers that must react to it (see the news page). */
  lastCompleted: CompletedChain | null
  togglePlay: () => void
  seek: (seconds: number) => void
  setRate: (rate: number) => void
  /**
   * Close the bar. Stops playback and clears the track. Mid-generation it also stops watching and
   * suppresses auto-play — but it does not cancel the server-side work, which is already paid for,
   * and the finished audio is still recorded so the card becomes playable.
   */
  dismiss: () => void
  /** Retry the last failed chain. Only meaningful when `error?.retryable`. */
  retry: () => void
  /** Re-check a generation that outran the polling window. */
  recheck: () => void
}

export const NarrationAudioContext = createContext<NarrationAudioApi | null>(null)
