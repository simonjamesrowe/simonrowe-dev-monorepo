/**
 * What the media library accepts, kept in one place. Mirrors `MediaService.ALLOWED_MIME_TYPES` on
 * the backend, which is what actually enforces it.
 */
export const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/svg+xml'] as const
export const VIDEO_TYPES = ['video/mp4'] as const
export const CAPTION_TYPES = ['text/vtt'] as const
export const ALL_MEDIA_TYPES: readonly string[] = [...IMAGE_TYPES, ...VIDEO_TYPES, ...CAPTION_TYPES]

export const MAX_MEDIA_BYTES = 10 * 1024 * 1024

export type MediaKind = 'image' | 'video' | 'captions'

export const TYPES_BY_KIND: Record<MediaKind, readonly string[]> = {
  image: IMAGE_TYPES,
  video: VIDEO_TYPES,
  captions: CAPTION_TYPES,
}

export const KIND_LABELS: Record<MediaKind, string> = {
  image: 'JPEG, PNG, GIF, WebP or SVG',
  video: 'MP4 video',
  captions: 'WebVTT captions (.vtt)',
}

export function kindOf(mimeType: string): MediaKind {
  if ((VIDEO_TYPES as readonly string[]).includes(mimeType)) return 'video'
  if ((CAPTION_TYPES as readonly string[]).includes(mimeType)) return 'captions'
  return 'image'
}

/**
 * A browser reports a `.vtt` file as `text/vtt` on most platforms and as an empty type on some, so
 * the extension decides when the type is missing. Anything else is taken at the browser's word.
 */
export function mimeTypeOf(file: File): string {
  if (file.type) return file.type
  return file.name.toLowerCase().endsWith('.vtt') ? 'text/vtt' : ''
}
