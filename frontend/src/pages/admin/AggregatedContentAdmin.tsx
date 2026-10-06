import { useCallback, useEffect, useState } from 'react'

import { Eye, EyeOff, Trash2 } from 'lucide-react'

import { useAuth } from '../../auth/useAuth'
import {
  fetchAdminNews,
  fetchAdminNewsSources,
  toggleArticleVisibility,
  deleteArticle,
  bulkUpdateArticles,
  fetchAdminEvents,
  toggleEventVisibility,
  deleteEvent,
  bulkUpdateEvents,
  triggerAggregation,
  triggerDigest,
  triggerSearchSync,
  triggerEmbeddingSync,
  importArticleUrl,
  type AdminBulkAction,
  type AdminBulkResult,
  type AdminEvent,
  type AdminEventSort,
  type AdminListingQuery,
  type AdminNewsArticle,
  type AdminNewsSort,
  type AdminSourceSummary,
} from '../../services/adminApi'
import { AdminMenu } from '../../components/admin/AdminMenu'
import {
  BulkBar,
  ListingToolbar,
  SortHeader,
  TitleLink,
} from '../../components/admin/AdminListingControls'
import { ConfirmDialog } from '../../components/admin/ConfirmDialog'
import { useAdminListing } from './useAdminListing'

type ActiveTab = 'news' | 'events'
type ItemKind = 'news' | 'event'

const DIGEST_CONFIRM_MESSAGE =
  'This will use AI to write a new blog post summarising recent blogs and articles, ' +
  'and publish it live on the site immediately. Continue?'

const NEWS_NOUN = { one: 'article', many: 'articles' }
const EVENTS_NOUN = { one: 'event', many: 'events' }

const PAST_TENSE: Record<AdminBulkAction, string> = {
  hide: 'Hid',
  show: 'Showed',
  delete: 'Deleted',
}

function bulkNotice(result: AdminBulkResult, noun: { one: string; many: string }): string {
  const what = `${PAST_TENSE[result.action]} ${result.updated} ${result.updated === 1 ? noun.one : noun.many}.`
  if (result.notFound === 0) return what
  return `${what} ${result.notFound} no longer existed.`
}

const formatDate = (dateStr: string | null) => {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleDateString()
}

export function AggregatedContentAdmin() {
  const { getAccessToken } = useAuth()

  const [activeTab, setActiveTab] = useState<ActiveTab>('news')

  const loadNewsPage = useCallback(
    (query: AdminListingQuery<AdminNewsSort>) => fetchAdminNews(getAccessToken, query),
    [getAccessToken],
  )
  const loadEventsPage = useCallback(
    (query: AdminListingQuery<AdminEventSort>) => fetchAdminEvents(getAccessToken, query),
    [getAccessToken],
  )
  const news = useAdminListing<AdminNewsArticle, AdminNewsSort>(
    loadNewsPage,
    'publishedDate',
    'Failed to load news',
  )
  const events = useAdminListing<AdminEvent, AdminEventSort>(
    loadEventsPage,
    'eventDate',
    'Failed to load events',
  )

  const [sources, setSources] = useState<AdminSourceSummary[]>([])

  const [aggregationTriggering, setAggregationTriggering] = useState(false)
  const [aggregationSuccess, setAggregationSuccess] = useState<string | null>(null)
  const [aggregationError, setAggregationError] = useState<string | null>(null)
  const [digestTriggering, setDigestTriggering] = useState(false)
  const [digestConfirmOpen, setDigestConfirmOpen] = useState(false)
  const [importUrl, setImportUrl] = useState('')
  const [importLoading, setImportLoading] = useState(false)

  const [deleteTarget, setDeleteTarget] = useState<{ id: string; title: string; type: ItemKind } | null>(null)
  const [bulkDeleteKind, setBulkDeleteKind] = useState<ItemKind | null>(null)
  const [bulkBusy, setBulkBusy] = useState(false)
  const [bulkMessage, setBulkMessage] = useState<string | null>(null)

  const loadSources = useCallback(async () => {
    try {
      setSources(await fetchAdminNewsSources(getAccessToken))
    } catch {
      // The source filter is a convenience; the table still works without it.
      setSources([])
    }
  }, [getAccessToken])

  useEffect(() => {
    loadSources()
  }, [loadSources])

  const handleToggleArticleVisibility = async (id: string, currentVisible: boolean) => {
    try {
      news.setError(null)
      const updated = await toggleArticleVisibility(getAccessToken, id, !currentVisible)
      news.setData((prev) =>
        prev
          ? { ...prev, content: prev.content.map((item) => (item.id === id ? updated : item)) }
          : prev,
      )
    } catch (err) {
      news.setError(err instanceof Error ? err.message : 'Failed to update visibility')
    }
  }

  const handleToggleEventVisibility = async (id: string, currentVisible: boolean) => {
    try {
      events.setError(null)
      const updated = await toggleEventVisibility(getAccessToken, id, !currentVisible)
      events.setData((prev) =>
        prev
          ? { ...prev, content: prev.content.map((item) => (item.id === id ? updated : item)) }
          : prev,
      )
    } catch (err) {
      events.setError(err instanceof Error ? err.message : 'Failed to update visibility')
    }
  }

  const handleDeleteConfirm = async () => {
    if (!deleteTarget) return
    const target = deleteTarget
    setDeleteTarget(null)
    const listing = target.type === 'news' ? news : events
    try {
      listing.setError(null)
      if (target.type === 'news') {
        await deleteArticle(getAccessToken, target.id)
        loadSources()
      } else {
        await deleteEvent(getAccessToken, target.id)
      }
      // Reload so the current page refills, clamping back if it is now past the end.
      await listing.reloadClamped()
    } catch (err) {
      listing.setError(err instanceof Error ? err.message : 'Failed to delete item')
    }
  }

  const runBulk = async (kind: ItemKind, action: AdminBulkAction) => {
    const listing = kind === 'news' ? news : events
    const ids = Array.from(listing.selected)
    if (ids.length === 0) return
    try {
      setBulkBusy(true)
      setBulkMessage(null)
      listing.setError(null)
      const result =
        kind === 'news'
          ? await bulkUpdateArticles(getAccessToken, ids, action)
          : await bulkUpdateEvents(getAccessToken, ids, action)
      setBulkMessage(bulkNotice(result, kind === 'news' ? NEWS_NOUN : EVENTS_NOUN))
      listing.clearSelection()
      if (kind === 'news' && action === 'delete') loadSources()
      await listing.reloadClamped()
    } catch (err) {
      listing.setError(err instanceof Error ? err.message : 'Bulk action failed')
    } finally {
      setBulkBusy(false)
    }
  }

  const handleBulkDeleteConfirm = async () => {
    const kind = bulkDeleteKind
    setBulkDeleteKind(null)
    if (kind) await runBulk(kind, 'delete')
  }

  const handleTriggerAggregation = async () => {
    try {
      setAggregationTriggering(true)
      setAggregationSuccess(null)
      setAggregationError(null)
      await triggerAggregation(getAccessToken)
      setAggregationSuccess('Aggregation triggered successfully.')
      await news.reload()
      await events.reload()
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

  const newsItems = news.data?.content ?? []
  const eventItems = events.data?.content ?? []
  const allNewsSelected = newsItems.length > 0 && newsItems.every((a) => news.selected.has(a.id))
  const allEventsSelected = eventItems.length > 0 && eventItems.every((e) => events.selected.has(e.id))
  const bulkDeleteCount = bulkDeleteKind === 'news' ? news.selected.size : events.selected.size

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
                await news.reload()
                await events.reload()
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
          aria-pressed={activeTab === 'news'}
          className={activeTab === 'news' ? 'active' : undefined}
          onClick={() => setActiveTab('news')}
          type="button"
        >
          News ({news.data?.totalElements ?? 0})
        </button>
        <button
          aria-pressed={activeTab === 'events'}
          className={activeTab === 'events' ? 'active' : undefined}
          onClick={() => setActiveTab('events')}
          type="button"
        >
          Events ({events.data?.totalElements ?? 0})
        </button>
      </div>

      {bulkMessage && <div className="admin-success-banner">{bulkMessage}</div>}

      {activeTab === 'news' && (
        <section className="admin-section">
          <ListingToolbar
            label="news"
            noun={NEWS_NOUN}
            onSearchChange={news.setSearch}
            onSizeChange={news.setSize}
            onSourceChange={news.setSource}
            onVisibilityChange={news.setVisibility}
            search={news.search}
            size={news.size}
            source={news.source}
            sources={sources}
            total={news.data?.totalElements ?? null}
            visibility={news.visibility}
          />
          {news.selected.size > 0 && (
            <BulkBar
              busy={bulkBusy}
              onClear={news.clearSelection}
              onDelete={() => setBulkDeleteKind('news')}
              onHide={() => runBulk('news', 'hide')}
              onShow={() => runBulk('news', 'show')}
              selectedCount={news.selected.size}
            />
          )}
          {news.error && <div className="admin-error-banner">{news.error}</div>}
          {news.loading && !news.data ? (
            <div className="admin-loading">Loading news...</div>
          ) : (
            <>
              <table className="admin-table" aria-busy={news.loading}>
                <thead>
                  <tr>
                    <th className="admin-table__th admin-listing__check-cell">
                      <input
                        aria-label="Select all articles on this page"
                        checked={allNewsSelected}
                        disabled={newsItems.length === 0}
                        onChange={news.toggleAllOnPage}
                        type="checkbox"
                      />
                    </th>
                    <SortHeader column="title" direction={news.direction} label="Title" onSort={news.sortBy} sort={news.sort} />
                    <SortHeader column="sourceName" direction={news.direction} label="Source" onSort={news.sortBy} sort={news.sort} />
                    <SortHeader column="publishedDate" direction={news.direction} label="Published" onSort={news.sortBy} sort={news.sort} />
                    <SortHeader column="fetchedAt" direction={news.direction} label="Fetched" onSort={news.sortBy} sort={news.sort} />
                    <th className="admin-table__th">Visible</th>
                    <th className="admin-table__th">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {newsItems.length === 0 && (
                    <tr>
                      <td className="admin-table__td admin-table__td--empty" colSpan={7}>
                        No news articles found.
                      </td>
                    </tr>
                  )}
                  {newsItems.map((article) => (
                    <tr key={article.id} className="admin-table__row">
                      <td className="admin-table__td admin-listing__check-cell">
                        <input
                          aria-label={`Select ${article.title}`}
                          checked={news.selected.has(article.id)}
                          onChange={() => news.toggleSelected(article.id)}
                          type="checkbox"
                        />
                      </td>
                      <td className="admin-table__td" title={article.title}>
                        <TitleLink title={article.title} url={article.originalUrl} />
                      </td>
                      <td className="admin-table__td">{article.sourceName}</td>
                      <td className="admin-table__td">{formatDate(article.publishedDate)}</td>
                      <td className="admin-table__td">{formatDate(article.fetchedAt)}</td>
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
              {news.data && news.data.totalPages > 1 && (
                <div className="pagination">
                  <button
                    disabled={news.page === 0}
                    onClick={() => news.setPage((p) => p - 1)}
                    type="button"
                  >
                    Previous
                  </button>
                  <span>
                    Page {news.page + 1} of {news.data.totalPages}
                  </span>
                  <button
                    disabled={news.page >= news.data.totalPages - 1}
                    onClick={() => news.setPage((p) => p + 1)}
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
          <ListingToolbar
            label="events"
            noun={EVENTS_NOUN}
            onSearchChange={events.setSearch}
            onSizeChange={events.setSize}
            onVisibilityChange={events.setVisibility}
            search={events.search}
            size={events.size}
            total={events.data?.totalElements ?? null}
            visibility={events.visibility}
          />
          {events.selected.size > 0 && (
            <BulkBar
              busy={bulkBusy}
              onClear={events.clearSelection}
              onDelete={() => setBulkDeleteKind('event')}
              onHide={() => runBulk('event', 'hide')}
              onShow={() => runBulk('event', 'show')}
              selectedCount={events.selected.size}
            />
          )}
          {events.error && <div className="admin-error-banner">{events.error}</div>}
          {events.loading && !events.data ? (
            <div className="admin-loading">Loading events...</div>
          ) : (
            <>
              <table className="admin-table" aria-busy={events.loading}>
                <thead>
                  <tr>
                    <th className="admin-table__th admin-listing__check-cell">
                      <input
                        aria-label="Select all events on this page"
                        checked={allEventsSelected}
                        disabled={eventItems.length === 0}
                        onChange={events.toggleAllOnPage}
                        type="checkbox"
                      />
                    </th>
                    <SortHeader column="title" direction={events.direction} label="Title" onSort={events.sortBy} sort={events.sort} />
                    <SortHeader column="sourceName" direction={events.direction} label="Source" onSort={events.sortBy} sort={events.sort} />
                    <SortHeader column="eventDate" direction={events.direction} label="Event Date" onSort={events.sortBy} sort={events.sort} />
                    <th className="admin-table__th">Visible</th>
                    <th className="admin-table__th">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {eventItems.length === 0 && (
                    <tr>
                      <td className="admin-table__td admin-table__td--empty" colSpan={6}>
                        No events found.
                      </td>
                    </tr>
                  )}
                  {eventItems.map((event) => (
                    <tr key={event.id} className="admin-table__row">
                      <td className="admin-table__td admin-listing__check-cell">
                        <input
                          aria-label={`Select ${event.title}`}
                          checked={events.selected.has(event.id)}
                          onChange={() => events.toggleSelected(event.id)}
                          type="checkbox"
                        />
                      </td>
                      <td className="admin-table__td" title={event.title}>
                        <TitleLink title={event.title} url={event.originalUrl} />
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
              {events.data && events.data.totalPages > 1 && (
                <div className="pagination">
                  <button
                    disabled={events.page === 0}
                    onClick={() => events.setPage((p) => p - 1)}
                    type="button"
                  >
                    Previous
                  </button>
                  <span>
                    Page {events.page + 1} of {events.data.totalPages}
                  </span>
                  <button
                    disabled={events.page >= events.data.totalPages - 1}
                    onClick={() => events.setPage((p) => p + 1)}
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
        open={bulkDeleteKind !== null}
        title={bulkDeleteKind === 'news' ? 'Delete Articles' : 'Delete Events'}
        message={`Delete ${bulkDeleteCount} selected ${
          bulkDeleteKind === 'news'
            ? bulkDeleteCount === 1 ? 'article' : 'articles'
            : bulkDeleteCount === 1 ? 'event' : 'events'
        }? This action cannot be undone.`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleBulkDeleteConfirm}
        onCancel={() => setBulkDeleteKind(null)}
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
