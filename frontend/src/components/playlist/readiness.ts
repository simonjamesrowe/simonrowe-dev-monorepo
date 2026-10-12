import { formatMinutes } from './playlistFormat'
import type { PlaylistEntry } from '../../types/playlist'

export type Readiness =
  | { kind: 'choosable'; label: string; estimate: number }
  | { kind: 'busy'; label: string }
  | { kind: 'blocked'; label: string }

/** What an item waiting for audio can do next, and how to say so. */
export function readinessOf(entry: PlaylistEntry): Readiness {
  const estimate = entry.estimatedSeconds ?? 0
  if (entry.summaryState === 'FAILED' && entry.summaryRetryable === false) {
    return { kind: 'blocked', label: "Can't be summarised" }
  }
  if (entry.audio.state === 'UNAVAILABLE') {
    return { kind: 'blocked', label: 'Audio unavailable this month' }
  }
  if (entry.audio.state === 'INELIGIBLE') {
    return { kind: 'blocked', label: "Can't be read aloud" }
  }
  if (entry.audio.state === 'QUEUED' || entry.audio.state === 'PROCESSING') {
    return { kind: 'busy', label: 'Making audio' }
  }
  if (entry.summaryState === 'GENERATING'
    || (entry.voiceRequested && entry.summaryState !== undefined && entry.summaryState !== 'READY')) {
    return { kind: 'busy', label: 'Writing the summary' }
  }
  if (entry.audio.state === 'FAILED') {
    return { kind: 'choosable', label: 'Audio failed · try again', estimate }
  }
  if (entry.summaryState !== undefined && entry.summaryState !== 'READY') {
    return { kind: 'choosable', label: 'Summary first, then audio', estimate }
  }
  return { kind: 'choosable', label: `~${formatMinutes(estimate)}`, estimate }
}

