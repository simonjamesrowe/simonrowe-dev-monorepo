import { API_BASE_URL } from '../config/api'

/**
 * The site's one STOMP endpoint. The chat and the news page's summary updates share it: the
 * broker is the same, only the topic differs.
 *
 * In production `API_BASE_URL` is `https://api.simonrowe.dev`, so the socket goes to that
 * host's `location /ws/` in the outer nginx, which carries the upgrade headers and the long
 * read timeout a WebSocket needs.
 */
export function buildWsUrl(): string {
  if (API_BASE_URL) {
    // API_BASE_URL is an absolute HTTP(S) URL, e.g. http://localhost:8080
    return API_BASE_URL.replace(/^http:/, 'ws:').replace(/^https:/, 'wss:') + '/ws/chat'
  }
  // Relative-path fallback: derive from current page origin
  const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${wsProtocol}//${window.location.host}/ws/chat`
}
