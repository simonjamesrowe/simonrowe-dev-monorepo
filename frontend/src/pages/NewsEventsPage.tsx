import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Calendar, ExternalLink, MapPin } from 'lucide-react'

import { ErrorMessage } from '../components/common/ErrorMessage'
import { FavouriteButton } from '../components/common/FavouriteButton'
import { ShareButton } from '../components/common/ShareButton'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { ListenButton } from '../components/narration/ListenButton'
import { useNarrationAudio } from '../components/narration/useNarrationAudio'
import { NewsFilterBar } from '../components/news/NewsFilterBar'
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

/** Articles per request. Was a single `size=100` fetch — the slowest public page. */
const NEWS_PAGE_SIZE = 24

/**
 * How long typing has to stop before the feed is re-queried.
 *
 * <p>Short enough to feel immediate, long enough that a nine-character word is one request
 * rather than nine — each one re-reads page zero and replaces the whole grid.
 */
const SEARCH_DEBOUNCE_MS = 300

function resolveImageUrl(url: string | null): string | undefined {
  if (!url) return undefined
  if (url.startsWith('/uploads/')) return `${API_BASE_URL}${url}`
  if (url.startsWith('http')) return url
  return undefined
}

/**
 * Whether every word of the query appears somewhere in the given fields.
 *
 * <p>Mirrors what the backend does for articles, and is the only implementation for
 * events and for the favourites view — both of those are complete in-memory lists that
 * never go back to the server, so filtering them client-side is not a duplicate of the
 * query, it is the whole of it.
 */
function matchesQuery(query: string, fields: Array<string | null | undefined>): boolean {
  const terms = query.trim().toLowerCase().split(/\s+/).filter(Boolean)
  if (terms.length === 0) return true
  const haystack = fields.filter(Boolean).join(' ').toLowerCase()
  return terms.every(term => haystack.includes(term))
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

  // Empty means every source. A list rather than a single name because the filter is now a
  // set of checkboxes, and the backend takes `source` more than once.
  const [selectedSources, setSelectedSources] = useState<string[]>([])
  // Only the events timeline, no articles. A view mode, not a source.
  const [eventsOnly, setEventsOnly] = useState(false)
  // What is in the box, and what the last request was actually made with. They differ for
  // as long as SEARCH_DEBOUNCE_MS after the last keystroke.
  const [queryInput, setQueryInput] = useState('')
  const [appliedQuery, setAppliedQuery] = useState('')

  const [favouritesOnly, setFavouritesOnly] = useState(false)
  const [favouriteArticles, setFavouriteArticles] = useState<ArticleResponse[]>([])
  const [favouriteEvents, setFavouriteEvents] = useState<EventResponse[]>([])
  const [favouritesLoading, setFavouritesLoading] = useState(false)

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

  // The timer is cleared on every keystroke and on unmount, so a query the visitor has
  // already moved past never lands and nothing is scheduled against a gone component.
  useEffect(() => {
    if (queryInput === appliedQuery) return
    const timer = window.setTimeout(() => setAppliedQuery(queryInput), SEARCH_DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [queryInput, appliedQuery])

  useEffect(() => {
    const requestId = newsRequestId.current + 1
    newsRequestId.current = requestId

    setLoadMoreError(null)
    setRefreshingNews(true)
    fetchNews(0, NEWS_PAGE_SIZE, { sources: selectedSources, query: appliedQuery })
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
    // `selectedSources` is state, so its identity only changes when the selection does.
  }, [selectedSources, appliedQuery, attempt])

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
    fetchNews(newsPageNumber + 1, NEWS_PAGE_SIZE, {
      sources: selectedSources,
      query: appliedQuery,
    })
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

  /** Puts the feed back to every source, no text, everything visible. */
  const clearFilters = () => {
    setSelectedSources([])
    setQueryInput('')
    setAppliedQuery('')
    setEventsOnly(false)
    setFavouritesOnly(false)
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

  // Unfavouriting while in favourites-only mode removes the card immediately.
  const visibleArticles = favouritesOnly
    ? favouriteArticles.filter(a => newsFavourites.isFavourite(a.id))
    : articles

  // The source and text filters are the backend's work on the ordinary feed — it is what
  // makes "Load more" page through the matching set rather than the whole one. Favourites
  // never go through that query, so they are the one list filtered here.
  const filtered = eventsOnly
    ? []
    : favouritesOnly
      ? visibleArticles.filter(a =>
          (selectedSources.length === 0 || selectedSources.includes(a.sourceName))
          && matchesQuery(appliedQuery, [a.title, a.summary, a.author, a.sourceName]))
      : visibleArticles

  // Looked up across both the loaded list and the favourites list, so the drawer survives
  // a switch into favourites-only mode while it is open.
  const summaryArticle = summaryArticleId
    ? [...articles, ...favouriteArticles, ...deepLinkedArticles]
        .find(a => a.id === summaryArticleId) ?? null
    : null

  // Events carry a source of their own — Meetup, lu.ma — that the source list, built from
  // articles, does not contain. Picking a publisher therefore means "articles from these
  // publishers" and hides the timeline, exactly as selecting a pill used to.
  const showEvents = eventsOnly || selectedSources.length === 0
  const eventMatchesQuery = (event: EventResponse) =>
    matchesQuery(appliedQuery, [
      event.title, event.summary, event.venue, event.location, event.sourceName,
    ])
  const trimmedQuery = appliedQuery.trim()
  const filtersApplied = selectedSources.length > 0 || trimmedQuery.length > 0 || eventsOnly
  // Naming what was filtered on is the difference between "there is nothing here" and
  // "there is nothing here *because of what you typed*", which is the only version a
  // visitor can act on.
  const emptyFilteredMessage = trimmedQuery
    ? selectedSources.length > 0
      ? `Nothing matches “${trimmedQuery}” in ${selectedSources.join(', ')}.`
      : `Nothing matches “${trimmedQuery}”.`
    : `No articles from ${selectedSources.join(', ')} yet.`
  const featured = filtered.slice(0, 2)
  const grid = filtered.slice(2)
  // A shared event has to have a card — unlike an article it has no drawer, so the card is
  // the destination. The timeline shows only upcoming events, so one arrived at by link is
  // appended whether it is upcoming, past, or off the loaded page entirely.
  const withDeepLinkedEvents = (events: EventResponse[]) => {
    const extra = deepLinkedEvents.filter(e => !events.some(loaded => loaded.id === e.id))
    return extra.length > 0 ? [...events, ...extra] : events
  }

  // Drives whether the Events toggle is offered at all, so it counts what the feed holds
  // rather than what the current query matched — a toggle that vanished as you typed
  // would take the way back out with it.
  const allEvents = withDeepLinkedEvents(favouritesOnly
    ? favouriteEvents.filter(e => eventFavourites.isFavourite(e.id))
    : [...upcomingEvents, ...pastEvents])
  const timelineEvents = withDeepLinkedEvents(favouritesOnly
    ? favouriteEvents.filter(e => eventFavourites.isFavourite(e.id))
    : upcomingEvents).filter(eventMatchesQuery)

  // A search that matched nothing anywhere gets one empty state, not two: the timeline's
  // own "no events match" line above the feed's "nothing matches" line reads as a page
  // that half-loaded. It stays for the useful case — articles found, events not — and in
  // events-only mode, where it is the only thing that can answer.
  const nothingMatches = filtered.length === 0 && timelineEvents.length === 0
  const showEventsSection = showEvents && allEvents.length > 0
    && (eventsOnly || !(trimmedQuery && nothingMatches))

  return (
    <div className="feed tour-news-events">
      <NewsFilterBar
        eventsOnly={eventsOnly}
        favouritesOnly={favouritesOnly}
        onEventsOnlyChange={setEventsOnly}
        onFavouritesOnlyChange={setFavouritesOnly}
        onQueryChange={setQueryInput}
        onSelectedSourcesChange={setSelectedSources}
        query={queryInput}
        selectedSources={selectedSources}
        showEventsToggle={allEvents.length > 0}
        sources={sortedSources}
      />

      {/* Anchor target for /news-events#news deep links */}
      <div id="news" className="feed__anchor" aria-hidden="true" />

      {favouritesOnly && favouritesLoading ? (
        <LoadingIndicator message="Loading favourites..." />
      ) : (
        <>
          {/* Featured hero section */}
          {!eventsOnly && featured.length > 0 && (
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
          {!eventsOnly && grid.length > 0 && (
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
          {!favouritesOnly && !eventsOnly && !isLastNewsPage && (
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
          {showEventsSection && (
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
              {/* The timeline shows upcoming events only, so it can be empty while the
                  feed still holds events — and now also when the query matched none of
                  them. Saying which is the difference between a page that looks broken
                  and one that is telling you what it did. */}
              {timelineEvents.length === 0 && (
                <div className="feed__events-empty">
                  {trimmedQuery ? (
                    <p>No upcoming events match &ldquo;{trimmedQuery}&rdquo;.</p>
                  ) : (
                    <p>We are currently scraping the next set of workshops and webinars. Stay tuned for updates.</p>
                  )}
                </div>
              )}
            </div>
          )}

          {/* Empty state. Three different nothings: nothing saved, nothing matching, and
              nothing here yet — and only the middle one has an action worth offering. */}
          {filtered.length === 0 && !eventsOnly && (
            <div className="feed__empty">
              {favouritesOnly && !filtersApplied ? (
                <p>No favourites yet. Tap the heart on any article or event to save it here.</p>
              ) : filtersApplied ? (
                <>
                  <p>{emptyFilteredMessage}</p>
                  <button className="button button--secondary" onClick={clearFilters} type="button">
                    Clear filters
                  </button>
                </>
              ) : (
                <p>No articles yet. Check back soon!</p>
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
