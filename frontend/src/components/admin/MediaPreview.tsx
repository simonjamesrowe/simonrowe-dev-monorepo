import { Captions } from 'lucide-react'

import { kindOf } from './mediaTypes'

interface MediaPreviewProps {
  src: string
  mimeType?: string
  alt: string
  className?: string
}

/** A media item as a thumbnail: the image itself, a muted video, or a captions icon. */
export function MediaPreview({ src, mimeType, alt, className }: MediaPreviewProps) {
  const kind = mimeType ? kindOf(mimeType) : kindFromPath(src)
  if (kind === 'video') {
    return <video aria-label={alt} className={className} muted playsInline preload="metadata" src={src} />
  }
  if (kind === 'captions') {
    return (
      <span aria-label={alt} className={`media-preview__file ${className ?? ''}`} role="img">
        <Captions aria-hidden="true" size={32} />
        <span>{src.split('/').pop()}</span>
      </span>
    )
  }
  return <img alt={alt} className={className} src={src} />
}

function kindFromPath(src: string) {
  const path = src.toLowerCase().split('?')[0]
  if (path.endsWith('.mp4')) return 'video'
  if (path.endsWith('.vtt')) return 'captions'
  return 'image'
}
