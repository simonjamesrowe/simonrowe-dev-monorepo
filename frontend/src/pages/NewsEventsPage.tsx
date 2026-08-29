import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Calendar, ChevronDown, ExternalLink, Heart, MapPin } from 'lucide-react'

import { ErrorMessage } from '../components/common/ErrorMessage'
import { FavouriteButton } from '../components/common/FavouriteButton'
import { ShareButton } from '../components/common/ShareButton'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { ListenButton } from '../components/narration/ListenButton'
import { useNarrationAudio } from '../components/narration/useNarrationAudio'
import { NewsSummaryDrawer } from '../components/news/NewsSummaryDrawer'
import { SummaryNarration } from '../components/news/SummaryNarration'
import { SummaryButton } from '../components/news/SummaryButton'
import { useArticleSummaries } from '../hooks/useArticleSummaries'
import { useFavourites } from '../hooks/useFavourites'
import { usePageTitle } from '../hooks/usePageTitle'
import { useScrollToHash } from '../hooks/useScrollToHash'
import { trackPageView } from '../services/analytics'
import { fetchNews, fetchNewsById, fetchNewsSources } from '../services/newsApi'
import { fetchEvents, fetchEventsById } from '../services/eventsApi'
import { getFavourites } from '../services/favouritesApi'
import { API_BASE_URL } from '../config/api'
import type { ArticleResponse, SourceSummary } from '../types/news'
import type { EventResponse } from '../types/events'

type SourceFilter = 'all' | string

/** Articles per request. Was a single `size=100` fetch — the slowest public page. */
const NEWS_PAGE_SIZE = 24

/**
 * Below this, a source is a long-tail one-off — usually a single manually imported
 * article — and goes in the "More" menu instead of costing a pill in the main row.
 */
const MIN_ARTICLES_FOR_PILL = 3

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
  const [moreOpen, setMoreOpen] = useState(false)
  const moreMenuRef = useRef<HTMLDivElement>(null)
  const moreToggleRef = useRef<HTMLButtonElement>(null)

  const newsFavourites = useFavourites('news')
  const eventFavourites = useFavourites('events')

  // Articles and events reached by a shared link but absent from the loaded page. Without
  // these, opening a link to something that has since fallen off page one loads the page
  // and then silently does nothing — the failure this feature is most likely to have.
  const [deepLinkedArticles, setDeepLinkedArticles] = useState<ArticleResponse[]>([])
  const [deepLinkedEvents, setDeepLinkedEvents] = useState<EventResponse[]>([])
  const [searchParams, setSearchParams] = useSearchParams()
  const sharedArticleId = searchParams.get('article')
  const sharedEventId = searchParams.get('event')

  const summaries = useArticleSummaries()
  // The article whose summary drawer is open, or null. Held as an id rather than the
  // article object so a reload of the list cannot leave a stale copy on screen.
  const [summaryArticleId, setSummaryArticleId] = useState<string | null>(null)

  const { lastCompleted } = useNarrationAudio()

  // The docked player's Listen chain can generate a summary as an intermediate step, and it
  // sits above this page in the tree so it cannot write to `useArticleSummaries` itself. It
  // publishes what finished; this relays it, so the card flips from "Summarise" to "Read
  // summary" without refetching the whole ids set.
  useEffect(() => {
    if (lastCompleted?.contentType === 'ARTICLE_SUMMARY' && lastCompleted.summaryWasGenerated) {
      summaries.noteSummarised(lastCompleted.contentId)
    }
  }, [lastCompleted, summaries])

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
    const handleClickOutside = (e: MouseEvent) => {
      if (moreMenuRef.current && !moreMenuRef.current.contains(e.target as Node)) {
        setMoreOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  // The toggle advertises aria-haspopup, so Escape has to close it and hand focus back —
  // otherwise a keyboard user who opens it just tabs on into the page with it still open.
  useEffect(() => {
    if (!moreOpen) return
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setMoreOpen(false)
        moreToggleRef.current?.focus()
      }
    }
    document.addEventListener('keydown', handleEscape)
    return () => document.removeEventListener('keydown', handleEscape)
  }, [moreOpen])

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

  /**
   * Opens the summary drawer. An article that already has a summary just reads it — no
   * session, no prompt, because the artefact is globally shared. One that does not runs
   * the sign-in popup first, inside `requestSummary`.
   */
  const handleSummaryOpen = (article: ArticleResponse) => {
    setSummaryArticleId(article.id)
    if (summaries.hasSummary(article.id)) {
      void summaries.loadSummary(article.id)
    } else {
      void summaries.requestSummary(article.id)
    }
  }

  // Closing aborts any in-flight poll and unmounts the drawer — which unmounts the audio
  // element with it, so playback stops without any extra handling. It also drops the
  // ?article= parameter, so the drawer does not spring back open on a reload or a Back.
  const handleSummaryClose = () => {
    if (summaryArticleId) summaries.cancel(summaryArticleId)
    setSummaryArticleId(null)
    if (sharedArticleId) {
      const next = new URLSearchParams(searchParams)
      next.delete('article')
      setSearchParams(next, { replace: true })
    }
  }

  /**
   * Opens the drawer for an article arrived at from a shared link.
   *
   * <p>Runs once per id. `hasSummary` cannot be trusted yet on first paint — the ids set
   * may still be loading — so this deliberately mirrors `handleSummaryOpen` rather than
   * calling it: `loadSummary` is a plain read, and `requestSummary` would put a sign-in
   * popup in front of someone who has just followed a link.
   */
  useEffect(() => {
    if (!sharedArticleId) return
    setSummaryArticleId(sharedArticleId)
    void summaries.loadSummary(sharedArticleId)
    // Intentionally keyed on the id alone: re-running when `summaries` changes identity
    // would reopen a drawer the visitor has just closed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sharedArticleId])

  /**
   * Fetches a shared article that is not in the loaded page.
   *
   * <p>This is the case a shared link hits most often once a link is a few weeks old: page
   * one holds 24 articles, and everything older has to be fetched by id or the page loads
   * and quietly does nothing.
   */
  useEffect(() => {
    // Gated on the list having settled: firing before it arrives would fetch by id on
    // every shared link, including the common case where the article is on page one.
    if (!sharedArticleId || !newsSettled) return
    const alreadyLoaded = [...articles, ...favouriteArticles, ...deepLinkedArticles]
      .some(a => a.id === sharedArticleId)
    if (alreadyLoaded) return

    let cancelled = false
    fetchNewsById(sharedArticleId)
      .then(article => {
        if (!cancelled) setDeepLinkedArticles(previous => [...previous, article])
      })
      // A deleted or hidden article is not worth an error banner over the whole page —
      // the drawer simply does not open, and the rest of the feed still works.
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [sharedArticleId, newsSettled, articles, favouriteArticles, deepLinkedArticles])

  /**
   * Same for a shared event. Events have no drawer, so this exists to put the card on the
   * page for `useScrollToHash` to find.
   */
  useEffect(() => {
    if (!sharedEventId || !eventsSettled) return
    const alreadyLoaded = [...upcomingEvents, ...pastEvents, ...deepLinkedEvents]
      .some(e => e.id === sharedEventId)
    if (alreadyLoaded) return

    let cancelled = false
    fetchEventsById(sharedEventId)
      .then(event => {
        if (!cancelled) setDeepLinkedEvents(previous => [...previous, event])
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [sharedEventId, eventsSettled, upcomingEvents, pastEvents, deepLinkedEvents])

  /**
   * Scrolls a shared item into view once it is on the page.
   *
   * <p>`useScrollToHash` handles the `#news` / `#events` anchors, but a share link carries
   * a query parameter rather than a hash, so this does the equivalent for the card itself.
   */
  useEffect(() => {
    const targetId = sharedArticleId ?? sharedEventId
    if (!targetId || loading) return
    const element = document.getElementById(targetId)
    // Feature-checked rather than assumed: scrollIntoView is absent in jsdom and in a
    // handful of stripped-down clients, and a shared link must not die on a nicety.
    if (typeof element?.scrollIntoView === 'function') {
      element.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
  }, [sharedArticleId, sharedEventId, loading, articles, deepLinkedArticles, deepLinkedEvents])

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

  // Sorted here rather than trusted from the API so the order holds for the
  // article-derived fallback too.
  const sortedSources = [...sourceSummaries].sort(
    (a, b) => b.count - a.count || a.name.localeCompare(b.name),
  )
  const pillSources = sortedSources.filter(s => s.count >= MIN_ARTICLES_FOR_PILL)
  const menuSources = sortedSources.filter(s => s.count < MIN_ARTICLES_FOR_PILL)
  // A collapsed source that is the active filter has to surface somewhere, or the
  // page looks unfiltered while showing one source's articles.
  const activeMenuSource = menuSources.find(s => s.name === sourceFilter)

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

  // Looked up across both the loaded list and the favourites list, so the drawer survives
  // a switch into favourites-only mode while it is open.
  const summaryArticle = summaryArticleId
    ? [...articles, ...favouriteArticles, ...deepLinkedArticles]
        .find(a => a.id === summaryArticleId) ?? null
    : null

  const showEvents = sourceFilter === 'all' || sourceFilter === 'events'
  const featured = filtered.slice(0, 2)
  const grid = filtered.slice(2)
  // A shared event has to have a card — unlike an article it has no drawer, so the card is
  // the destination. The timeline shows only upcoming events, so one arrived at by link is
  // appended whether it is upcoming, past, or off the loaded page entirely.
  const withDeepLinkedEvents = (events: EventResponse[]) => {
    const extra = deepLinkedEvents.filter(e => !events.some(loaded => loaded.id === e.id))
    return extra.length > 0 ? [...events, ...extra] : events
  }

  const timelineEvents = withDeepLinkedEvents(favouritesOnly
    ? favouriteEvents.filter(e => eventFavourites.isFavourite(e.id))
    : upcomingEvents)
  const allEvents = favouritesOnly
    ? timelineEvents
    : withDeepLinkedEvents([...upcomingEvents, ...pastEvents])

  return (
    <div className="feed tour-news-events">
      {/* View modes, kept apart from sources so "Events" doesn't read as a publisher. */}
      <div className="feed__modes">
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

      {/* Source filter pills, busiest first, long tail collapsed.
          The mobile horizontal scroller lives on the wrapper, not on the pill row:
          overflow-x on the row itself makes it a clipping context, which cut the
          absolutely positioned More popover down to a sliver at phone widths. */}
      <div className="feed__filters-scroll">
        <div className="feed__filters">
          <button
            className={`feed__pill${sourceFilter === 'all' ? ' feed__pill--active' : ''}`}
            onClick={() => handleSourceSelect('all')}
            type="button"
          >
            All
          </button>
          {pillSources.map(({ name }) => (
            <button
              className={`feed__pill${sourceFilter === name ? ' feed__pill--active' : ''}`}
              key={name}
              onClick={() => handleSourceSelect(name)}
              type="button"
            >
              {name}
            </button>
          ))}
          {menuSources.length > 0 && (
            <div className="feed__more" ref={moreMenuRef}>
              <button
                aria-expanded={moreOpen}
                aria-haspopup="true"
                className={`feed__pill feed__more-toggle${activeMenuSource ? ' feed__pill--active' : ''}`}
                onClick={() => setMoreOpen(open => !open)}
                ref={moreToggleRef}
                type="button"
              >
                <span>{activeMenuSource ? activeMenuSource.name : `More (${menuSources.length})`}</span>
                <ChevronDown aria-hidden="true" size={14} />
              </button>
              {moreOpen && (
                /* Plain buttons, not role="menu"/"menuitem": an explicit menuitem role
                   would stop these matching getByRole('button'), and the popover is a
                   list of filters rather than an application menu. */
                <div className="feed__more-menu">
                  {menuSources.map(({ name, count }) => (
                    <button
                      className={`feed__more-item${sourceFilter === name ? ' feed__more-item--active' : ''}`}
                      key={name}
                      onClick={() => {
                        handleSourceSelect(name)
                        setMoreOpen(false)
                      }}
                      type="button"
                    >
                      <span>{name}</span>
                      <span className="feed__more-count">{count}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
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
                  /* The card's own id, so a shared link can scroll to it. Only the #news
                     and #events sections carried one before. */
                  id={article.id}
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
                  <div className="feed__card-actions">
                    <ListenButton
                      contentId={article.id}
                      contentType="ARTICLE_SUMMARY"
                      external
                      href={article.originalUrl}
                      title={article.title}
                    />
                    <SummaryButton
                      articleTitle={article.title}
                      hasSummary={summaries.hasSummary(article.id)}
                      onClick={() => handleSummaryOpen(article)}
                    />
                    <FavouriteButton
                      active={newsFavourites.isFavourite(article.id)}
                      label={article.title}
                      onClick={() => void newsFavourites.toggleFavourite(article.id)}
                    />
                    {/* Fourth control on a card whose job is a headline and an image. The
                        labels collapse to icons under 30rem rather than any of these being
                        dropped — see .feed__card-actions in styles.css. */}
                    {article.shortUrl && (
                      <ShareButton title={article.title} url={article.shortUrl} />
                    )}
                  </div>
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
                  id={article.id}
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
                  <div className="feed__card-actions">
                    <ListenButton
                      contentId={article.id}
                      contentType="ARTICLE_SUMMARY"
                      external
                      href={article.originalUrl}
                      title={article.title}
                    />
                    <SummaryButton
                      articleTitle={article.title}
                      hasSummary={summaries.hasSummary(article.id)}
                      onClick={() => handleSummaryOpen(article)}
                    />
                    <FavouriteButton
                      active={newsFavourites.isFavourite(article.id)}
                      label={article.title}
                      onClick={() => void newsFavourites.toggleFavourite(article.id)}
                    />
                    {/* Fourth control on a card whose job is a headline and an image. The
                        labels collapse to icons under 30rem rather than any of these being
                        dropped — see .feed__card-actions in styles.css. */}
                    {article.shortUrl && (
                      <ShareButton title={article.title} url={article.shortUrl} />
                    )}
                  </div>
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
                      id={event.id}
                      key={event.id}
                      rel="noopener noreferrer"
                      target="_blank"
                    >
                      <div className="feed__timeline-dot" />
                      <div className="feed__timeline-content">
                        <div className="feed__timeline-actions">
                          {event.shortUrl && (
                            <ShareButton title={event.title} url={event.shortUrl} />
                          )}
                          <FavouriteButton
                            active={eventFavourites.isFavourite(event.id)}
                            label={event.title}
                            onClick={() => void eventFavourites.toggleFavourite(event.id)}
                          />
                        </div>
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

      {summaryArticle && (
        <NewsSummaryDrawer
          article={summaryArticle}
          /* Only offered once there is a summary to narrate — the backend has nothing to
             synthesise before that, and the panel would just report a 404. */
          audioPanel={
            summaries.summaryFor(summaryArticle.id)?.state === 'READY' ? (
              <SummaryNarration
                articleId={summaryArticle.id}
                articleTitle={summaryArticle.title}
              />
            ) : undefined
          }
          delayed={summaries.isDelayed(summaryArticle.id)}
          error={summaries.errorFor(summaryArticle.id)}
          isFavourite={newsFavourites.isFavourite(summaryArticle.id)}
          loading={summaries.isLoading(summaryArticle.id)}
          onClose={handleSummaryClose}
          onRetry={() => void summaries.requestSummary(summaryArticle.id)}
          onToggleFavourite={() => void newsFavourites.toggleFavourite(summaryArticle.id)}
          summary={summaries.summaryFor(summaryArticle.id)}
        />
      )}
    </div>
  )
}
