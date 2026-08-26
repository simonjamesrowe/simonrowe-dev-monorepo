import { API_BASE_URL } from '../../config/api'

/**
 * Turns a narration's stored `audioUrl` into something an `<audio>` element can load.
 *
 * Narration audio paths are site-relative (`/uploads/narrations/…/narration.mp3`) and served by
 * the backend, which is a different origin from the frontend in every deployment. Absolute URLs
 * are passed through untouched.
 *
 * Extracted from `NarrationPanel` so the detail-page player and the docked bar resolve the same
 * path the same way.
 */
export function narrationMediaUrl(path: string): string {
  return path.startsWith('http://') || path.startsWith('https://')
    ? path
    : `${API_BASE_URL}${path}`
}
