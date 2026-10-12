import { Headphones, TrainFront } from 'lucide-react'
import { Fragment } from 'react'
import { useNavigate } from 'react-router-dom'

import { PlaylistHeart } from './PlaylistHeart'
import { formatClock, formatDate, kindOf } from './playlistFormat'
import { RowMenu, type RowMenuAction } from './RowMenu'
import { itemKey } from '../../hooks/playlistStore'
import { narrationMediaUrl } from '../narration/narrationMedia'
import type { PlaylistEntry } from '../../types/playlist'

interface PlaylistQueueProps {
  entries: PlaylistEntry[]
  currentKey: string | null
  playing: boolean
  /** The item the journey ends during; a marker sits under it. */
  stopKey: string | null
  /** Items the journey will not reach, dimmed. */
  beyondKeys: ReadonlySet<string>
  /** Signed in: rows can be hearted, played next and removed. */
  canChange: boolean
  onPlay: (index: number) => void
  onPlayNext: (entry: PlaylistEntry) => void
  onRemove: (entry: PlaylistEntry) => void
  onToggleHeart: (entry: PlaylistEntry) => void
}

/** Everything queued that has audio, in play order: "Play next" first, then newest first. */
export function PlaylistQueue({
  entries, currentKey, playing, stopKey, beyondKeys, canChange,
  onPlay, onPlayNext, onRemove, onToggleHeart,
}: PlaylistQueueProps) {
  const navigate = useNavigate()

  const open = (entry: PlaylistEntry) => {
    if (entry.external) {
      window.open(entry.href, '_blank', 'noopener,noreferrer')
    } else {
      navigate(entry.href)
    }
  }

  return (
    <ol className={`playlist-queue${canChange ? '' : ' playlist-queue--readonly'}`}>
      {entries.map((entry, index) => {
        const key = itemKey(entry)
        const current = key === currentKey
        const seconds = entry.audio.durationSeconds ?? 0
        const resumeLeft = !current && entry.progressSeconds > 0
          ? Math.max(0, seconds - entry.progressSeconds) : null
        const actions: RowMenuAction[] = [
          { label: 'Play next', onSelect: () => onPlayNext(entry), disabled: current },
          { label: entry.external ? 'Open the original article' : 'Read the post', onSelect: () => open(entry) },
          { label: 'Remove from the playlist', onSelect: () => onRemove(entry) },
        ]
        const className = [
          'playlist-row',
          current ? 'playlist-row--current' : '',
          beyondKeys.has(key) ? 'playlist-row--beyond' : '',
        ].filter(Boolean).join(' ')

        return (
          <Fragment key={key}>
            <li className={className}>
              <span aria-hidden="true" className="playlist-row__number">{index + 1}</span>
              <button
                aria-label={current && playing ? `Pause ${entry.title}` : `Play ${entry.title}`}
                aria-current={current ? 'true' : undefined}
                className="playlist-row__play"
                onClick={() => onPlay(index)}
                type="button"
              >
                {entry.imageUrl
                  ? <img alt="" className="playlist-row__image" loading="lazy" src={narrationMediaUrl(entry.imageUrl)} />
                  : (
                    <span aria-hidden="true" className="playlist-row__image playlist-row__image--blank">
                      <Headphones size={18} />
                    </span>
                  )}
                <span className="playlist-row__text">
                  <span className="playlist-row__source">
                    {entry.sourceName}
                    <span className="playlist-row__kind">
                      {[kindOf(entry), formatDate(entry.publishedAt)].filter(Boolean).join(' · ')}
                    </span>
                    {entry.pinnedAt && !current && <span className="playlist-row__pin">Plays next</span>}
                  </span>
                  <span className="playlist-row__title">{entry.title}</span>
                </span>
              </button>
              <span className="playlist-row__time">
                {current && playing
                  ? <span aria-hidden="true" className="playlist-eq"><i /><i /><i /></span>
                  : formatClock(seconds)}
                {resumeLeft !== null && (
                  <span className="playlist-row__left">{formatClock(resumeLeft)} left</span>
                )}
              </span>
              <PlaylistHeart canChange={canChange} entry={entry} onToggle={onToggleHeart} />
              {canChange && <RowMenu actions={actions} label={`More for ${entry.title}`} />}
            </li>
            {key === stopKey && (
              <li aria-hidden="true" className="playlist-queue__stop">
                <TrainFront size={15} /> Your journey ends here
                <span> · playback pauses</span>
              </li>
            )}
          </Fragment>
        )
      })}
    </ol>
  )
}
