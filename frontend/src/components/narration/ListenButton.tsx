import { Headphones, Loader2, Play } from 'lucide-react'

import { formatCompactDuration } from './formatDuration'
import { useNarrationAudio } from './useNarrationAudio'
import type { NarrationAudioContentType } from '../../types/narrationAudio'

interface ListenButtonProps {
  contentType: NarrationAudioContentType
  contentId: string
  /** The item's title, for the accessible name and the bar's label. */
  title: string
  /** Where the bar's title should link. `/blogs/{id}` for a post, the article's URL for news. */
  href: string
  /** True when `href` leaves the site. */
  external?: boolean
  className?: string
}

/**
 * The per-card listen control.
 *
 * Three states, driven by the provider:
 * - audio exists → `▶ 12 min`, which plays instantly with no round trip. The duration *is* the
 *   reason to press it, which is why it is on the label rather than hidden in a tooltip.
 * - no audio → a secondary-weight `Listen`, an offer rather than the card's primary action. It is
 *   deliberately *visible* on cold cards: a control that appears on an unpredictable subset of
 *   cards reads as broken while narrations are still sparse.
 * - generation in flight for *this* item → a spinner and the stage it has reached.
 *
 * Everything is read from provider state keyed on the content id — this component holds no state
 * of its own. That is what stops a filter change or a "Load more" from losing an in-flight
 * generation.
 *
 * News cards are `<a>` anchors, so the click is stopped from reaching them exactly as
 * `SummaryButton` does.
 */
export function ListenButton({
  contentType,
  contentId,
  title,
  href,
  external,
  className,
}: ListenButtonProps) {
  const { readyFor, stageFor, listen } = useNarrationAudio()

  const ready = readyFor(contentType, contentId)
  const stage = stageFor(contentType, contentId)
  const busy = stage === 'summarising' || stage === 'narrating'

  let label: string
  let modifier: 'ready' | 'cold' | 'busy'
  if (busy) {
    label = stage === 'summarising' ? 'Summarising…' : 'Preparing audio…'
    modifier = 'busy'
  } else if (ready) {
    label = formatCompactDuration(ready.durationSeconds)
    modifier = 'ready'
  } else {
    label = 'Listen'
    modifier = 'cold'
  }

  return (
    <button
      aria-label={busy
        ? `${label} for ${title}`
        : ready
          ? `Listen to the ${label} audio version of ${title}`
          : `Generate an audio version of ${title}`}
      className={`listen-button listen-button--${modifier}${className ? ` ${className}` : ''}`}
      disabled={busy}
      onClick={(event) => {
        // Cards are anchor elements — stop the click from opening the original article.
        event.preventDefault()
        event.stopPropagation()
        listen({ contentType, contentId, title, href, external })
      }}
      type="button"
    >
      {busy
        ? <Loader2 aria-hidden="true" className="listen-button__spinner" size={14} />
        : ready
          ? <Play aria-hidden="true" size={14} />
          : <Headphones aria-hidden="true" size={14} />}
      <span>{label}</span>
    </button>
  )
}
