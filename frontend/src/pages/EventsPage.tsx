import { useEffect, useState } from 'react'
import { Calendar, ExternalLink, MapPin } from 'lucide-react'

import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { fetchEvents } from '../services/eventsApi'
import { trackPageView } from '../services/analytics'
import type { EventResponse } from '../types/events'

export function EventsPage() {
  const [upcoming, setUpcoming] = useState<EventResponse[]>([])
  const [past, setPast] = useState<EventResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    trackPageView('/events')
    document.title = 'Events'
  }, [])

  useEffect(() => {
    Promise.all([
      fetchEvents(0, 50, true),
      fetchEvents(0, 20, false),
    ])
      .then(([upcomingPage, pastPage]) => {
        setUpcoming(upcomingPage.content)
        setPast(pastPage.content)
      })
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <LoadingIndicator />
  if (error) return <ErrorMessage message={error} />

  return (
    <div className="events-page">
      <h1 className="events-page__title">Events</h1>

      <section className="events-page__section">
        <h2 className="events-page__section-title">Upcoming</h2>
        {upcoming.length === 0 ? (
          <p className="events-page__empty">No upcoming events at this time.</p>
        ) : (
          <div className="events-page__grid">
            {upcoming.map((event) => (
              <EventCard key={event.id} event={event} />
            ))}
          </div>
        )}
      </section>

      {past.length > 0 && (
        <section className="events-page__section">
          <h2 className="events-page__section-title">Past Events</h2>
          <div className="events-page__grid">
            {past.map((event) => (
              <EventCard key={event.id} event={event} />
            ))}
          </div>
        </section>
      )}
    </div>
  )
}

function EventCard({ event }: { event: EventResponse }) {
  const dateStr = new Date(event.eventDate).toLocaleDateString('en-GB', {
    weekday: 'short',
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })

  return (
    <a
      className="event-card"
      href={event.originalUrl}
      target="_blank"
      rel="noopener noreferrer"
    >
      <span className="event-card__source">{event.sourceName}</span>
      <h3 className="event-card__title">{event.title}</h3>
      <div className="event-card__detail">
        <Calendar size={14} />
        <span>{dateStr}</span>
      </div>
      {event.venue && (
        <div className="event-card__detail">
          <MapPin size={14} />
          <span>
            {event.venue}
            {event.location ? `, ${event.location}` : ''}
          </span>
        </div>
      )}
      {event.summary && (
        <p className="event-card__description">{event.summary}</p>
      )}
      <span className="event-card__link">
        View on {event.sourceName} <ExternalLink size={12} />
      </span>
    </a>
  )
}
