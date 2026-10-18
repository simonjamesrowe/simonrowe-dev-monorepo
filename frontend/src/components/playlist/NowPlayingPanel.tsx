import {
  BookOpenText,
  ChevronDown,
  ExternalLink,
  Headphones,
  Heart,
  Pause,
  Play,
  RotateCcw,
  RotateCw,
  SkipBack,
  SkipForward,
  TrainFront,
} from 'lucide-react'
import { useEffect, useState, type Ref } from 'react'
import { Link } from 'react-router-dom'

import { formatClock, formatDate, toTrack } from './playlistFormat'
import { ReadAlong } from './ReadAlong'
import { useAuth } from '../../auth/useAuth'
import { itemKey, upNextOf, usePlaylistSnapshot } from '../../hooks/playlistStore'
import { usePlaylistActions } from '../../hooks/usePlaylistActions'
import { fetchArticleSummary } from '../../services/articleSummaryApi'
import { fetchBlogById } from '../../services/blogApi'
import { narrationMediaUrl } from '../narration/narrationMedia'
import { PLAYBACK_SPEEDS } from '../narration/playbackSpeeds'
import { useNarrationAudio } from '../narration/useNarrationAudio'

const SKIP_BACK_SECONDS = 15
const SKIP_FORWARD_SECONDS = 30

/** Remembers whether the phone player shows the text instead of the artwork. */
const READ_ALONG_KEY = 'narration-read-along'

function readAlongPreferred(): boolean {
  try {
    return window.localStorage.getItem(READ_ALONG_KEY) === 'on'
  } catch {
    return false
  }
}

interface NowPlayingPanelProps {
  /** A card beside the playlist on wide screens, or the whole screen on a phone. */
  variant: 'card' | 'sheet'
  onClose?: () => void
  headingRef?: Ref<HTMLHeadingElement>
}

/**
 * What is playing, with the full set of controls. The playlist page shows it beside the list on
 * a wide screen; on a phone the docked bar opens it as a full-screen sheet from any page.
 */
export function NowPlayingPanel({ variant, onClose, headingRef }: NowPlayingPanelProps) {
  const {
    track, playing, position, duration, rate, upNext, journeyEndsAt,
    togglePlay, seek, setRate, next, previous, skipBy, setJourney, playQueue,
  } = useNarrationAudio()
  const { isAuthenticated } = useAuth()
  const { data } = usePlaylistSnapshot()
  const { toggleFavourite } = usePlaylistActions()
  const [text, setText] = useState<string | null>(null)
  const [reading, setReading] = useState(readAlongPreferred)

  const entry = track
    ? data?.items.find((item) => itemKey(item) === itemKey(track))
    : undefined
  const queue = data ? upNextOf(data.items) : []
  const queueIndex = track ? queue.findIndex((item) => itemKey(item) === itemKey(track)) : -1

  // Read along: fetch the text being heard, a summary or a post. Both are public, and cheap.
  const contentType = track?.contentType
  const contentId = track?.contentId
  useEffect(() => {
    setText(null)
    if (!contentType || !contentId) return undefined
    const controller = new AbortController()
    const load = contentType === 'BLOG'
      ? fetchBlogById(contentId).then((blog) => blog.content)
      : fetchArticleSummary(contentId, { signal: controller.signal })
        .then((response) => (response.state === 'READY' ? response.body : null))
    load
      .then((body) => {
        if (!controller.signal.aborted && body) setText(body)
      })
      .catch(() => {
        // The read-along is a nicety. The audio plays without it.
      })
    return () => controller.abort()
  }, [contentId, contentType])

  const toggleReading = () => {
    setReading((on) => {
      try {
        window.localStorage.setItem(READ_ALONG_KEY, on ? 'off' : 'on')
      } catch {
        // Private browsing: the choice lasts until the page closes.
      }
      return !on
    })
  }

  if (!track) {
    return (
      <div className={`now-playing now-playing--${variant} now-playing--idle`}>
        <Headphones aria-hidden="true" className="now-playing__idle-icon" size={28} />
        <p className="now-playing__idle-text">Nothing playing.</p>
        {queue.length > 0 && (
          <button
            className="now-playing__start"
            onClick={() => playQueue(queue.map(toTrack), 0)}
            type="button"
          >
            <Play aria-hidden="true" size={16} /> Play the playlist
          </button>
        )}
      </div>
    )
  }

  const total = duration || track.durationSeconds || 0
  const nextRate = PLAYBACK_SPEEDS[(PLAYBACK_SPEEDS.indexOf(rate) + 1) % PLAYBACK_SPEEDS.length]
  const minutesToStop = journeyEndsAt === null
    ? null
    : Math.max(0, Math.round((journeyEndsAt - Date.now()) / 60_000))

  return (
    <div className={`now-playing now-playing--${variant}`}>
      {variant === 'sheet' && (
        <div className="now-playing__sheet-bar">
          <button
            aria-label="Close the player"
            className="now-playing__round"
            onClick={onClose}
            type="button"
          >
            <ChevronDown aria-hidden="true" size={20} />
          </button>
          <span className="now-playing__sheet-label">
            {track.fromPlaylist ? 'Playing from Playlist' : 'Now playing'}
          </span>
          <span aria-hidden="true" className="now-playing__sheet-spacer" />
        </div>
      )}

      {variant === 'sheet' && reading && text
        ? <ReadAlong className="read-along--sheet" markdown={text} timingsUrl={track.timingsUrl} />
        : (
          <div className="now-playing__art">
            {track.imageUrl
              ? <img alt="" src={narrationMediaUrl(track.imageUrl)} />
              : <Headphones aria-hidden="true" size={40} />}
          </div>
        )}

      <div className="now-playing__body">
        <p className="now-playing__from">
          <Headphones aria-hidden="true" size={14} />
          Now playing{queueIndex >= 0 ? ` · ${queueIndex + 1} of ${queue.length}` : ''}
        </p>
        <h2 className="now-playing__title" ref={headingRef} tabIndex={-1}>{track.title}</h2>
        <p className="now-playing__meta">
          {[
            track.contentType === 'BLOG' ? 'Blog post' : 'Summary',
            track.sourceName,
            formatDate(entry?.publishedAt),
          ].filter(Boolean).join(' · ')}
        </p>

        <input
          aria-label="Seek"
          className="now-playing__seek"
          max={Math.max(total, 1)}
          min={0}
          onChange={(event) => seek(Number(event.target.value))}
          step={1}
          style={{ '--fill': `${total > 0 ? (position / total) * 100 : 0}%` } as React.CSSProperties}
          type="range"
          value={Math.min(position, total || position)}
        />
        <div className="now-playing__times">
          <span>{formatClock(position)}</span>
          <span>−{formatClock(Math.max(0, total - position))}</span>
        </div>

        <div className="now-playing__controls">
          <button aria-label="Previous" className="now-playing__control" onClick={previous} type="button">
            <SkipBack aria-hidden="true" fill="currentColor" size={22} />
          </button>
          <button
            aria-label={`Back ${SKIP_BACK_SECONDS} seconds`}
            className="now-playing__control"
            onClick={() => skipBy(-SKIP_BACK_SECONDS)}
            type="button"
          >
            <RotateCcw aria-hidden="true" size={22} />
            <span aria-hidden="true" className="now-playing__skip-label">{SKIP_BACK_SECONDS}</span>
          </button>
          <button
            aria-label={playing ? 'Pause' : 'Play'}
            className="now-playing__control now-playing__control--main"
            onClick={togglePlay}
            type="button"
          >
            {playing
              ? <Pause aria-hidden="true" fill="currentColor" size={26} />
              : <Play aria-hidden="true" fill="currentColor" size={26} />}
          </button>
          <button
            aria-label={`Forward ${SKIP_FORWARD_SECONDS} seconds`}
            className="now-playing__control"
            onClick={() => skipBy(SKIP_FORWARD_SECONDS)}
            type="button"
          >
            <RotateCw aria-hidden="true" size={22} />
            <span aria-hidden="true" className="now-playing__skip-label">{SKIP_FORWARD_SECONDS}</span>
          </button>
          <button
            aria-label="Next"
            className="now-playing__control"
            disabled={upNext.length === 0}
            onClick={next}
            type="button"
          >
            <SkipForward aria-hidden="true" fill="currentColor" size={22} />
          </button>
        </div>

        <div className="now-playing__options">
          <button
            aria-label={`Playback speed ${rate}×. Change to ${nextRate}×`}
            className="now-playing__option"
            onClick={() => setRate(nextRate)}
            type="button"
          >
            {rate}×
          </button>
          {variant === 'sheet' && text && (
            <button
              aria-pressed={reading}
              className={`now-playing__option${reading ? ' now-playing__option--on' : ''}`}
              onClick={toggleReading}
              type="button"
            >
              <BookOpenText aria-hidden="true" size={14} /> Read along
            </button>
          )}
          {minutesToStop !== null && (
            <button
              aria-label={`Pauses in ${minutesToStop} minutes. Cancel`}
              className="now-playing__option now-playing__option--on"
              onClick={() => setJourney(null)}
              type="button"
            >
              <TrainFront aria-hidden="true" size={14} /> Pauses in {minutesToStop} min
            </button>
          )}
          {entry?.favourite !== undefined && (isAuthenticated || entry.favourite) && (
            <button
              aria-pressed={entry.favourite}
              className={`now-playing__option${entry.favourite ? ' now-playing__option--hearted' : ''}`}
              disabled={!isAuthenticated}
              onClick={() => { void toggleFavourite(entry) }}
              type="button"
            >
              <Heart aria-hidden="true" fill={entry.favourite ? 'currentColor' : 'none'} size={14} />
              {entry.favourite ? 'Favourite' : 'Add to favourites'}
            </button>
          )}
        </div>

        {variant === 'card' && (
          <div className="now-playing__read">
            {text && (
              <>
                <h3 className="now-playing__read-title">
                  {track.timingsUrl
                    ? 'Read along'
                    : track.contentType === 'BLOG' ? 'The post' : 'The summary'}
                </h3>
                <ReadAlong markdown={text} timingsUrl={track.timingsUrl} />
              </>
            )}
            {track.external
              ? (
                <a className="now-playing__link" href={track.href} rel="noopener noreferrer" target="_blank">
                  Open the original article <ExternalLink aria-hidden="true" size={14} />
                </a>
              )
              : <Link className="now-playing__link" to={track.href}>Read the post</Link>}
          </div>
        )}

        {variant === 'sheet' && upNext.length > 0 && (
          <div className="now-playing__next">
            <div className="now-playing__next-head">
              <h3>Up next</h3>
              <Link onClick={onClose} to="/playlist">See all</Link>
            </div>
            <ul>
              {upNext.slice(0, 3).map((item) => (
                <li className="now-playing__next-item" key={itemKey(item)}>
                  {item.imageUrl
                    ? <img alt="" src={narrationMediaUrl(item.imageUrl)} />
                    : <span aria-hidden="true" className="now-playing__next-blank" />}
                  <span className="now-playing__next-title">{item.title}</span>
                  <span className="now-playing__next-time">{formatClock(item.durationSeconds ?? 0)}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </div>
  )
}
