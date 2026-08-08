import { useCallback, useEffect, useRef, useState } from 'react'
import { Calendar, ExternalLink, Heart, MapPin } from 'lucide-react'

import { ErrorMessage } from '../components/common/ErrorMessage'
import { FavouriteButton } from '../components/common/FavouriteButton'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { useFavourites } from '../hooks/useFavourites'
import { usePageTitle } from '../hooks/usePageTitle'
import { useScrollToHash } from '../hooks/useScrollToHash'
import { trackPageView } from '../services/analytics'
import { fetchNews, fetchNewsSources } from '../services/newsApi'
import { fetchEvents } from '../services/eventsApi'
import { getFavourites } from '../services/favouritesApi'
import { API_BASE_URL } from '../config/api'
import type { ArticleResponse, SourceSummary } from '../types/news'
import type { EventResponse } from '../types/events'

type SourceFilter = 'all' | string

/** Articles per request. Was a single `size=100` fetch — the slowest public page. */
const NEWS_PAGE_SIZE = 24

function resolveImageUrl(url: string | null): string | undefined {
  if (!url) return undefined
  if (url.startsWith('/uploads/')) return `${API_BASE_URL}${url}`
  if (url.startsWith('http')) return url
  return undefined
}

export function NewsEventsPage() {
  const [articles, setArticles] = useState<ArticleResponse[]>([])
  const [newsPageNumber, setNewsPageNumber] = useState(0)
  const [isLastNewsPage, setIsLastNewsPage] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [refreshingNews, setRefreshingNews] = useState(true)
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null)
  const [sources, setSources] = useState<SourceSummary[]>([])
  const [upcomingEvents, setUpcomingEvents] = useState<EventResponse[]>([])
  const [pastEvents, setPastEvents] = useState<EventResponse[]>([])
  const [newsSettled, setNewsSettled] = useState(false)
  const [eventsSettled, setEventsSettled] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [sourceFilter, setSourceFilter] = useState<SourceFilter>('all')

  const [favouritesOnly, setFavouritesOnly] = useState(false)
  const [favouriteArticles, setFavouriteArticles] = useState<ArticleResponse[]>([])
  const [favouriteEvents, setFavouriteEvents] = useState<EventResponse[]>([])
  const [favouritesLoading, setFavouritesLoading] = useState(false)

  const newsFavourites = useFavourites('news')
  const eventFavourites = useFavourites('events')

  // 'all' and 'events' are local-only view modes; only a real source name is a query
  // parameter, so the backend does the filtering and paging continues within a source.
  const activeSource =
    sourceFilter === 'all' || sourceFilter === 'events' ? undefined : sourceFilter

  const loading = !newsSettled || !eventsSettled

  // Discards any news response that has been superseded — switching source while a
  // "Load more" is in flight would otherwise append the previous source's articles.
  const newsRequestId = useRef(0)

  // Scroll to #news / #events once content has loaded (section ids exist).
  useScrollToHash(!loading)
  usePageTitle('News & Events')

  useEffect(() => {
    trackPageView('/news-events')
  }, [])

  useEffect(() => {
    const requestId = newsRequestId.current + 1
    newsRequestId.current = requestId

    setLoadMoreError(null)
    setRefreshingNews(true)
    fetchNews(0, NEWS_PAGE_SIZE, activeSource)
      .then((newsPage) => {
        if (newsRequestId.current !== requestId) return
        setArticles(newsPage.content)
        setNewsPageNumber(newsPage.number)
        setIsLastNewsPage(newsPage.last)
      })
      .catch((err: Error) => {
        if (newsRequestId.current !== requestId) return
        setError(err.message)
      })
      .finally(() => {
        if (newsRequestId.current !== requestId) return
        setRefreshingNews(false)
        setNewsSettled(true)
      })
  }, [activeSource, attempt])

  // Events and the source list are independent of news paging, so they load once.
  useEffect(() => {
    Promise.all([fetchEvents(0, 50, true), fetchEvents(0, 20, false)])
      .then(([upcomingPage, pastPage]) => {
        setUpcomingEvents(upcomingPage.content)
        setPastEvents(pastPage.content)
      })
      .catch((err: Error) => setError(err.message))
      .finally(() => setEventsSettled(true))
  }, [attempt])

  useEffect(() => {
    // Chips list every source the site holds, not just those in the loaded page.
    // A failure here is not fatal: the chips fall back to the loaded articles.
    fetchNewsSources()
      .then(setSources)
      .catch(() => setSources([]))
  }, [attempt])

  const handleLoadMore = () => {
    const requestId = newsRequestId.current + 1
    newsRequestId.current = requestId

    setLoadingMore(true)
    setLoadMoreError(null)
    fetchNews(newsPageNumber + 1, NEWS_PAGE_SIZE, activeSource)
      .then((newsPage) => {
        if (newsRequestId.current !== requestId) return
        // Append, never replace: the container stays put so scroll position holds.
        setArticles((previous) => [...previous, ...newsPage.content])
        setNewsPageNumber(newsPage.number)
        setIsLastNewsPage(newsPage.last)
      })
      .catch((err: Error) => {
        if (newsRequestId.current !== requestId) return
        setLoadMoreError(err.message)
      })
      .finally(() => {
        if (newsRequestId.current !== requestId) return
        setLoadingMore(false)
      })
  }

  const handleSourceSelect = (next: SourceFilter) => {
    setSourceFilter(next)
  }

  const retry = useCallback(() => {
    setError(null)
    setNewsSettled(false)
    setEventsSettled(false)
    setAttempt((value) => value + 1)
  }, [])

  useEffect(() => {
    if (!favouritesOnly) return
    setFavouritesLoading(true)
    Promise.all([
      getFavourites('news', 0, 100),
      getFavourites('events', 0, 100),
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
  }, [favouritesOnly])

  // Favourites are globally shared, so viewing them needs no session — just flip the view.
  const handleFavouritesToggle = () => {
    setFavouritesOnly(prev => !prev)
  }

  if (loading) return <LoadingIndicator message="Loading news and events..." />
  if (error) {
    return <ErrorMessage message={error} onRetry={retry} title="Unable to load News & Events" />
  }

  // Every source the site holds (FR-039), so a source with no article on page 0 is
  // still selectable. Falls back to counting the loaded articles if that request failed.
  const sourceSummaries: SourceSummary[] =
    sources.length > 0
      ? sources
      : Object.entries(
          articles.reduce<Record<string, number>>((counts, a) => {
            counts[a.sourceName] = (counts[a.sourceName] ?? 0) + 1
            return counts
          }, {}),
        ).map(([name, count]) => ({ name, count }))

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
          onClick={() => handleSourceSelect('all')}
          type="button"
        >
          All
        </button>
        {sourceSummaries.map(({ name }) => (
          <button
            className={`feed__pill${sourceFilter === name ? ' feed__pill--active' : ''}`}
            key={name}
            onClick={() => handleSourceSelect(name)}
            type="button"
          >
            {name}
          </button>
        ))}
        {allEvents.length > 0 && (
          <button
            className={`feed__pill feed__pill--events${sourceFilter === 'events' ? ' feed__pill--active' : ''}`}
            onClick={() => handleSourceSelect('events')}
            type="button"
          >
            Events
          </button>
        )}
        <button
          aria-pressed={favouritesOnly}
          className={`feed__pill feed__favourites-toggle${favouritesOnly ? ' feed__pill--active' : ''}`}
          onClick={handleFavouritesToggle}
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

          {/* Backend-driven paging: appends below the grid, so scroll position holds.
              Favourites are a complete in-memory list and are never paged (FR-040). */}
          {!favouritesOnly && sourceFilter !== 'events' && !isLastNewsPage && (
            <div className="feed__load-more">
              <button
                className="button button--secondary"
                // Disabled while a source re-query is in flight: paging off the previous
                // source's page number would append the wrong articles.
                disabled={loadingMore || refreshingNews}
                onClick={handleLoadMore}
                type="button"
              >
                {loadingMore ? 'Loading...' : 'Load more'}
              </button>
            </div>
          )}

          {loadMoreError && (
            <ErrorMessage
              message={loadMoreError}
              onRetry={handleLoadMore}
              title="Unable to load more articles"
            />
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
