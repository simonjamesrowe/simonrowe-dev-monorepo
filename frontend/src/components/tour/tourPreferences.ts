/**
 * Visitor choices about how the tour plays, remembered between visits.
 *
 * Storage is best-effort throughout: private browsing and blocked storage must never stop the
 * tour running, and a preference that cannot be saved is not worth failing a click over. Every
 * read therefore falls back to the supplied default rather than propagating.
 */
const AUTOPLAY_PAUSED_KEY = 'tour.autoplay.paused'
const NARRATION_MUTED_KEY = 'tour.narration.muted'

function readFlag(key: string, fallback: boolean): boolean {
  try {
    const stored = window.localStorage.getItem(key)
    return stored === null ? fallback : stored === 'true'
  } catch {
    return fallback
  }
}

function writeFlag(key: string, value: boolean): void {
  try {
    window.localStorage.setItem(key, String(value))
  } catch {
    // Ignored deliberately — see the note above.
  }
}

/** Whether the visitor has stopped the tour advancing on its own. Defaults to playing. */
export function readAutoplayPaused(): boolean {
  return readFlag(AUTOPLAY_PAUSED_KEY, false)
}

export function writeAutoplayPaused(paused: boolean): void {
  writeFlag(AUTOPLAY_PAUSED_KEY, paused)
}

/** Whether the visitor has silenced the spoken narration. Defaults to speaking. */
export function readNarrationMuted(): boolean {
  return readFlag(NARRATION_MUTED_KEY, false)
}

export function writeNarrationMuted(muted: boolean): void {
  writeFlag(NARRATION_MUTED_KEY, muted)
}

/**
 * Roughly how long a step's own text takes to read.
 *
 * This is the floor autoplay applies to a step with no spoken audio — text-to-speech
 * unconfigured, generation not yet swept, or the visitor muted. Without it those steps would
 * settle the instant their target resolved and the tour would flick past unread. A step that
 * *is* narrated needs no protection from this: its audio already takes about as long.
 *
 * 180 words per minute is a deliberately unhurried silent-reading pace, and the bounds stop
 * both a two-word step vanishing and a mis-authored wall of text stalling the tour.
 */
export function readingTimeMs(text: string | null | undefined): number {
  const words = (text ?? '').trim().split(/\s+/).filter(Boolean).length
  if (words === 0) {
    return MIN_READING_MS
  }
  const estimate = (words / 180) * 60_000
  return Math.min(MAX_READING_MS, Math.max(MIN_READING_MS, Math.round(estimate)))
}

const MIN_READING_MS = 3500
const MAX_READING_MS = 15_000
