import { useCallback, useEffect, useRef, useState } from 'react'

import { useAuth } from '../../auth/useAuth'
import {
  fetchAdminMedia,
  uploadAdminMedia,
  deleteAdminMedia,
  type MediaAsset,
  type PageResponse,
} from '../../services/adminApi'
import { ConfirmDialog } from '../../components/admin/ConfirmDialog'

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

export function MediaAdmin() {
  const { getAccessToken } = useAuth()
  const fileInputRef = useRef<HTMLInputElement>(null)

  const [media, setMedia] = useState<MediaAsset[]>([])
  const [pageInfo, setPageInfo] = useState<Omit<PageResponse<MediaAsset>, 'content'> | null>(null)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [uploading, setUploading] = useState(false)
  const [selectedAsset, setSelectedAsset] = useState<MediaAsset | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; name: string } | null>(null)

  const loadMedia = useCallback(
    async (pageNum = 0) => {
      try {
        setLoading(true)
        setError(null)
        const data = await fetchAdminMedia(getAccessToken, pageNum, 20)
        const { content, ...rest } = data
        setMedia(content)
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

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    try {
      setUploading(true)
      setError(null)
      await uploadAdminMedia(getAccessToken, file)
      if (fileInputRef.current) {
        fileInputRef.current.value = ''
      }
      await loadMedia(0)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed')
    } finally {
      setUploading(false)
    }
  }

  const handleDeleteConfirm = async () => {
    if (!deleteTarget) return
    try {
      setError(null)
      await deleteAdminMedia(getAccessToken, deleteTarget.id)
      if (selectedAsset?.id === deleteTarget.id) {
        setSelectedAsset(null)
      }
      setDeleteTarget(null)
      await loadMedia(page)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete media asset')
      setDeleteTarget(null)
    }
  }

  const handleSelectAsset = (asset: MediaAsset) => {
    setSelectedAsset((prev) => (prev?.id === asset.id ? null : asset))
  }

  return (
    <div className="admin-page">
      <div className="admin-page__header">
        <h1 className="admin-page__title">Media Library</h1>
        <label className="admin-btn admin-btn--primary admin-btn--upload">
          {uploading ? 'Uploading...' : 'Upload Image'}
          <input
            accept="image/*"
            className="admin-upload__input"
            disabled={uploading}
            onChange={handleUpload}
            ref={fileInputRef}
            type="file"
          />
        </label>
      </div>

      {error && <div className="admin-error-banner">{error}</div>}

      {loading ? (
        <div className="admin-loading">Loading media...</div>
      ) : (
        <>
          {media.length === 0 ? (
            <div className="admin-empty">No media assets found. Upload an image to get started.</div>
          ) : (
            <div className="admin-media-grid">
              {media.map((asset) => (
                <div
                  key={asset.id}
                  className={`admin-media-card${selectedAsset?.id === asset.id ? ' admin-media-card--selected' : ''}`}
                  onClick={() => handleSelectAsset(asset)}
                >
                  <div className="admin-media-card__preview">
                    <img
                      alt={asset.fileName}
                      className="admin-media-card__image"
                      src={asset.originalPath}
                    />
                  </div>
                  <div className="admin-media-card__info">
                    <p className="admin-media-card__name" title={asset.fileName}>
                      {asset.fileName}
                    </p>
                    <p className="admin-media-card__meta">
                      {asset.mimeType} &middot; {formatBytes(asset.fileSize)}
                    </p>
                  </div>
                  <button
                    aria-label={`Delete ${asset.fileName}`}
                    className="admin-btn admin-btn--sm admin-btn--danger admin-media-card__delete"
                    onClick={(e) => {
                      e.stopPropagation()
                      setDeleteTarget({ id: asset.id, name: asset.fileName })
                    }}
                    type="button"
                  >
                    Delete
                  </button>
                </div>
              ))}
            </div>
          )}

          {pageInfo && pageInfo.totalPages > 1 && (
            <div className="admin-pagination">
              <button
                className="admin-btn admin-btn--sm"
                disabled={page === 0}
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
                disabled={page >= pageInfo.totalPages - 1}
                onClick={() => loadMedia(page + 1)}
                type="button"
              >
                Next
              </button>
            </div>
          )}
        </>
      )}

      {selectedAsset && (
        <div className="admin-media-detail">
          <div className="admin-media-detail__header">
            <h2 className="admin-media-detail__title">Asset Details</h2>
            <button
              className="admin-btn admin-btn--sm"
              onClick={() => setSelectedAsset(null)}
              type="button"
            >
              Close
            </button>
          </div>
          <div className="admin-media-detail__body">
            <img
              alt={selectedAsset.fileName}
              className="admin-media-detail__image"
              src={selectedAsset.originalPath}
            />
            <dl className="admin-media-detail__meta">
              <dt>File Name</dt>
              <dd>{selectedAsset.fileName}</dd>
              <dt>MIME Type</dt>
              <dd>{selectedAsset.mimeType}</dd>
              <dt>File Size</dt>
              <dd>{formatBytes(selectedAsset.fileSize)}</dd>
              <dt>Path</dt>
              <dd className="admin-media-detail__path">{selectedAsset.originalPath}</dd>
              <dt>Uploaded</dt>
              <dd>{new Date(selectedAsset.createdAt).toLocaleString()}</dd>
            </dl>
            {Object.keys(selectedAsset.variants).length > 0 && (
              <div className="admin-media-detail__variants">
                <h3 className="admin-media-detail__variants-title">Variants</h3>
                <table className="admin-table">
                  <thead>
                    <tr>
                      <th className="admin-table__th">Name</th>
                      <th className="admin-table__th">Dimensions</th>
                      <th className="admin-table__th">Size</th>
                      <th className="admin-table__th">Path</th>
                    </tr>
                  </thead>
                  <tbody>
                    {Object.entries(selectedAsset.variants).map(([name, variant]) => (
                      <tr key={name} className="admin-table__row">
                        <td className="admin-table__td">{name}</td>
                        <td className="admin-table__td">
                          {variant.width} x {variant.height}
                        </td>
                        <td className="admin-table__td">{formatBytes(variant.fileSize)}</td>
                        <td className="admin-table__td admin-table__td--mono">
                          {variant.path}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}

      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete Media Asset"
        message={`Are you sure you want to delete "${deleteTarget?.name}"? This action cannot be undone.`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  )
}
