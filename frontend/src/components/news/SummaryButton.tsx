import { Sparkles } from 'lucide-react'

interface SummaryButtonProps {
  /** True when a summary already exists — the button reads and opens rather than generates. */
  hasSummary: boolean
  articleTitle: string
  onClick: () => void
  className?: string
}

/**
 * The per-card summary control.
 *
 * Two states, driven by the shared summaries id set:
 * - a summary exists → "Read summary", which opens the drawer immediately for everyone,
 *   signed in or not, because the artefact is globally shared
 * - no summary → "Summarise", which prompts for sign-in first, because generating one
 *   spends on the model
 *
 * News only. Events are not summarised.
 */
export function SummaryButton({
  hasSummary,
  articleTitle,
  onClick,
  className,
}: SummaryButtonProps) {
  const label = hasSummary ? 'Read summary' : 'Summarise'

  return (
    <button
      aria-label={hasSummary
        ? `Read the AI-generated summary of ${articleTitle}`
        : `Generate an AI summary of ${articleTitle}`}
      className={`summary-button${className ? ` ${className}` : ''}`}
      onClick={(e) => {
        // Cards are anchor elements — stop the click from opening the original article.
        e.preventDefault()
        e.stopPropagation()
        onClick()
      }}
      type="button"
    >
      <Sparkles aria-hidden="true" size={14} />
      <span>{label}</span>
    </button>
  )
}
