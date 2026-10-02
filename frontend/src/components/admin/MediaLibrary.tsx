import { useCallback, useEffect, useState } from 'react'
import { X } from 'lucide-react'

import { useAuth } from '../../auth/useAuth'
import { fetchAdminMedia, type MediaAsset, type PageResponse } from '../../services/adminApi'
import { MediaPreview } from './MediaPreview'

interface MediaLibraryProps {
  onSelect: (asset: MediaAsset) => void
  onClose: () => void
  /** Only these types are listed and filterable, so an image field never offers a video. */
  accept?: readonly string[]
}

const MIME_FILTERS = [
  { label: 'All', value: '' },
  { label: 'JPEG', value: 'image/jpeg' },
  { label: 'PNG', value: 'image/png' },
  { label: 'GIF', value: 'image/gif' },
  { label: 'WebP', value: 'image/webp' },
  { label: 'SVG', value: 'image/svg+xml' },
  { label: 'Video', value: 'video/mp4' },
  { label: 'Captions', value: 'text/vtt' },
]

const PAGE_SIZE = 20

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function getThumbnailSrc(asset: MediaAsset): string {
  const thumbnail = asset.variants['thumbnail']
  return thumbnail ? thumbnail.path : asset.originalPath
}

function getThumbnailDimensions(asset: MediaAsset): string {
  const thumbnail = asset.variants['thumbnail']
  if (thumbnail) return `${thumbnail.width} x ${thumbnail.height}`
  return ''
}

/** How long typing pauses before the search is sent. */
const SEARCH_DELAY_MS = 300

export function MediaLibrary({ onSelect, onClose, accept }: MediaLibraryProps) {
  const { getAccessToken } = useAuth()

  const [assets, setAssets] = useState<MediaAsset[]>([])
  const [pageInfo, setPageInfo] = useState<Omit<PageResponse<MediaAsset>, 'content'> | null>(null)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const [mimeFilter, setMimeFilter] = useState('')

  // The server filters, not this page of results: a video field must find a video that sits
  // forty pages into a library of images.
  const mimeTypes = mimeFilter ? [mimeFilter] : accept
  const typesKey = mimeTypes?.join(',') ?? ''

  useEffect(() => {
    const timer = setTimeout(() => setQuery(search.trim()), SEARCH_DELAY_MS)
    return () => clearTimeout(timer)
  }, [search])

  const loadMedia = useCallback(
    async (pageNum: number, signal?: { cancelled: boolean }) => {
      try {
        setLoading(true)
        setError(null)
        const data = await fetchAdminMedia(getAccessToken, pageNum, PAGE_SIZE, {
          mimeTypes: typesKey ? typesKey.split(',') : undefined,
          search: query,
        })
        if (signal?.cancelled) return
        const { content, ...rest } = data
        setAssets(content)
        setPageInfo(rest)
        setPage(pageNum)
      } catch (err) {
        if (signal?.cancelled) return
        setError(err instanceof Error ? err.message : 'Failed to load media')
      } finally {
        if (!signal?.cancelled) setLoading(false)
      }
    },
    [getAccessToken, typesKey, query],
  )

  useEffect(() => {
    // A slower answer to an older filter must not replace the newer one.
    const signal = { cancelled: false }
    loadMedia(0, signal)
    return () => { signal.cancelled = true }
  }, [loadMedia])

  const filters = MIME_FILTERS.filter((f) => f.value === '' || !accept || accept.includes(f.value))

  const handleSelect = (asset: MediaAsset) => {
    onSelect(asset)
    onClose()
  }

  return (
    <div className="drawer-overlay" onClick={onClose} role="dialog" aria-modal="true">
      <div
        className="drawer"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="drawer__header">
          <h2 className="drawer__title">Media Library</h2>
          <button
            className="drawer__close"
            onClick={onClose}
            type="button"
            aria-label="Close media library"
          >
            <X size={18} />
          </button>
        </div>

        <div className="media-library-drawer__controls">
          <input
            className="admin-form__input"
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search by file name..."
            type="search"
            value={search}
          />
          <div className="media-library-drawer__mime-filters">
            {filters.length > 2 && filters.map((f) => (
              <button
                className={`admin-btn admin-btn--sm${mimeFilter === f.value ? ' admin-btn--primary' : ''}`}
                key={f.value}
                onClick={() => setMimeFilter(f.value)}
                type="button"
              >
                {f.label}
              </button>
            ))}
          </div>
        </div>

        <div className="media-library-drawer__body">
          {error && <div className="admin-error-banner">{error}</div>}

          {loading ? (
            <div className="admin-loading">Loading media...</div>
          ) : assets.length === 0 ? (
            <div className="admin-empty">
              {search || mimeFilter ? 'No assets match your filters.' : 'No media assets found.'}
            </div>
          ) : (
            <div className="admin-media-grid">
              {assets.map((asset) => (
                <button
                  className="admin-media-card admin-media-card--selectable"
                  key={asset.id}
                  onClick={() => handleSelect(asset)}
                  type="button"
                >
                  <div className="admin-media-card__preview">
                    <MediaPreview
                      alt={asset.fileName}
                      className="admin-media-card__image"
                      mimeType={asset.mimeType}
                      src={getThumbnailSrc(asset)}
                    />
                  </div>
                  <div className="admin-media-card__info">
                    <p className="admin-media-card__name" title={asset.fileName}>
                      {asset.fileName}
                    </p>
                    <p className="admin-media-card__meta">
                      {formatBytes(asset.fileSize)}
                      {getThumbnailDimensions(asset) && (
                        <> &middot; {getThumbnailDimensions(asset)}</>
                      )}
                    </p>
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>

        {pageInfo && pageInfo.totalPages > 1 && (
          <div className="admin-pagination">
            <button
              className="admin-btn admin-btn--sm"
              disabled={page === 0 || loading}
              onClick={() => loadMedia(page - 1)}
              type="button"
            >
              Previous
            </button>
            <span className="admin-pagination__info">
              Page {page + 1} of {pageInfo.totalPages} ({pageInfo.totalElements} items)
            </span>
            <button
              className="admin-btn admin-btn--sm"
              disabled={page >= pageInfo.totalPages - 1 || loading}
              onClick={() => loadMedia(page + 1)}
              type="button"
            >
              Next
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
