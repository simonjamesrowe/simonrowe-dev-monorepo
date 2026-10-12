import { Heart } from 'lucide-react'

import type { PlaylistEntry } from '../../types/playlist'

interface PlaylistHeartProps {
  entry: PlaylistEntry
  canChange: boolean
  onToggle: (entry: PlaylistEntry) => void
}

/**
 * The heart on a playlist row: the same global favourite as the heart on the news card.
 * Signed out, a hearted item shows a filled heart that cannot be pressed, and an unhearted one
 * shows nothing, because there is nothing a visitor can do with it.
 */
export function PlaylistHeart({ entry, canChange, onToggle }: PlaylistHeartProps) {
  if (entry.favourite === undefined) return <span aria-hidden="true" className="playlist-heart" />
  if (!canChange) {
    return entry.favourite
      ? (
        <span aria-label="In favourites" className="playlist-heart playlist-heart--on" role="img">
          <Heart aria-hidden="true" fill="currentColor" size={18} />
        </span>
      )
      : <span aria-hidden="true" className="playlist-heart" />
  }
  return (
    <button
      aria-label={entry.favourite ? 'Remove from favourites' : 'Add to favourites'}
      aria-pressed={entry.favourite}
      className={`playlist-heart playlist-heart--button${entry.favourite ? ' playlist-heart--on' : ''}`}
      onClick={() => onToggle(entry)}
      type="button"
    >
      <Heart aria-hidden="true" fill={entry.favourite ? 'currentColor' : 'none'} size={18} />
    </button>
  )
}
