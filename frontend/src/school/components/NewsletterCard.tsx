import { ExternalLink, Newspaper, Sparkles } from 'lucide-react'
import { useState } from 'react'

import { formatPublished, yearsLabel } from '../calendarModel'
import type { Newsletter } from '../schoolNewsApi'
import type { NewsLoad } from '../useSchoolNews'

/** How many points the narrow version beside a conversation shows. */
const COMPACT_POINTS = 3

interface NewsletterCardProps {
  state: NewsLoad<Newsletter>
  onAsk: (question: string) => void
  compact?: boolean
}

/**
 * The latest school newsletter in thirty seconds, with the earlier ones a click away.
 *
 * The summary is written once on the server when the newsletter arrives, never on view. Until it
 * has been written the card says so and links to the newsletter itself, which is always the
 * authority: the summary is a model's reading of it.
 */
export function NewsletterCard({ state, onAsk, compact = false }: NewsletterCardProps) {
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const items = state.status === 'ready' ? state.items : []
  const shown = items.find((item) => item.id === selectedId) ?? items[0]

  return (
    <section
      className={`school-card school-newsletter${compact ? ' school-card--compact' : ''}`}
      aria-labelledby="school-newsletter-title"
    >
      <div className="school-card__head">
        <h2 id="school-newsletter-title" className="school-card__title">
          <Newspaper size={17} aria-hidden="true" />
          {compact && shown ? `Newsletter · ${formatPublished(shown.publishedAt)}` : 'Newsletter'}
        </h2>
        {!compact && shown && (
          <span className="school-card__meta">{formatPublished(shown.publishedAt)}</span>
        )}
      </div>

      <div className="school-card__body">
        {state.status === 'loading' && (
          <p className="school-card__status" role="status">
            Loading the latest newsletter…
          </p>
        )}
        {state.status === 'error' && (
          <p className="school-card__status">The newsletter could not be loaded.</p>
        )}
        {state.status === 'ready' && !shown && (
          <p className="school-card__status">No newsletters yet this term.</p>
        )}

        {shown && (
          <>
            {shown.headline ? (
              <>
                {!compact && (
                  <span className="school-newsletter__badge">
                    <Sparkles size={12} aria-hidden="true" />
                    30-second summary
                  </span>
                )}
                <p className="school-newsletter__headline">{shown.headline}</p>
                <ul className="school-newsletter__points">
                  {(compact ? shown.points.slice(0, COMPACT_POINTS) : shown.points).map(
                    (point) => (
                      <li key={point.text} className="school-newsletter__point">
                        {point.text}
                        {point.yearGroups.length > 0 && (
                          <span className="school-newsletter__years">
                            {yearsLabel(point.yearGroups)}
                          </span>
                        )}
                      </li>
                    ),
                  )}
                </ul>
              </>
            ) : (
              <p className="school-card__status">
                The summary of this newsletter is still being written. The full newsletter is on
                the school&rsquo;s website.
              </p>
            )}

            <div className="school-newsletter__actions">
              <a
                className="school-button"
                href={shown.url}
                target="_blank"
                rel="noopener noreferrer"
              >
                <ExternalLink size={15} aria-hidden="true" />
                {compact ? 'Read it' : 'Full newsletter'}
              </a>
              {!compact && (
                <button
                  type="button"
                  className="school-button school-button--ai"
                  onClick={() =>
                    onAsk(
                      `What was in the school newsletter of ${formatPublished(
                        shown.publishedAt,
                        'long',
                      )}?`,
                    )
                  }
                >
                  <Sparkles size={15} aria-hidden="true" />
                  Ask about it
                </button>
              )}
            </div>

            {!compact && items.length > 1 && (
              <div className="school-newsletter__archive" role="group" aria-label="Newsletters">
                {items.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    aria-pressed={item.id === shown.id}
                    className={`school-newsletter__issue${
                      item.id === shown.id ? ' school-newsletter__issue--on' : ''
                    }`}
                    onClick={() => setSelectedId(item.id)}
                  >
                    {formatPublished(item.publishedAt, 'date')}
                  </button>
                ))}
              </div>
            )}
          </>
        )}
      </div>
    </section>
  )
}
