import { Headphones, Loader2 } from 'lucide-react'
import { useRef, useState } from 'react'

import { API_BASE_URL } from '../../config/api'
import { isPending, type NarrationState } from './useNarration'
import type { BlogNarrationResponse } from '../../types/blog'

const PLAYBACK_SPEEDS = [0.75, 1, 1.25, 1.5, 2]

interface NarrationPanelProps {
  state: NarrationState
  /** Distinguishes the audio element and the speed control on a page with several. */
  domId: string
  /** What the audio is of, for the player's aria-label. */
  subject: string
  /** Small caps label above the heading, e.g. "Listen". */
  eyebrow?: string
  heading?: string
  /** Copy for the not-yet-requested state. */
  promptText?: string
  actionLabel?: string
  /** Copy for the pending state. */
  pendingText?: string
  className?: string
}

function mediaUrl(path: string): string {
  return path.startsWith('http://') || path.startsWith('https://')
    ? path
    : `${API_BASE_URL}${path}`
}

function formatApproximateDuration(durationSeconds: number): string {
  const minutes = Math.max(1, Math.round(durationSeconds / 60))
  return `About ${minutes} min`
}

/**
 * The seven-state render machine and the audio player.
 *
 * Extracted from `BlogNarration` so the article summary drawer gets the same playback
 * behaviour — including the playback-speed control and pausing every other `<audio>` on
 * the page when this one starts — rather than a second, subtly different player.
 *
 * The class names and copy default to the blog values so `BlogNarration` stays a pure
 * rename for its own tests; every visible string is overridable for other callers.
 */
export function NarrationPanel({
  state,
  domId,
  subject,
  eyebrow = 'Listen',
  heading = 'Generated narration',
  promptText = 'Prefer to listen? Generate an audio version of this post.',
  actionLabel = 'Listen to this post',
  pendingText = 'Preparing audio. You can keep reading.',
  className = 'blog-narration',
}: NarrationPanelProps) {
  const { narration, checking, requesting, delayed, clientError } = state
  const [playbackRate, setPlaybackRate] = useState(1)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const headingId = domId

  const handlePlaybackRateChange = (rate: number) => {
    setPlaybackRate(rate)
    if (audioRef.current) {
      audioRef.current.playbackRate = rate
    }
  }

  const handleAudioPlay = (event: React.SyntheticEvent<HTMLAudioElement>) => {
    document.querySelectorAll('audio').forEach((audio) => {
      if (audio !== event.currentTarget) {
        audio.pause()
      }
    })
  }

  let content: React.ReactNode

  if (checking) {
    content = (
      <p aria-live="polite" className={`${className}__status`} role="status">
        <Loader2 aria-hidden="true" className={`${className}__spinner`} size={18} />
        Checking audio availability&hellip;
      </p>
    )
  } else if (clientError) {
    content = (
      <div className={`${className}__feedback`}>
        <p className={`${className}__message ${className}__message--error`} role="alert">
          {clientError}
        </p>
        <button
          className={`button button--secondary ${className}__action`}
          onClick={state.recheck}
          type="button"
        >
          Check audio status
        </button>
      </div>
    )
  } else if (requesting) {
    content = (
      <p aria-live="polite" className={`${className}__status`} role="status">
        <Loader2 aria-hidden="true" className={`${className}__spinner`} size={18} />
        Requesting audio&hellip;
      </p>
    )
  } else if (!narration || narration.state === 'NOT_REQUESTED') {
    content = (
      <div className={`${className}__prompt`}>
        <p className={`${className}__message`}>{promptText}</p>
        <button
          className={`button button--secondary ${className}__action`}
          onClick={() => void state.requestNarration()}
          type="button"
        >
          {actionLabel}
        </button>
      </div>
    )
  } else if (narration.state === 'READY') {
    content = (
      <div className={`${className}__player`}>
        <audio
          aria-label={`Generated narration for ${subject}`}
          controls
          onLoadedMetadata={() => {
            if (audioRef.current) audioRef.current.playbackRate = playbackRate
          }}
          onPlay={handleAudioPlay}
          preload="metadata"
          ref={audioRef}
          src={mediaUrl(narration.audioUrl)}
        >
          Your browser does not support audio playback.
        </audio>
        <div className={`${className}__player-meta`}>
          <span>{formatApproximateDuration(narration.durationSeconds)}</span>
          <label className={`${className}__speed-label`} htmlFor={`${headingId}-speed`}>
            Playback speed
            <select
              className={`${className}__speed`}
              id={`${headingId}-speed`}
              onChange={(event) => handlePlaybackRateChange(Number(event.target.value))}
              value={playbackRate}
            >
              {PLAYBACK_SPEEDS.map((speed) => (
                <option key={speed} value={speed}>{speed}&times;</option>
              ))}
            </select>
          </label>
        </div>
      </div>
    )
  } else if (isPending(narration)) {
    content = delayed ? (
      <div className={`${className}__feedback`}>
        <p aria-live="polite" className={`${className}__message`} role="status">
          This is taking longer than usual. You can keep reading and check again.
        </p>
        <button
          className={`button button--secondary ${className}__action`}
          onClick={state.recheck}
          type="button"
        >
          Check audio status
        </button>
      </div>
    ) : (
      <p
        aria-atomic="true"
        aria-live="polite"
        className={`${className}__status`}
        role="status"
      >
        <Loader2 aria-hidden="true" className={`${className}__spinner`} size={18} />
        {pendingText}
      </p>
    )
  } else if (narration.state === 'FAILED') {
    content = (
      <div className={`${className}__feedback`}>
        <p className={`${className}__message ${className}__message--error`} role="alert">
          Audio could not be prepared. {narration.message}
        </p>
        {narration.retryable && (
          <button
            className={`button button--secondary ${className}__action`}
            onClick={() => void state.requestNarration()}
            type="button"
          >
            Try again
          </button>
        )}
      </div>
    )
  } else {
    // Prefer the response's own message. These states carry a reason worth showing — a
    // caller that declined sign-in sends "Sign in to generate audio", and reporting
    // "temporarily unavailable" instead would blame the service for the reader's own
    // choice. Fall back to the generic wording when no message is supplied.
    const fallback = (narration as BlogNarrationResponse).state === 'INELIGIBLE'
      ? 'Narration is not available for this post.'
      : 'Narration is temporarily unavailable.'
    const message = narration.message?.trim() ? narration.message : fallback
    content = <p className={`${className}__message`} role="status">{message}</p>
  }

  return (
    <section aria-labelledby={headingId} className={className}>
      <div className={`${className}__heading`}>
        <span className={`${className}__icon`} aria-hidden="true">
          <Headphones size={20} />
        </span>
        <div>
          <span className={`${className}__eyebrow`}>{eyebrow}</span>
          <h2 className={`${className}__title`} id={headingId}>{heading}</h2>
        </div>
      </div>
      <div className={`${className}__body`}>{content}</div>
    </section>
  )
}
