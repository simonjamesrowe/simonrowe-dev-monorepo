import {
  ChevronUp, Headphones, Heart, Loader2, Pause, Play, SkipBack, SkipForward, X,
} from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'

import { PLAYBACK_SPEEDS } from './playbackSpeeds'
import { useNarrationAudio } from './useNarrationAudio'
import { useAuth } from '../../auth/useAuth'
import { itemKey, usePlaylistSnapshot } from '../../hooks/playlistStore'
import { useMediaQuery } from '../../hooks/useMediaQuery'
import { usePlaylistActions } from '../../hooks/usePlaylistActions'
import { NowPlayingSheet } from '../playlist/NowPlayingSheet'
import { formatClock } from '../playlist/playlistFormat'
import type { ChainStage } from '../../types/narrationAudio'

const STAGE_LABELS: Record<ChainStage, string> = {
  idle: '',
  summarising: 'Summarising…',
  narrating: 'Preparing audio…',
  ready: '',
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
 * are what the bar is for, and an expand button opens the full player as a sheet.
 *
 * Playing from the playlist adds previous and next, a heart for an article, and a way back to
 * the list. On the playlist page itself a wide screen hides the bar: the page shows the full
 * player beside the list.
 */
export function NarrationPlayerBar() {
  const {
    track, stage, playing, position, duration, rate, error, delayed, upNext, notice,
    togglePlay, seek, setRate, dismiss, retry, recheck, next, previous, dismissNotice,
  } = useNarrationAudio()
  const compact = useMediaQuery('(max-width: 640px)')
  const location = useLocation()
  const { isAuthenticated } = useAuth()
  const { data } = usePlaylistSnapshot()
  const { toggleFavourite } = usePlaylistActions()
  const [sheetOpen, setSheetOpen] = useState(false)
  const expandRef = useRef<HTMLButtonElement>(null)
  const closeSheet = useCallback(() => {
    setSheetOpen(false)
    expandRef.current?.focus()
  }, [])

  // The bar is fixed to the bottom of the viewport, so it would sit on top of the last of the
  // page — the footer, or the final row of cards. A body attribute rather than a wrapper class
  // because the bar renders inside PublicLayout but has to reserve space in a scroll container
  // it does not own. Only while it is actually on screen: reserving the gutter permanently
  // would leave a dead strip under every page.
  const visible = Boolean(track) || Boolean(error) || Boolean(notice)
  // A playlist track's bar is taller on a phone (two rows), so it reserves more room.
  const queued = Boolean(track?.fromPlaylist)
  useEffect(() => {
    if (!visible) return
    document.body.dataset.narrationBar = queued ? 'queue' : 'true'
    return () => { delete document.body.dataset.narrationBar }
  }, [queued, visible])

  // An error can outlive its track — audio that 404s at playback time clears the track but must
  // still say so, and the bar is the only place errors are ever shown.
  if (!track && !error && !notice) return null

  const generating = stage === 'summarising' || stage === 'narrating'
  // Only offer the transport when the *current* track actually has audio. A track mid-chain
  // has none yet, and a track whose chain failed never will — in both cases a play button
  // would either do nothing or, worse, drive whatever was playing before.
  const playable = Boolean(track?.audioUrl)
  const total = duration || track?.durationSeconds || 0
  const onPlaylistPage = location.pathname === '/playlist'
  const entry = track ? data?.items.find((item) => itemKey(item) === itemKey(track)) : undefined
  const showHeart = entry?.favourite !== undefined && (isAuthenticated || entry.favourite)
  const upNextCount = upNext.length

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
    <section
      aria-label="Narration player"
      className={[
        'narration-bar',
        queued ? 'narration-bar--queue' : '',
        onPlaylistPage ? 'narration-bar--on-playlist' : '',
      ].filter(Boolean).join(' ')}
      role="region"
    >
      <div className="narration-bar__inner">
        <div className="narration-bar__meta">
          {renderTitle()}
          {/* Stage changes happen without any reader action, so they are announced rather than
              only shown. */}
          <p aria-atomic="true" aria-live="polite" className="narration-bar__status" role="status">
            {generating ? STAGE_LABELS[stage] : ''}
          </p>
          {!generating && playable && upNextCount > 0 && (
            <p className="narration-bar__next">Next: {upNext[0].title}</p>
          )}
        </div>

        {generating ? (
          <Loader2 aria-hidden="true" className="narration-bar__spinner" size={18} />
        ) : !playable ? null : (
          <div className="narration-bar__transport">
            {track?.fromPlaylist && !compact && (
              <button aria-label="Previous" className="narration-bar__step" onClick={previous} type="button">
                <SkipBack aria-hidden="true" fill="currentColor" size={16} />
              </button>
            )}
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
            {upNextCount > 0 && (
              <button aria-label="Next" className="narration-bar__step" onClick={next} type="button">
                <SkipForward aria-hidden="true" fill="currentColor" size={16} />
              </button>
            )}
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
            {showHeart && entry && (
              <button
                aria-label={entry.favourite ? 'Remove from favourites' : 'Add to favourites'}
                aria-pressed={entry.favourite}
                className={`narration-bar__step narration-bar__heart${entry.favourite ? ' narration-bar__heart--on' : ''}`}
                disabled={!isAuthenticated}
                onClick={() => { void toggleFavourite(entry) }}
                type="button"
              >
                <Heart aria-hidden="true" fill={entry.favourite ? 'currentColor' : 'none'} size={16} />
              </button>
            )}
            {track?.fromPlaylist && !compact && !onPlaylistPage && (
              <Link className="narration-bar__playlist" to="/playlist">
                <Headphones aria-hidden="true" size={15} /> Playlist{upNextCount > 0 ? ` · ${upNextCount}` : ''}
              </Link>
            )}
            {compact && (
              <button
                aria-label="Open the player"
                className="narration-bar__step"
                onClick={() => setSheetOpen(true)}
                ref={expandRef}
                type="button"
              >
                <ChevronUp aria-hidden="true" size={18} />
              </button>
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

      {notice && !error && (
        <div className="narration-bar__feedback">
          <p aria-live="polite" className="narration-bar__message" role="status">{notice}</p>
          <button
            className="button button--secondary narration-bar__action"
            onClick={dismissNotice}
            type="button"
          >
            OK
          </button>
        </div>
      )}

      {sheetOpen && playable && <NowPlayingSheet onClose={closeSheet} />}

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
