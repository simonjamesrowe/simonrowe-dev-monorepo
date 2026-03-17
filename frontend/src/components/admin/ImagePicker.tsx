import { useRef, useState } from 'react'
import { Upload, FolderOpen, Trash2, ImageIcon } from 'lucide-react'
import { useAuth } from '../../auth/useAuth'
import { uploadAdminMedia } from '../../services/adminApi'
import { MediaLibrary } from './MediaLibrary'

const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/svg+xml']
const MAX_SIZE_BYTES = 10 * 1024 * 1024

interface ImagePickerProps {
  value: string | null
  onChange: (url: string) => void
}

export function ImagePicker({ value, onChange }: ImagePickerProps) {
  const { getAccessToken } = useAuth()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [showLibrary, setShowLibrary] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return

    if (!ACCEPTED_TYPES.includes(file.type)) {
      setError(`Unsupported file type. Accepted: JPEG, PNG, GIF, WebP, SVG.`)
      return
    }
    if (file.size > MAX_SIZE_BYTES) {
      setError(`File too large (${(file.size / (1024 * 1024)).toFixed(1)} MB). Max 10 MB.`)
      return
    }

    try {
      setError(null)
      setUploading(true)
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
    <div className="image-picker">
      <div className="image-picker__box">
        {value ? (
          <img src={value} alt="Selected" className="image-picker__img" />
        ) : (
          <div className="image-picker__placeholder">
            <ImageIcon size={40} className="image-picker__placeholder-icon" />
            <p>No image selected</p>
          </div>
        )}
      </div>

      {error && <p className="image-picker__error">{error}</p>}

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
        accept={ACCEPTED_TYPES.join(',')}
        onChange={handleFileChange}
        style={{ display: 'none' }}
      />

      {showLibrary && (
        <MediaLibrary
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
