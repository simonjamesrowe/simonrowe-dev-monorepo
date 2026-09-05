import { useCallback, useEffect, useRef, useState } from 'react'

import { fetchReadyNarrations } from '../../services/narrationApi'
import type { TourStep } from '../../types/tour'
import { narrationMediaUrl } from '../narration/narrationMedia'
import { readNarrationMuted, writeNarrationMuted } from './tourPreferences'

/**
 * Pauses only a player that is actually running.
 *
 * Guarding on `paused` is not just tidiness: jsdom does not implement
 * `HTMLMediaElement.pause`, so an unconditional call writes a "Not implemented" error to
 * stderr from every test that mounts the tour.
 */
function stop(audio: HTMLAudioElement): void {
  if (!audio.paused) {
    audio.pause()
  }
}

export interface TourNarration {
  /** True when the current step has audio and it is playing. */
  speaking: boolean
  /**
   * True when this step has nothing left to say — its audio finished, or there is none to
   * play because the step has no narration or the visitor muted it.
   *
   * This is what autoplay waits on. It is deliberately true rather than false in the silent
   * cases: a step with no audio must not stall an auto-advancing tour forever. The reading
   * floor in `TourProvider` is what stops those steps flying past.
   */
  settled: boolean
  /** True when any step of this tour has audio, so the control is worth rendering. */
  available: boolean
  muted: boolean
  toggleMuted: () => void
}

/**
 * Speaks each tour step aloud in the same Google voice the rest of the site narrates in.
 *
 * Audio is rendered server-side ahead of time by `TourNarrationSweep` and read here in one
 * bulk call, so a step never waits on synthesis and no public endpoint can be used to spend
 * the shared text-to-speech budget. A step with no audio is simply silent — narration is an
 * enhancement to the tour, never a precondition for it.
 *
 * The element is appended to `<body>` rather than rendered as JSX for the reason
 * `NarrationAudioProvider` documents: `document.querySelectorAll('audio')` only walks the
 * document, so a detached element is invisible to the "pause every other player" sweep and
 * the tour would end up talking over a blog narration.
 */
export function useTourNarration(
  isActive: boolean,
  steps: TourStep[],
  currentStepIndex: number,
): TourNarration {
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const [audioByStepId, setAudioByStepId] = useState<Record<string, string>>({})
  const [speaking, setSpeaking] = useState(false)
  const [finished, setFinished] = useState(false)
  const [muted, setMuted] = useState(readNarrationMuted)

  // One audio element for the life of the provider, in the document so other players see it.
  useEffect(() => {
    const audio = new Audio()
    audio.preload = 'auto'
    audio.dataset.tourNarration = 'true'
    document.body.appendChild(audio)
    audioRef.current = audio
    const onPlay = () => setSpeaking(true)
    const onStop = () => setSpeaking(false)
    // Only `ended` settles a step. A `pause` does not: pausing is how leaving a step and
    // muting both present, and treating either as "finished speaking" would advance the tour
    // off a step the visitor just silenced.
    const onEnded = () => {
      setSpeaking(false)
      setFinished(true)
    }
    // Audio that cannot play settles the step too. A missing or malformed file would
    // otherwise leave `settled` false forever, and autoplay waits on it — so one broken
    // narration would silently stop the tour advancing on that step for good. Settling here
    // hands the step to the reading-time floor instead, which is the silent-step behaviour.
    const onError = () => {
      setSpeaking(false)
      setFinished(true)
    }
    audio.addEventListener('play', onPlay)
    audio.addEventListener('pause', onStop)
    audio.addEventListener('ended', onEnded)
    audio.addEventListener('error', onError)
    return () => {
      audio.removeEventListener('play', onPlay)
      audio.removeEventListener('pause', onStop)
      audio.removeEventListener('ended', onEnded)
      audio.removeEventListener('error', onError)
      stop(audio)
      audio.remove()
      audioRef.current = null
    }
  }, [])

  // Load what is playable once per tour run, never per step.
  useEffect(() => {
    if (!isActive) {
      return
    }
    const controller = new AbortController()
    fetchReadyNarrations('TOUR_STEP', controller.signal)
      .then((ready) => {
        setAudioByStepId(Object.fromEntries(
          ready.map((item) => [item.contentId, item.audioUrl]),
        ))
      })
      .catch(() => {
        // A tour that cannot check for audio still runs, silently.
      })
    return () => controller.abort()
  }, [isActive])

  const currentStepId = isActive ? steps[currentStepIndex]?.id : undefined
  const currentAudioUrl = currentStepId ? audioByStepId[currentStepId] : undefined

  // Each step starts unspoken. Without this the previous step's `ended` would carry over and
  // settle the new one before a word of it had played.
  useEffect(() => {
    setFinished(false)
  }, [currentStepId])

  useEffect(() => {
    const audio = audioRef.current
    if (!audio) {
      return
    }
    if (!isActive || muted || !currentAudioUrl) {
      stop(audio)
      return
    }

    // The tour is the loudest thing on the page while it runs; anything else must yield.
    document.querySelectorAll('audio').forEach((other) => {
      if (other !== audio && !other.paused) {
        other.pause()
      }
    })

    audio.src = narrationMediaUrl(currentAudioUrl)
    audio.currentTime = 0
    // Autoplay is permitted here because starting the tour is itself a user gesture, but a
    // rejection must stay silent rather than surface as an unhandled promise — and must settle
    // the step, or a browser refusing to play would stall autoplay indefinitely.
    //
    // `play()` is only specified to return a promise since 2016 and older engines return
    // nothing at all, so the result is checked rather than assumed. Calling `.catch` on
    // `undefined` throws inside an effect, which would take the whole tour down.
    const played = audio.play()
    if (played && typeof played.catch === 'function') {
      played.catch(() => {
        setSpeaking(false)
        setFinished(true)
      })
    }

    return () => {
      stop(audio)
    }
  }, [isActive, muted, currentAudioUrl])

  const toggleMuted = useCallback(() => {
    setMuted((previous) => {
      const next = !previous
      writeNarrationMuted(next)
      return next
    })
  }, [])

  return {
    speaking,
    settled: muted || !currentAudioUrl || finished,
    available: Object.keys(audioByStepId).length > 0,
    muted,
    toggleMuted,
  }
}
