import { useRef, useState } from 'react'

import { useAuth } from '../../auth/useAuth'
import { uploadAdminMedia, type MediaAsset } from '../../services/adminApi'

interface ImageUploaderProps {
  onUploadComplete: (asset: MediaAsset) => void
  currentImage?: string | null
}

const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/svg+xml']
const MAX_SIZE_BYTES = 10 * 1024 * 1024 // 10 MB

function validateFile(file: File): string | null {
  if (!ACCEPTED_TYPES.includes(file.type)) {
    return `Unsupported file type "${file.type}". Accepted types: JPEG, PNG, GIF, WebP, SVG.`
  }
  if (file.size > MAX_SIZE_BYTES) {
    return `File is too large (${(file.size / (1024 * 1024)).toFixed(1)} MB). Maximum allowed size is 10 MB.`
  }
  return null
}

export function ImageUploader({ onUploadComplete, currentImage }: ImageUploaderProps) {
  const { getAccessToken } = useAuth()
  const fileInputRef = useRef<HTMLInputElement>(null)

  const [dragging, setDragging] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [showUploader, setShowUploader] = useState(!currentImage)

  async function uploadFile(file: File) {
    const validationError = validateFile(file)
    if (validationError) {
      setError(validationError)
      return
    }
    try {
      setError(null)
      setUploading(true)
      const asset = await uploadAdminMedia(getAccessToken, file)
      onUploadComplete(asset)
      setShowUploader(false)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed. Please try again.')
    } finally {
      setUploading(false)
      if (fileInputRef.current) {
        fileInputRef.current.value = ''
      }
    }
  }

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (file) uploadFile(file)
  }

  function handleDrop(e: React.DragEvent<HTMLDivElement>) {
    e.preventDefault()
    setDragging(false)
    const file = e.dataTransfer.files[0]
    if (file) uploadFile(file)
  }

  function handleDragOver(e: React.DragEvent<HTMLDivElement>) {
    e.preventDefault()
    setDragging(true)
  }

  function handleDragLeave() {
    setDragging(false)
  }

  function handleBrowseClick() {
    fileInputRef.current?.click()
  }

  if (currentImage && !showUploader) {
    return (
      <div className="image-uploader">
        <div className="image-uploader__current">
          <img
            alt="Current selection"
            className="image-uploader__current-image"
            src={currentImage}
          />
          <button
            className="admin-btn admin-btn--sm"
            onClick={() => setShowUploader(true)}
            type="button"
          >
            Change
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="image-uploader">
      {currentImage && showUploader && (
        <div className="image-uploader__current-preview">
          <img
            alt="Current selection"
            className="image-uploader__current-image image-uploader__current-image--small"
            src={currentImage}
          />
          <button
            className="admin-btn admin-btn--sm"
            onClick={() => setShowUploader(false)}
            type="button"
          >
            Keep current
          </button>
        </div>
      )}

      <div
        className={`image-uploader__dropzone${dragging ? ' image-uploader__dropzone--active' : ''}${uploading ? ' image-uploader__dropzone--uploading' : ''}`}
        onDragLeave={handleDragLeave}
        onDragOver={handleDragOver}
        onDrop={handleDrop}
      >
        {uploading ? (
          <div className="image-uploader__spinner-wrap">
            <div className="image-uploader__spinner" />
            <p className="image-uploader__hint">Uploading...</p>
          </div>
        ) : (
          <>
            <p className="image-uploader__hint">
              Drag and drop an image here, or{' '}
              <button
                className="image-uploader__browse-btn"
                onClick={handleBrowseClick}
                type="button"
              >
                browse
              </button>
            </p>
            <p className="image-uploader__supported">
              Supported: JPEG, PNG, GIF, WebP, SVG &mdash; max 10 MB
            </p>
          </>
        )}
      </div>

      <input
        accept={ACCEPTED_TYPES.join(',')}
        className="image-uploader__file-input"
        disabled={uploading}
        onChange={handleFileChange}
        ref={fileInputRef}
        type="file"
      />

      {error && <p className="image-uploader__error">{error}</p>}
    </div>
  )
}
