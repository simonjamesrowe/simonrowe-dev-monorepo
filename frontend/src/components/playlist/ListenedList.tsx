import { ChevronDown, Play, RotateCcw } from 'lucide-react'
import { useState } from 'react'

import { formatAgo, formatDate } from './playlistFormat'
import { itemKey } from '../../hooks/playlistStore'
import type { PlaylistEntry } from '../../types/playlist'

interface ListenedListProps {
  entries: PlaylistEntry[]
  canChange: boolean
  onPlay: (entry: PlaylistEntry) => void
  onPlayAgain: (entry: PlaylistEntry) => void
  onClear: () => void
}

/** What has been heard in the last 30 days, most recent first. Folded away by default. */
export function ListenedList({ entries, canChange, onPlay, onPlayAgain, onClear }: ListenedListProps) {
  const [open, setOpen] = useState(false)

  return (
    <section aria-label="Listened" className="playlist-listened">
      <div className="playlist-listened__bar">
        <p className="playlist-listened__summary">
          <strong>{entries.length} listened</strong> · kept for 30 days
        </p>
        <div className="playlist-listened__actions">
          {canChange && open && (
            <button className="playlist-button playlist-button--ghost" onClick={onClear} type="button">
              Clear listened
            </button>
          )}
          <button
            aria-expanded={open}
            className="playlist-button playlist-button--ghost"
            onClick={() => setOpen((value) => !value)}
            type="button"
          >
            {open ? 'Hide' : 'Show'}
            <ChevronDown
              aria-hidden="true"
              className={`playlist-listened__chevron${open ? ' playlist-listened__chevron--open' : ''}`}
              size={16}
            />
          </button>
        </div>
      </div>
      {open && (
        <ul className="playlist-listened__list">
          {entries.map((entry) => (
            <li className="listened-row" key={itemKey(entry)}>
              <span className="listened-row__text">
                <span className="listened-row__source">
                  {entry.sourceName}
                  <span className="listened-row__when">
                    {[formatDate(entry.publishedAt), `heard ${formatAgo(entry.listenedAt)}`]
                      .filter(Boolean).join(' · ')}
                  </span>
                </span>
                <span className="listened-row__title">{entry.title}</span>
              </span>
              {entry.audio.state === 'READY' && (
                canChange
                  ? (
                    <button
                      className="playlist-button playlist-button--ghost"
                      onClick={() => onPlayAgain(entry)}
                      type="button"
                    >
                      <RotateCcw aria-hidden="true" size={15} /> Queue again
                    </button>
                  )
                  : (
                    <button
                      aria-label={`Play ${entry.title}`}
                      className="playlist-button playlist-button--ghost"
                      onClick={() => onPlay(entry)}
                      type="button"
                    >
                      <Play aria-hidden="true" size={15} /> Play
                    </button>
                  )
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
