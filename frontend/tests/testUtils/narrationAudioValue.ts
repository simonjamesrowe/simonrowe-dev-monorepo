import { vi } from 'vitest'

import type { NarrationAudioApi } from '../../src/components/narration/narrationAudioContext'
import type {
  ChainStage,
  ListenRequest,
  NarrationAudioContentType,
  NarrationTrack,
  ReadyNarration,
} from '../../src/types/narrationAudio'

export interface NarrationAudioStubOptions {
  /** Items that already have audio, keyed `${contentType}:${contentId}`. */
  ready?: Record<string, ReadyNarration>
  /** The current track, when a test needs the bar rendered. */
  track?: NarrationTrack | null
  stage?: ChainStage
  playing?: boolean
  position?: number
  duration?: number
  rate?: number
  error?: { message: string; retryable: boolean } | null
  delayed?: boolean
  /** Stage reported for a specific item, e.g. to render a card's in-flight state. */
  stages?: Record<string, ChainStage>
}

/**
 * A hand-controlled narration audio context for tests.
 *
 * Preferred over mounting the real `NarrationAudioProvider` in page and card tests: those tests
 * are about the page, and a stub lets them seed "this item has 12 minutes of audio" in one line
 * without also mocking the bulk endpoint, the audio element and the auth popup. The provider's
 * own behaviour is covered directly in
 * `tests/components/narration/NarrationAudioProvider.test.tsx`.
 *
 * Kept apart from `NarrationAudioStub.tsx` so that file exports only a component and the
 * `react-refresh/only-export-components` lint rule stays quiet.
 */
export function narrationAudioStub(
  options: NarrationAudioStubOptions = {},
): NarrationAudioApi & { listen: ReturnType<typeof vi.fn> } {
  const ready = options.ready ?? {}
  const stages = options.stages ?? {}
  const key = (contentType: NarrationAudioContentType, contentId: string) =>
    `${contentType}:${contentId}`

  return {
    track: options.track ?? null,
    stage: options.stage ?? 'idle',
    playing: options.playing ?? false,
    position: options.position ?? 0,
    duration: options.duration ?? 0,
    rate: options.rate ?? 1,
    error: options.error ?? null,
    delayed: options.delayed ?? false,
    lastCompleted: null,
    readyFor: (contentType, contentId) => ready[key(contentType, contentId)],
    stageFor: (contentType, contentId) => stages[key(contentType, contentId)] ?? 'idle',
    listen: vi.fn<(request: ListenRequest) => void>(),
    togglePlay: vi.fn(),
    seek: vi.fn(),
    setRate: vi.fn(),
    dismiss: vi.fn(),
    retry: vi.fn(),
    recheck: vi.fn(),
  }
}
