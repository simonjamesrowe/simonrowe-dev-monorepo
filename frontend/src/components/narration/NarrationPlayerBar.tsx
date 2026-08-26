import { Loader2, Pause, Play, X } from 'lucide-react'
import { useEffect } from 'react'
import { Link } from 'react-router-dom'

import { PLAYBACK_SPEEDS } from './playbackSpeeds'
import { useNarrationAudio } from './useNarrationAudio'
import { useMediaQuery } from '../../hooks/useMediaQuery'
import type { ChainStage } from '../../types/narrationAudio'

const STAGE_LABELS: Record<ChainStage, string> = {
  idle: '',
  summarising: 'Summarising…',
  narrating: 'Preparing audio…',
  ready: '',
}

function formatClock(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00'
  const whole = Math.floor(seconds)
  const minutes = Math.floor(whole / 60)
  return `${minutes}:${String(whole % 60).padStart(2, '0')}`
}

/**
 * The docked player.
 *
 * Rendered inside `PublicLayout`, which is what keeps it off `/admin` without any path sniffing,
 * and which it can afford because it holds no state — everything comes from
 * `NarrationAudioProvider`, mounted above `<Routes>`. It may remount freely on navigation while
 * playback carries on in the provider's detached audio element.
 *
 * Four shapes:
 * - nothing to say → renders nothing at all
 * - generating → title plus a stage label, dismiss, and *no* transport controls: there is nothing
 *   to seek yet, and offering a dead scrubber would be worse than offering none
 * - ready → the full transport
 * - error with no track → just the message and dismiss. Audio that 404s at playback time clears
 *   the track but still has to say so, and the bar is the only place a failure is ever shown:
 *   never the card, which returns to its resting state.
 *
 * On narrow viewports the playback-speed control is dropped; the title, play/pause and progress
 * are what the bar is for.
 */
export function NarrationPlayerBar() {
  const {
    track, stage, playing, position, duration, rate, error, delayed,
    togglePlay, seek, setRate, dismiss, retry, recheck,
  } = useNarrationAudio()
  const compact = useMediaQuery('(max-width: 640px)')

  // The bar is fixed to the bottom of the viewport, so it would sit on top of the last of the
  // page — the footer, or the final row of cards. A body attribute rather than a wrapper class
  // because the bar renders inside PublicLayout but has to reserve space in a scroll container
  // it does not own. Only while it is actually on screen: reserving the gutter permanently
  // would leave a dead strip under every page.
  const visible = Boolean(track) || Boolean(error)
  useEffect(() => {
    if (!visible) return
    document.body.dataset.narrationBar = 'true'
    return () => { delete document.body.dataset.narrationBar }
  }, [visible])

  // An error can outlive its track — audio that 404s at playback time clears the track but must
  // still say so, and the bar is the only place errors are ever shown.
  if (!track && !error) return null

  const generating = stage === 'summarising' || stage === 'narrating'
  const total = duration || track?.durationSeconds || 0

  // A news track links out to the original article, a blog track links internally. Written as
  // a function rather than a nested ternary in the JSX so both branches stay legible.
  function renderTitle() {
    if (!track) return null
    if (track.external) {
      return (
        <a
          className="narration-bar__title"
          href={track.href}
          rel="noopener noreferrer"
          target="_blank"
        >
          {track.title}
        </a>
      )
    }
    return <Link className="narration-bar__title" to={track.href}>{track.title}</Link>
  }

  return (
    <section aria-label="Narration player" className="narration-bar" role="region">
      <div className="narration-bar__inner">
        <div className="narration-bar__meta">
          {renderTitle()}
          {/* Stage changes happen without any reader action, so they are announced rather than
              only shown. */}
          <p aria-atomic="true" aria-live="polite" className="narration-bar__status" role="status">
            {generating ? STAGE_LABELS[stage] : ''}
          </p>
        </div>

        {generating ? (
          <Loader2 aria-hidden="true" className="narration-bar__spinner" size={18} />
        ) : !track ? null : (
          <div className="narration-bar__transport">
            <button
              aria-label={playing ? 'Pause' : 'Play'}
              className="narration-bar__play"
              onClick={togglePlay}
              type="button"
            >
              {playing
                ? <Pause aria-hidden="true" size={18} />
                : <Play aria-hidden="true" size={18} />}
            </button>
            <input
              aria-label="Seek"
              className="narration-bar__seek"
              max={Math.max(total, 1)}
              min={0}
              onChange={(event) => seek(Number(event.target.value))}
              step={1}
              type="range"
              value={Math.min(position, total || position)}
            />
            <span className="narration-bar__time">
              {formatClock(position)} / {formatClock(total)}
            </span>
            {!compact && (
              <label className="narration-bar__speed-label" htmlFor="narration-bar-speed">
                Playback speed
                <select
                  className="narration-bar__speed"
                  id="narration-bar-speed"
                  onChange={(event) => setRate(Number(event.target.value))}
                  value={rate}
                >
                  {PLAYBACK_SPEEDS.map((speed) => (
                    <option key={speed} value={speed}>{speed}&times;</option>
                  ))}
                </select>
              </label>
            )}
          </div>
        )}

        <button
          aria-label="Close the narration player"
          className="narration-bar__dismiss"
          onClick={dismiss}
          type="button"
        >
          <X aria-hidden="true" size={18} />
        </button>
      </div>

      {error && (
        <div className="narration-bar__feedback">
          <p className="narration-bar__error" role="alert">{error.message}</p>
          {error.retryable && (
            <button
              className="button button--secondary narration-bar__action"
              onClick={retry}
              type="button"
            >
              Try again
            </button>
          )}
        </div>
      )}

      {delayed && !error && (
        <div className="narration-bar__feedback">
          <p aria-live="polite" className="narration-bar__message" role="status">
            This is taking longer than usual. You can keep browsing and check again.
          </p>
          <button
            className="button button--secondary narration-bar__action"
            onClick={recheck}
            type="button"
          >
            Check audio status
          </button>
        </div>
      )}
    </section>
  )
}
