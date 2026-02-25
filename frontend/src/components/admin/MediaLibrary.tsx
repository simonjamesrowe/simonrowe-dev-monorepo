import { useCallback, useEffect, useState } from 'react'

import { useAuth } from '../../auth/useAuth'
import { fetchAdminMedia, type MediaAsset, type PageResponse } from '../../services/adminApi'

interface MediaLibraryProps {
  onSelect: (asset: MediaAsset) => void
  onClose: () => void
}

const MIME_FILTERS = [
  { label: 'All', value: '' },
  { label: 'JPEG', value: 'image/jpeg' },
  { label: 'PNG', value: 'image/png' },
  { label: 'GIF', value: 'image/gif' },
  { label: 'WebP', value: 'image/webp' },
  { label: 'SVG', value: 'image/svg+xml' },
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

export function MediaLibrary({ onSelect, onClose }: MediaLibraryProps) {
  const { getAccessToken } = useAuth()

  const [allAssets, setAllAssets] = useState<MediaAsset[]>([])
  const [pageInfo, setPageInfo] = useState<Omit<PageResponse<MediaAsset>, 'content'> | null>(null)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [mimeFilter, setMimeFilter] = useState('')

  const loadMedia = useCallback(
    async (pageNum: number) => {
      try {
        setLoading(true)
        setError(null)
        const data = await fetchAdminMedia(getAccessToken, pageNum, PAGE_SIZE)
        const { content, ...rest } = data
        setAllAssets(content)
        setPageInfo(rest)
        setPage(pageNum)
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to load media')
      } finally {
        setLoading(false)
      }
    },
    [getAccessToken],
  )

  useEffect(() => {
    loadMedia(0)
  }, [loadMedia])

  const filteredAssets = allAssets.filter((asset) => {
    const matchesMime = mimeFilter === '' || asset.mimeType === mimeFilter
    const matchesSearch =
      search.trim() === '' || asset.fileName.toLowerCase().includes(search.trim().toLowerCase())
    return matchesMime && matchesSearch
  })

  const handleSelect = (asset: MediaAsset) => {
    onSelect(asset)
    onClose()
  }

  return (
    <div className="media-library-overlay" onClick={onClose} role="dialog" aria-modal="true">
      <div
        className="media-library"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="media-library__header">
          <h2 className="media-library__title">Media Library</h2>
          <button
            className="admin-btn admin-btn--sm"
            onClick={onClose}
            type="button"
            aria-label="Close media library"
          >
            Close
          </button>
        </div>

        <div className="media-library__controls">
          <input
            className="media-library__search"
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search by file name..."
            type="search"
            value={search}
          />
          <div className="media-library__mime-filters">
            {MIME_FILTERS.map((f) => (
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

        <div className="media-library__body">
          {error && <div className="admin-error-banner">{error}</div>}

          {loading ? (
            <div className="admin-loading">Loading media...</div>
          ) : filteredAssets.length === 0 ? (
            <div className="admin-empty">
              {search || mimeFilter ? 'No assets match your filters.' : 'No media assets found.'}
            </div>
          ) : (
            <div className="admin-media-grid">
              {filteredAssets.map((asset) => (
                <button
                  className="admin-media-card admin-media-card--selectable"
                  key={asset.id}
                  onClick={() => handleSelect(asset)}
                  type="button"
                >
                  <div className="admin-media-card__preview">
                    <img
                      alt={asset.fileName}
                      className="admin-media-card__image"
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
