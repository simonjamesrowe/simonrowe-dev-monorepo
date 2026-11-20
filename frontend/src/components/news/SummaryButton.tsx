import { Loader2, Sparkles } from 'lucide-react'

interface SummaryButtonProps {
  /** True when a summary already exists — the button reads and opens rather than generates. */
  hasSummary: boolean
  /**
   * True while a summary is being written, whoever asked for it. The button then opens the
   * drawer to follow progress and never asks for a second one.
   */
  generating?: boolean
  articleTitle: string
  onClick: () => void
  className?: string
}

/**
 * The per-card summary control.
 *
 * Three states, driven by the shared summary id sets:
 * - a summary exists → "Read summary", which opens the drawer immediately for everyone,
 *   signed in or not, because the artefact is globally shared
 * - one is being written → "Summarising", which opens the drawer on its progress. It stays
 *   until the server says the summary is done or failed, even after the drawer is closed
 * - no summary → "Summarise", which prompts for sign-in first, because generating one
 *   spends on the model. It also asks for the audio: summarising and listening are one action,
 *   so the summary is read aloud and waiting on the playlist without a second press
 *
 * News only. Events are not summarised.
 */
export function SummaryButton({
  hasSummary,
  generating = false,
  articleTitle,
  onClick,
  className,
}: SummaryButtonProps) {
  const busy = generating && !hasSummary
  let label = 'Summarise'
  let ariaLabel = `Generate an AI summary of ${articleTitle}, with audio for the playlist`
  if (hasSummary) {
    label = 'Read summary'
    ariaLabel = `Read the AI-generated summary of ${articleTitle}`
  } else if (busy) {
    label = 'Summarising…'
    ariaLabel = `An AI summary of ${articleTitle} is being written. Open it to follow progress`
  }

  const classes = ['summary-button']
  if (busy) classes.push('summary-button--busy')
  if (className) classes.push(className)

  return (
    <button
      aria-busy={busy || undefined}
      aria-label={ariaLabel}
      className={classes.join(' ')}
      onClick={(e) => {
        // Cards are anchor elements — stop the click from opening the original article.
        e.preventDefault()
        e.stopPropagation()
        onClick()
      }}
      type="button"
    >
      {busy
        ? <Loader2 aria-hidden="true" className="summary-button__spinner" size={14} />
        : <Sparkles aria-hidden="true" size={14} />}
      <span>{label}</span>
    </button>
  )
}
