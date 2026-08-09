import { useCallback, useEffect, useState } from 'react'

import { Eye, EyeOff, Trash2 } from 'lucide-react'

import { useAuth } from '../../auth/useAuth'
import {
  fetchAdminNews,
  toggleArticleVisibility,
  deleteArticle,
  fetchAdminEvents,
  toggleEventVisibility,
  deleteEvent,
  triggerAggregation,
  triggerDigest,
  triggerSearchSync,
  triggerEmbeddingSync,
  importArticleUrl,
  type AdminNewsArticle,
  type AdminEvent,
  type PageResponse,
} from '../../services/adminApi'
import { AdminMenu } from '../../components/admin/AdminMenu'
import { ConfirmDialog } from '../../components/admin/ConfirmDialog'

type ActiveTab = 'news' | 'events'

const DIGEST_CONFIRM_MESSAGE =
  'This will use AI to write a new blog post summarising recent blogs and articles, ' +
  'and publish it live on the site immediately. Continue?'

export function AggregatedContentAdmin() {
  const { getAccessToken } = useAuth()

  const [activeTab, setActiveTab] = useState<ActiveTab>('news')

  const [news, setNews] = useState<PageResponse<AdminNewsArticle> | null>(null)
  const [newsPage, setNewsPage] = useState(0)
  const [newsLoading, setNewsLoading] = useState(true)
  const [newsError, setNewsError] = useState<string | null>(null)

  const [events, setEvents] = useState<PageResponse<AdminEvent> | null>(null)
  const [eventsPage, setEventsPage] = useState(0)
  const [eventsLoading, setEventsLoading] = useState(true)
  const [eventsError, setEventsError] = useState<string | null>(null)

  const [aggregationTriggering, setAggregationTriggering] = useState(false)
  const [aggregationSuccess, setAggregationSuccess] = useState<string | null>(null)
  const [aggregationError, setAggregationError] = useState<string | null>(null)
  const [digestTriggering, setDigestTriggering] = useState(false)
  const [digestConfirmOpen, setDigestConfirmOpen] = useState(false)
  const [importUrl, setImportUrl] = useState('')
  const [importLoading, setImportLoading] = useState(false)

  const [deleteTarget, setDeleteTarget] = useState<{ id: string; title: string; type: 'news' | 'event' } | null>(null)

  const loadNews = useCallback(async (): Promise<PageResponse<AdminNewsArticle> | null> => {
    try {
      setNewsLoading(true)
      setNewsError(null)
      const data = await fetchAdminNews(getAccessToken, newsPage)
      setNews(data)
      return data
    } catch (err) {
      setNewsError(err instanceof Error ? err.message : 'Failed to load news')
      return null
    } finally {
      setNewsLoading(false)
    }
  }, [getAccessToken, newsPage])

  const loadEvents = useCallback(async (): Promise<PageResponse<AdminEvent> | null> => {
    try {
      setEventsLoading(true)
      setEventsError(null)
      const data = await fetchAdminEvents(getAccessToken, eventsPage)
      setEvents(data)
      return data
    } catch (err) {
      setEventsError(err instanceof Error ? err.message : 'Failed to load events')
      return null
    } finally {
      setEventsLoading(false)
    }
  }, [getAccessToken, eventsPage])

  useEffect(() => {
    loadNews()
  }, [loadNews])

  useEffect(() => {
    loadEvents()
  }, [loadEvents])

  const handleToggleArticleVisibility = async (id: string, currentVisible: boolean) => {
    try {
      setNewsError(null)
      const updated = await toggleArticleVisibility(getAccessToken, id, !currentVisible)
      setNews((prev) =>
        prev
          ? { ...prev, content: prev.content.map((item) => (item.id === id ? updated : item)) }
          : prev,
      )
    } catch (err) {
      setNewsError(err instanceof Error ? err.message : 'Failed to update visibility')
    }
  }

  const handleToggleEventVisibility = async (id: string, currentVisible: boolean) => {
    try {
      setEventsError(null)
      const updated = await toggleEventVisibility(getAccessToken, id, !currentVisible)
      setEvents((prev) =>
        prev
          ? { ...prev, content: prev.content.map((item) => (item.id === id ? updated : item)) }
          : prev,
      )
    } catch (err) {
      setEventsError(err instanceof Error ? err.message : 'Failed to update visibility')
    }
  }

  const handleDeleteConfirm = async () => {
    if (!deleteTarget) return
    const target = deleteTarget
    setDeleteTarget(null)
    try {
      if (target.type === 'news') {
        setNewsError(null)
        await deleteArticle(getAccessToken, target.id)
        // Reload so the current page refills, clamping back if it is now past the end.
        const reloaded = await loadNews()
        if (reloaded && newsPage > 0 && newsPage > reloaded.totalPages - 1) {
          setNewsPage((p) => p - 1)
        }
      } else {
        setEventsError(null)
        await deleteEvent(getAccessToken, target.id)
        const reloaded = await loadEvents()
        if (reloaded && eventsPage > 0 && eventsPage > reloaded.totalPages - 1) {
          setEventsPage((p) => p - 1)
        }
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to delete item'
      if (target.type === 'news') {
        setNewsError(message)
      } else {
        setEventsError(message)
      }
    }
  }

  const handleTriggerAggregation = async () => {
    try {
      setAggregationTriggering(true)
      setAggregationSuccess(null)
      setAggregationError(null)
      await triggerAggregation(getAccessToken)
      setAggregationSuccess('Aggregation triggered successfully.')
      await loadNews()
      await loadEvents()
    } catch (err) {
      setAggregationError(err instanceof Error ? err.message : 'Failed to trigger aggregation')
    } finally {
      setAggregationTriggering(false)
    }
  }

  const handleGenerateDigest = async () => {
    setDigestConfirmOpen(false)
    try {
      setDigestTriggering(true)
      setAggregationSuccess(null)
      setAggregationError(null)
      await triggerDigest(getAccessToken)
      setAggregationSuccess('Weekly digest generation triggered.')
    } catch (err) {
      setAggregationError(err instanceof Error ? err.message : 'Failed to trigger digest')
    } finally {
      setDigestTriggering(false)
    }
  }

  const handleSearchSync = async () => {
    try {
      await triggerSearchSync(getAccessToken)
      setAggregationSuccess('Search sync triggered.')
    } catch (err) {
      setAggregationError(err instanceof Error ? err.message : 'Sync failed')
    }
  }

  const handleEmbeddingSync = async () => {
    try {
      await triggerEmbeddingSync(getAccessToken)
      setAggregationSuccess('Embedding sync triggered.')
    } catch (err) {
      setAggregationError(err instanceof Error ? err.message : 'Sync failed')
    }
  }

  const formatDate = (dateStr: string | null) => {
    if (!dateStr) return '-'
    return new Date(dateStr).toLocaleDateString()
  }

  const truncate = (text: string, maxLength = 60) => {
    if (text.length <= maxLength) return text
    return `${text.slice(0, maxLength)}…`
  }

  const newsItems = news?.content ?? []
  const eventItems = events?.content ?? []

  return (
    <div className="admin-page">
      <div className="admin-page__header">
        <h1 className="admin-page__title">News &amp; Events</h1>
        <div className="admin-page__actions">
          {aggregationSuccess && (
            <span className="admin-success-inline">{aggregationSuccess}</span>
          )}
          {aggregationError && (
            <span className="admin-error-inline">{aggregationError}</span>
          )}
          <AdminMenu
            label="Maintenance"
            items={[
              {
                label: 'Rebuild Search Index',
                title: 'Rebuild the site-wide Elasticsearch index.',
                onSelect: handleSearchSync,
              },
              {
                label: 'Rebuild Embeddings',
                title: 'Rebuild the site-wide vector embeddings.',
                onSelect: handleEmbeddingSync,
              },
            ]}
          />
          <button
            className="admin-btn admin-btn--primary"
            disabled={aggregationTriggering}
            onClick={handleTriggerAggregation}
            title="Visit every active content source and scrape new articles and events. Also runs automatically each night."
            type="button"
          >
            {aggregationTriggering ? 'Fetching...' : 'Fetch New Articles'}
          </button>
          <button
            className="admin-btn admin-btn--primary"
            disabled={digestTriggering}
            onClick={() => setDigestConfirmOpen(true)}
            title="Use AI to write and publish a blog post summarising recent activity. Also runs automatically every 3 days."
            type="button"
          >
            {digestTriggering ? 'Generating...' : 'Generate Digest Blog Post'}
          </button>
        </div>
      </div>

      <div className="admin-page__import" style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', margin: '1rem 0' }}>
        <input
          type="url"
          placeholder="Paste article or event URL to import..."
          value={importUrl}
          onChange={(e) => setImportUrl(e.target.value)}
          style={{ flex: 1, padding: '0.5rem 0.75rem', borderRadius: '6px', border: '1px solid var(--border-color, #ddd)', fontSize: '0.875rem' }}
        />
        <button
          className="admin-btn admin-btn--secondary"
          disabled={importLoading || !importUrl.trim()}
          onClick={async () => {
            try {
              setImportLoading(true)
              setAggregationSuccess(null)
              setAggregationError(null)
              const result = await importArticleUrl(getAccessToken, importUrl.trim()) as { message?: string }
              const message = result?.message || 'Import complete'
              if (message.startsWith('Failed') || message.startsWith('Already')) {
                setAggregationError(message)
              } else {
                setAggregationSuccess(message)
                setImportUrl('')
                await loadNews()
                await loadEvents()
              }
            } catch (err) {
              setAggregationError(err instanceof Error ? err.message : 'Failed to import URL')
            } finally {
              setImportLoading(false)
            }
          }}
          type="button"
        >
          {importLoading ? 'Importing...' : 'Import URL'}
        </button>
      </div>

      <div className="admin-tabs">
        <button
          className={`admin-tab${activeTab === 'news' ? ' admin-tab--active' : ''}`}
          onClick={() => setActiveTab('news')}
          type="button"
        >
          News ({news?.totalElements ?? 0})
        </button>
        <button
          className={`admin-tab${activeTab === 'events' ? ' admin-tab--active' : ''}`}
          onClick={() => setActiveTab('events')}
          type="button"
        >
          Events ({events?.totalElements ?? 0})
        </button>
      </div>

      {activeTab === 'news' && (
        <section className="admin-section">
          {newsError && <div className="admin-error-banner">{newsError}</div>}
          {newsLoading ? (
            <div className="admin-loading">Loading news...</div>
          ) : (
            <>
              <table className="admin-table">
                <thead>
                  <tr>
                    <th className="admin-table__th">Title</th>
                    <th className="admin-table__th">Source</th>
                    <th className="admin-table__th">Published</th>
                    <th className="admin-table__th">Visible</th>
                    <th className="admin-table__th">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {newsItems.length === 0 && (
                    <tr>
                      <td className="admin-table__td admin-table__td--empty" colSpan={5}>
                        No news articles found.
                      </td>
                    </tr>
                  )}
                  {newsItems.map((article) => (
                    <tr key={article.id} className="admin-table__row">
                      <td className="admin-table__td" title={article.title}>
                        {truncate(article.title)}
                      </td>
                      <td className="admin-table__td">{article.sourceName}</td>
                      <td className="admin-table__td">{formatDate(article.publishedDate)}</td>
                      <td className="admin-table__td">
                        <button
                          className={`admin-btn admin-btn--icon${article.visible ? '' : ' admin-btn--muted'}`}
                          onClick={() => handleToggleArticleVisibility(article.id, article.visible)}
                          title={article.visible ? 'Visible - click to hide' : 'Hidden - click to show'}
                          type="button"
                        >
                          {article.visible ? <Eye size={16} /> : <EyeOff size={16} />}
                        </button>
                      </td>
                      <td className="admin-table__td admin-table__td--actions">
                        <button
                          className="admin-btn admin-btn--icon admin-btn--danger-icon"
                          onClick={() => setDeleteTarget({ id: article.id, title: article.title, type: 'news' })}
                          title="Delete"
                          type="button"
                        >
                          <Trash2 size={16} />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {news && news.totalPages > 1 && (
                <div className="pagination">
                  <button
                    disabled={newsPage === 0}
                    onClick={() => setNewsPage((p) => p - 1)}
                    type="button"
                  >
                    Previous
                  </button>
                  <span>
                    Page {newsPage + 1} of {news.totalPages}
                  </span>
                  <button
                    disabled={newsPage >= news.totalPages - 1}
                    onClick={() => setNewsPage((p) => p + 1)}
                    type="button"
                  >
                    Next
                  </button>
                </div>
              )}
            </>
          )}
        </section>
      )}

      {activeTab === 'events' && (
        <section className="admin-section">
          {eventsError && <div className="admin-error-banner">{eventsError}</div>}
          {eventsLoading ? (
            <div className="admin-loading">Loading events...</div>
          ) : (
            <>
              <table className="admin-table">
                <thead>
                  <tr>
                    <th className="admin-table__th">Title</th>
                    <th className="admin-table__th">Source</th>
                    <th className="admin-table__th">Event Date</th>
                    <th className="admin-table__th">Visible</th>
                    <th className="admin-table__th">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {eventItems.length === 0 && (
                    <tr>
                      <td className="admin-table__td admin-table__td--empty" colSpan={5}>
                        No events found.
                      </td>
                    </tr>
                  )}
                  {eventItems.map((event) => (
                    <tr key={event.id} className="admin-table__row">
                      <td className="admin-table__td" title={event.title}>
                        {truncate(event.title)}
                      </td>
                      <td className="admin-table__td">{event.sourceName}</td>
                      <td className="admin-table__td">{formatDate(event.eventDate)}</td>
                      <td className="admin-table__td">
                        <button
                          className={`admin-btn admin-btn--icon${event.visible ? '' : ' admin-btn--muted'}`}
                          onClick={() => handleToggleEventVisibility(event.id, event.visible)}
                          title={event.visible ? 'Visible - click to hide' : 'Hidden - click to show'}
                          type="button"
                        >
                          {event.visible ? <Eye size={16} /> : <EyeOff size={16} />}
                        </button>
                      </td>
                      <td className="admin-table__td admin-table__td--actions">
                        <button
                          className="admin-btn admin-btn--icon admin-btn--danger-icon"
                          onClick={() => setDeleteTarget({ id: event.id, title: event.title, type: 'event' })}
                          title="Delete"
                          type="button"
                        >
                          <Trash2 size={16} />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {events && events.totalPages > 1 && (
                <div className="pagination">
                  <button
                    disabled={eventsPage === 0}
                    onClick={() => setEventsPage((p) => p - 1)}
                    type="button"
                  >
                    Previous
                  </button>
                  <span>
                    Page {eventsPage + 1} of {events.totalPages}
                  </span>
                  <button
                    disabled={eventsPage >= events.totalPages - 1}
                    onClick={() => setEventsPage((p) => p + 1)}
                    type="button"
                  >
                    Next
                  </button>
                </div>
              )}
            </>
          )}
        </section>
      )}

      <ConfirmDialog
        open={deleteTarget !== null}
        title={deleteTarget?.type === 'news' ? 'Delete Article' : 'Delete Event'}
        message={`Are you sure you want to delete "${deleteTarget?.title}"? This action cannot be undone.`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteTarget(null)}
      />

      <ConfirmDialog
        open={digestConfirmOpen}
        title="Generate Digest Blog Post"
        message={DIGEST_CONFIRM_MESSAGE}
        confirmLabel="Generate"
        cancelLabel="Cancel"
        onConfirm={handleGenerateDigest}
        onCancel={() => setDigestConfirmOpen(false)}
      />
    </div>
  )
}
