import { Mail, MessageCircleQuestion } from 'lucide-react'

import { formatPublished, yearsLabel } from '../calendarModel'
import type { SchoolMessage } from '../schoolNewsApi'
import type { NewsLoad } from '../useSchoolNews'

/** How many messages the narrow version beside a conversation shows. */
const COMPACT_MESSAGES = 3

interface SchoolMessagesCardProps {
  state: NewsLoad<SchoolMessage>
  onAsk: (question: string) => void
  compact?: boolean
}

/**
 * The school's recent emails to parents, by subject.
 *
 * Only emails someone has approved for the public reach this list, the same rule as the chat,
 * so it can be shorter than a parent's inbox. Each one is a button that asks the chat about it,
 * because the subject is all the card has room for and the chat has the whole email.
 */
export function SchoolMessagesCard({ state, onAsk, compact = false }: SchoolMessagesCardProps) {
  const items = state.status === 'ready' ? state.items : []
  const shown = compact ? items.slice(0, COMPACT_MESSAGES) : items

  return (
    <section
      className={`school-card school-messages${compact ? ' school-card--compact' : ''}`}
      aria-labelledby="school-messages-title"
    >
      <div className="school-card__head">
        <h2 id="school-messages-title" className="school-card__title">
          <Mail size={17} aria-hidden="true" />
          From the school
        </h2>
      </div>
      <div className="school-card__body">
        {state.status === 'loading' && (
          <p className="school-card__status" role="status">
            Loading the school&rsquo;s emails…
          </p>
        )}
        {state.status === 'error' && (
          <p className="school-card__status">The school&rsquo;s emails could not be loaded.</p>
        )}
        {state.status === 'ready' && items.length === 0 && (
          <p className="school-card__status">Nothing new from the school in the last month.</p>
        )}
        {shown.length > 0 && (
          <ul className="school-messages__list">
            {shown.map((message) => (
              <li key={message.id}>
                <button
                  type="button"
                  className="school-messages__item"
                  onClick={() =>
                    onAsk(
                      `What did the school's email "${message.title}" on ${formatPublished(
                        message.publishedAt,
                        'long',
                      )} say?`,
                    )
                  }
                >
                  <span className="school-messages__title">{message.title}</span>
                  <span className="school-messages__meta">
                    {message.yearGroups.length > 0
                      ? yearsLabel(message.yearGroups)
                      : 'All years'}
                    {' · '}
                    {formatPublished(message.publishedAt)}
                  </span>
                  <span className="school-messages__ask" aria-hidden="true">
                    <MessageCircleQuestion size={14} />
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}
