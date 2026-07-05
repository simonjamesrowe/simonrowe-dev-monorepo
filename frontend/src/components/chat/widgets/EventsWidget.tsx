import { Calendar, MapPin } from 'lucide-react'
import type { EventWidgetPayload } from '../chatTypes'
import { resolveChatWidgetImageUrl } from './chatWidgetImages'

interface EventsWidgetProps {
  payload: EventWidgetPayload
}

export function EventsWidget({ payload }: EventsWidgetProps) {
  if (!payload.events?.length) return null

  return (
    <div className="chat-widget chat-widget--events">
      {payload.events.map(event => {
        const imageUrl = resolveChatWidgetImageUrl(event.imageUrl)
        return (
          <article className="chat-widget__item" key={event.id ?? event.originalUrl ?? event.title}>
            {imageUrl && (
              <div className="chat-widget__media">
                <img src={imageUrl} alt="" className="chat-widget__image" />
              </div>
            )}
            <div className="chat-widget__item-head">
              <h4 className="chat-widget__title">{event.title}</h4>
              {event.sourceName && <span className="chat-widget__meta">{event.sourceName}</span>}
            </div>
            {(event.eventDate || event.eventEndDate) && (
              <div className="chat-widget__detail">
                <Calendar size={14} />
                <span>{formatDateRange(event.eventDate, event.eventEndDate)}</span>
              </div>
            )}
            {(event.venue || event.location) && (
              <div className="chat-widget__detail">
                <MapPin size={14} />
                <span>{[event.venue, event.location].filter(Boolean).join(', ')}</span>
              </div>
            )}
            {event.summary && <p className="chat-widget__summary">{event.summary}</p>}
            {event.originalUrl && (
              <a
                aria-label={`View event: ${event.title}`}
                className="chat-widget__link"
                href={event.originalUrl}
                rel="noopener noreferrer"
                target="_blank"
              >
                View event
              </a>
            )}
          </article>
        )
      })}
    </div>
  )
}

function formatDateRange(start?: string | null, end?: string | null) {
  if (start && end) return `${formatDateTime(start)} - ${formatDateTime(end)}`
  if (start) return formatDateTime(start)
  if (end) return formatDateTime(end)
  return ''
}

function formatDateTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}
