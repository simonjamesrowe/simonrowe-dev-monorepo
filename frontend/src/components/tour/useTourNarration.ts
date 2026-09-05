import { useCallback, useEffect, useRef, useState } from 'react'

import { fetchReadyNarrations } from '../../services/narrationApi'
import type { TourStep } from '../../types/tour'
import { narrationMediaUrl } from '../narration/narrationMedia'

const MUTE_PREFERENCE_KEY = 'tour.narration.muted'

function readMutePreference(): boolean {
  try {
    return window.localStorage.getItem(MUTE_PREFERENCE_KEY) === 'true'
  } catch {
    // Private browsing and blocked storage must not stop the tour narrating.
    return false
  }
}

function writeMutePreference(muted: boolean): void {
  try {
    window.localStorage.setItem(MUTE_PREFERENCE_KEY, String(muted))
  } catch {
    // A preference that cannot be saved is not worth failing a click over.
  }
}

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
  const [muted, setMuted] = useState(readMutePreference)

  // One audio element for the life of the provider, in the document so other players see it.
  useEffect(() => {
    const audio = new Audio()
    audio.preload = 'auto'
    audio.dataset.tourNarration = 'true'
    document.body.appendChild(audio)
    audioRef.current = audio
    const onPlay = () => setSpeaking(true)
    const onStop = () => setSpeaking(false)
    audio.addEventListener('play', onPlay)
    audio.addEventListener('pause', onStop)
    audio.addEventListener('ended', onStop)
    return () => {
      audio.removeEventListener('play', onPlay)
      audio.removeEventListener('pause', onStop)
      audio.removeEventListener('ended', onStop)
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
    // rejected promise must stay silent rather than surface as an unhandled rejection.
    void audio.play().catch(() => setSpeaking(false))

    return () => {
      stop(audio)
    }
  }, [isActive, muted, currentAudioUrl])

  const toggleMuted = useCallback(() => {
    setMuted((previous) => {
      const next = !previous
      writeMutePreference(next)
      return next
    })
  }, [])

  return {
    speaking,
    available: Object.keys(audioByStepId).length > 0,
    muted,
    toggleMuted,
  }
}
