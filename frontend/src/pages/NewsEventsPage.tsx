import { useEffect, useState } from 'react'
import { Calendar, ExternalLink, Heart, MapPin } from 'lucide-react'

import { ErrorMessage } from '../components/common/ErrorMessage'
import { FavouriteButton } from '../components/common/FavouriteButton'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { useAuth } from '../auth/useAuth'
import { useFavourites } from '../hooks/useFavourites'
import { useScrollToHash } from '../hooks/useScrollToHash'
import { trackPageView } from '../services/analytics'
import { fetchNews } from '../services/newsApi'
import { fetchEvents } from '../services/eventsApi'
import { getFavourites } from '../services/favouritesApi'
import { API_BASE_URL } from '../config/api'
import type { ArticleResponse } from '../types/news'
import type { EventResponse } from '../types/events'

type SourceFilter = 'all' | string

function resolveImageUrl(url: string | null): string | undefined {
  if (!url) return undefined
  if (url.startsWith('/uploads/')) return `${API_BASE_URL}${url}`
  if (url.startsWith('http')) return url
  return undefined
}

export function NewsEventsPage() {
  const [articles, setArticles] = useState<ArticleResponse[]>([])
  const [upcomingEvents, setUpcomingEvents] = useState<EventResponse[]>([])
  const [pastEvents, setPastEvents] = useState<EventResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [sourceFilter, setSourceFilter] = useState<SourceFilter>('all')

  const [favouritesOnly, setFavouritesOnly] = useState(false)
  const [favouriteArticles, setFavouriteArticles] = useState<ArticleResponse[]>([])
  const [favouriteEvents, setFavouriteEvents] = useState<EventResponse[]>([])
  const [favouritesLoading, setFavouritesLoading] = useState(false)

  const { getAccessToken } = useAuth()
  const newsFavourites = useFavourites('news')
  const eventFavourites = useFavourites('events')

  // Scroll to #news / #events once content has loaded (section ids exist).
  useScrollToHash(!loading)

  useEffect(() => {
    trackPageView('/news-events')
    document.title = 'News & Events'
  }, [])

  useEffect(() => {
    Promise.all([
      fetchNews(0, 100),
      fetchEvents(0, 50, true),
      fetchEvents(0, 20, false),
    ])
      .then(([newsPage, upcomingPage, pastPage]) => {
        setArticles(newsPage.content)
        setUpcomingEvents(upcomingPage.content)
        setPastEvents(pastPage.content)
      })
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    if (!favouritesOnly) return
    setFavouritesLoading(true)
    Promise.all([
      getFavourites(getAccessToken, 'news', 0, 100),
      getFavourites(getAccessToken, 'events', 0, 100),
    ])
      .then(([newsPage, eventsPage]) => {
        setFavouriteArticles(newsPage.content)
        setFavouriteEvents(eventsPage.content)
      })
      .catch(() => {
        setFavouriteArticles([])
        setFavouriteEvents([])
      })
      .finally(() => setFavouritesLoading(false))
  }, [favouritesOnly, getAccessToken])

  const handleFavouritesToggle = async () => {
    if (favouritesOnly) {
      setFavouritesOnly(false)
      return
    }
    // Logged out this runs the login popup first; cancelling stays on the normal feed.
    if (await newsFavourites.ensureAuthenticated()) {
      setFavouritesOnly(true)
    }
  }

  if (loading) return <LoadingIndicator message="Loading news and events..." />
  if (error) return <ErrorMessage message={error} />

  // Get unique sources for filter pills
  const sources = [...new Set(articles.map(a => a.sourceName))]

  // Unfavouriting while in favourites-only mode removes the card immediately.
  const visibleArticles = favouritesOnly
    ? favouriteArticles.filter(a => newsFavourites.isFavourite(a.id))
    : articles

  // Filter articles by source
  const filtered = sourceFilter === 'all'
    ? visibleArticles
    : sourceFilter === 'events'
    ? [] // show events timeline instead
    : visibleArticles.filter(a => a.sourceName === sourceFilter)

  const showEvents = sourceFilter === 'all' || sourceFilter === 'events'
  const featured = filtered.slice(0, 2)
  const grid = filtered.slice(2)
  const timelineEvents = favouritesOnly
    ? favouriteEvents.filter(e => eventFavourites.isFavourite(e.id))
    : upcomingEvents
  const allEvents = favouritesOnly ? timelineEvents : [...upcomingEvents, ...pastEvents]

  return (
    <div className="feed tour-news-events">
      {/* Source filter pills */}
      <div className="feed__filters">
        <button
          className={`feed__pill${sourceFilter === 'all' ? ' feed__pill--active' : ''}`}
          onClick={() => setSourceFilter('all')}
          type="button"
        >
          All
        </button>
        {sources.map(source => (
          <button
            className={`feed__pill${sourceFilter === source ? ' feed__pill--active' : ''}`}
            key={source}
            onClick={() => setSourceFilter(source)}
            type="button"
          >
            {source}
          </button>
        ))}
        {allEvents.length > 0 && (
          <button
            className={`feed__pill feed__pill--events${sourceFilter === 'events' ? ' feed__pill--active' : ''}`}
            onClick={() => setSourceFilter('events')}
            type="button"
          >
            Events
          </button>
        )}
        <button
          aria-pressed={favouritesOnly}
          className={`feed__pill feed__favourites-toggle${favouritesOnly ? ' feed__pill--active' : ''}`}
          onClick={() => void handleFavouritesToggle()}
          type="button"
        >
          <Heart aria-hidden="true" fill={favouritesOnly ? 'currentColor' : 'none'} size={14} />
          <span>Show favourites only</span>
        </button>
      </div>

      {/* Anchor target for /news-events#news deep links */}
      <div id="news" className="feed__anchor" aria-hidden="true" />

      {favouritesOnly && favouritesLoading ? (
        <LoadingIndicator message="Loading favourites..." />
      ) : (
        <>
          {/* Featured hero section */}
          {sourceFilter !== 'events' && featured.length > 0 && (
            <div className="feed__hero">
              {featured.map((article, i) => (
                <a
                  className={`feed__hero-card${i === 0 ? ' feed__hero-card--primary' : ' feed__hero-card--secondary'}`}
                  href={article.originalUrl}
                  key={article.id}
                  rel="noopener noreferrer"
                  target="_blank"
                >
                  {resolveImageUrl(article.imageUrl) ? (
                    <img
                      alt=""
                      className="feed__hero-image"
                      src={resolveImageUrl(article.imageUrl)}
                      onError={(e) => {
                        (e.target as HTMLImageElement).style.display = 'none';
                        (e.target as HTMLImageElement).parentElement!.classList.add('feed__hero-image--fallback');
                      }}
                    />
                  ) : (
                    <div className="feed__hero-image feed__hero-image--fallback">
                      <div className="feed__hero-image-placeholder">
                        {article.sourceName.substring(0, 2).toUpperCase()}
                      </div>
                    </div>
                  )}
                  <FavouriteButton
                    active={newsFavourites.isFavourite(article.id)}
                    className="feed__favourite"
                    label={article.title}
                    onClick={() => void newsFavourites.toggleFavourite(article.id)}
                  />
                  <div className="feed__hero-overlay">
                    <span className="feed__source-badge">{article.sourceName}</span>
                    <h2 className="feed__hero-title">{article.title}</h2>
                    <div className="feed__hero-meta">
                      {article.author && <span>{article.author}</span>}
                      {article.publishedDate && (
                        <span>{new Date(article.publishedDate).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })}</span>
                      )}
                    </div>
                  </div>
                </a>
              ))}
            </div>
          )}

          {/* Article grid */}
          {sourceFilter !== 'events' && grid.length > 0 && (
            <div className="feed__grid">
              {grid.map(article => (
                <a
                  className="feed__card"
                  href={article.originalUrl}
                  key={article.id}
                  rel="noopener noreferrer"
                  target="_blank"
                >
                  <div className="feed__card-image">
                    {resolveImageUrl(article.imageUrl) ? (
                      <img
                        alt=""
                        src={resolveImageUrl(article.imageUrl)}
                        onError={(e) => {
                          (e.target as HTMLImageElement).style.display = 'none';
                          (e.target as HTMLImageElement).parentElement!.classList.add('feed__card-image--fallback');
                        }}
                      />
                    ) : (
                      <div className="feed__card-image-placeholder">
                        {article.sourceName.substring(0, 2).toUpperCase()}
                      </div>
                    )}
                  </div>
                  <FavouriteButton
                    active={newsFavourites.isFavourite(article.id)}
                    className="feed__favourite"
                    label={article.title}
                    onClick={() => void newsFavourites.toggleFavourite(article.id)}
                  />
                  <div className="feed__card-body">
                    <span className="feed__source-badge">{article.sourceName}</span>
                    <h3 className="feed__card-title">{article.title}</h3>
                    {article.summary && (
                      <p className="feed__card-summary">{article.summary}</p>
                    )}
                    <div className="feed__card-meta">
                      {article.publishedDate && (
                        <span>{new Date(article.publishedDate).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })}</span>
                      )}
                      <span className="feed__card-readmore">Read article <ExternalLink size={12} /></span>
                    </div>
                  </div>
                </a>
              ))}
            </div>
          )}

          {/* Events timeline */}
          {showEvents && allEvents.length > 0 && (
            <div id="events" className="feed__events">
              <h2 className="feed__events-title">Timeline</h2>
              {timelineEvents.length > 0 && (
                <div className="feed__timeline">
                  {timelineEvents.map(event => (
                    <a
                      className="feed__timeline-item"
                      href={event.originalUrl}
                      key={event.id}
                      rel="noopener noreferrer"
                      target="_blank"
                    >
                      <div className="feed__timeline-dot" />
                      <div className="feed__timeline-content">
                        <FavouriteButton
                          active={eventFavourites.isFavourite(event.id)}
                          className="feed__favourite feed__favourite--timeline"
                          label={event.title}
                          onClick={() => void eventFavourites.toggleFavourite(event.id)}
                        />
                        <span className="feed__source-badge">{event.sourceName}</span>
                        <h3 className="feed__timeline-title">{event.title}</h3>
                        <div className="feed__timeline-meta">
                          <Calendar size={14} />
                          <span>{new Date(event.eventDate).toLocaleDateString('en-GB', {
                            weekday: 'short', day: 'numeric', month: 'short', year: 'numeric',
                            hour: '2-digit', minute: '2-digit'
                          })}</span>
                        </div>
                        {event.venue && (
                          <div className="feed__timeline-meta">
                            <MapPin size={14} />
                            <span>{event.venue}{event.location ? `, ${event.location}` : ''}</span>
                          </div>
                        )}
                        {event.summary && (
                          <p className="feed__timeline-summary">{event.summary}</p>
                        )}
                      </div>
                    </a>
                  ))}
                </div>
              )}
              {allEvents.length === 0 && (
                <div className="feed__events-empty">
                  <p>We are currently scraping the next set of workshops and webinars. Stay tuned for updates.</p>
                </div>
              )}
            </div>
          )}

          {/* Empty state */}
          {filtered.length === 0 && sourceFilter !== 'events' && (
            <div className="feed__empty">
              {favouritesOnly ? (
                <p>No favourites yet. Tap the heart on any article or event to save it here.</p>
              ) : (
                <p>No articles from this source yet. Check back soon!</p>
              )}
            </div>
          )}
        </>
      )}
    </div>
  )
}
