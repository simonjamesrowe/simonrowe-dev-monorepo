import { API_BASE_URL } from '../../../config/api'

export function resolveChatWidgetImageUrl(url?: string | null): string | undefined {
  if (!url) return undefined
  if (url.startsWith('/uploads/')) return `${API_BASE_URL}${url}`
  if (url.startsWith('http://') || url.startsWith('https://')) return url
  return undefined
}
