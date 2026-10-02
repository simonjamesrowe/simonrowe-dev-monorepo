import { useRef, useState } from 'react'
import { FolderOpen, ImageIcon, Trash2, Upload } from 'lucide-react'

import { useAuth } from '../../auth/useAuth'
import { uploadAdminMedia } from '../../services/adminApi'
import { MediaLibrary } from './MediaLibrary'
import { MediaPreview } from './MediaPreview'
import { KIND_LABELS, MAX_MEDIA_BYTES, TYPES_BY_KIND, mimeTypeOf, type MediaKind } from './mediaTypes'

interface MediaPickerProps {
  value: string | null
  onChange: (url: string) => void
  /** What may be chosen: uploads and the library browser are both restricted to it. */
  kind?: MediaKind
  /** Names the field for assistive technology, since several pickers can share a page. */
  label?: string
}

/**
 * Chooses one media-library item: upload a new file or browse the library. The value is the
 * item's path (`/uploads/...`), which is what every CMS field stores.
 */
export function MediaPicker({ value, onChange, kind = 'image', label }: MediaPickerProps) {
  const { getAccessToken } = useAuth()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [showLibrary, setShowLibrary] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const accepted = TYPES_BY_KIND[kind]
  const noun = kind === 'image' ? 'image' : kind === 'video' ? 'video' : 'captions file'

  async function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const picked = e.target.files?.[0]
    if (!picked) return
    const type = mimeTypeOf(picked)

    if (!accepted.includes(type)) {
      setError(`Unsupported file type. Accepted: ${KIND_LABELS[kind]}.`)
      return
    }
    if (picked.size > MAX_MEDIA_BYTES) {
      setError(`File too large (${(picked.size / (1024 * 1024)).toFixed(1)} MB). Max 10 MB.`)
      return
    }

    try {
      setError(null)
      setUploading(true)
      // Re-labelled so a .vtt the browser typed as "" still reaches the server as text/vtt.
      const file = type === picked.type ? picked : new File([picked], picked.name, { type })
      const asset = await uploadAdminMedia(getAccessToken, file)
      onChange(asset.originalPath)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed.')
    } finally {
      setUploading(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  return (
    <div aria-label={label} className="image-picker" role={label ? 'group' : undefined}>
      <div className={`image-picker__box image-picker__box--${kind}`}>
        {value ? (
          kind === 'video' ? (
            <video aria-label={`Selected ${noun}`} className="image-picker__img" controls preload="metadata" src={value} />
          ) : (
            <MediaPreview alt={`Selected ${noun}`} className="image-picker__img" src={value} />
          )
        ) : (
          <div className="image-picker__placeholder">
            <ImageIcon size={40} className="image-picker__placeholder-icon" />
            <p>No {noun} selected</p>
          </div>
        )}
      </div>

      {error && <p className="image-picker__error" role="alert">{error}</p>}

      <div className="image-picker__actions">
        <button
          type="button"
          className="admin-btn admin-btn--primary admin-btn--sm"
          onClick={() => fileInputRef.current?.click()}
          disabled={uploading}
        >
          <Upload size={14} /> {uploading ? 'Uploading...' : 'Upload'}
        </button>
        <button
          type="button"
          className="admin-btn admin-btn--sm"
          onClick={() => setShowLibrary(true)}
          disabled={uploading}
        >
          <FolderOpen size={14} /> Browse Library
        </button>
        {value && (
          <button
            type="button"
            className="admin-btn admin-btn--sm admin-btn--danger"
            onClick={() => onChange('')}
            disabled={uploading}
          >
            <Trash2 size={14} /> Remove
          </button>
        )}
      </div>

      <input
        ref={fileInputRef}
        type="file"
        accept={kind === 'captions' ? [...accepted, '.vtt'].join(',') : accepted.join(',')}
        onChange={handleFileChange}
        style={{ display: 'none' }}
      />

      {showLibrary && (
        <MediaLibrary
          accept={accepted}
          onSelect={(asset) => {
            onChange(asset.originalPath)
            setShowLibrary(false)
          }}
          onClose={() => setShowLibrary(false)}
        />
      )}
    </div>
  )
}
