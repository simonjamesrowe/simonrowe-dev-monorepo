import { API_BASE_URL } from '../config/api'
import type { NarrationAudioContentType } from '../types/narrationAudio'
import type {
  PlaylistAudioResult,
  PlaylistItemKey,
  PlaylistResponse,
} from '../types/playlist'

const PLAYLIST_URL = `${API_BASE_URL}/api/playlist`

const READ_FALLBACK_MESSAGE = 'The playlist could not be loaded.'
const WRITE_FALLBACK_MESSAGE = 'The playlist could not be updated.'

/** Supplied by the caller so this module stays free of the Auth0 hook. */
export type GetAccessToken = () => Promise<string>

const PATH_SEGMENTS: Record<NarrationAudioContentType, string> = {
  ARTICLE_SUMMARY: 'articles',
  BLOG: 'blogs',
}

function itemUrl(contentType: NarrationAudioContentType, contentId: string): string {
  return `${PLAYLIST_URL}/items/${PATH_SEGMENTS[contentType]}/${encodeURIComponent(contentId)}`
}

async function write(
  getAccessToken: GetAccessToken,
  url: string,
  method: 'PUT' | 'POST' | 'DELETE',
  body?: unknown,
): Promise<Response> {
  const token = await getAccessToken()
  let response: Response
  try {
    response = await fetch(url, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    // Never surface "TypeError: Failed to fetch" to the page.
    throw new Error(WRITE_FALLBACK_MESSAGE)
  }
  if (response.status === 429) {
    const wait = Number(response.headers.get('Retry-After'))
    throw new Error(Number.isFinite(wait) && wait > 0
      ? `Too many requests. Try again in ${wait} seconds.`
      : 'Too many requests. Try again shortly.')
  }
  if (!response.ok) {
    throw new Error(WRITE_FALLBACK_MESSAGE)
  }
  return response
}

/** The whole playlist. Public: anyone can see it and play it. */
export async function fetchPlaylist(signal?: AbortSignal): Promise<PlaylistResponse> {
  let response: Response
  try {
    response = await fetch(PLAYLIST_URL, { headers: { Accept: 'application/json' }, signal })
  } catch (error) {
    if (signal?.aborted) throw error
    throw new Error(READ_FALLBACK_MESSAGE)
  }
  if (!response.ok) throw new Error(READ_FALLBACK_MESSAGE)
  const payload = (await response.json()) as Partial<PlaylistResponse>
  if (!Array.isArray(payload.items)) throw new Error(READ_FALLBACK_MESSAGE)
  return { items: payload.items, autoVoice: Boolean(payload.autoVoice) }
}

/** Puts something on the end of the playlist, or queues something already heard again. */
export async function addToPlaylist(
  getAccessToken: GetAccessToken,
  item: PlaylistItemKey,
): Promise<void> {
  await write(getAccessToken, itemUrl(item.contentType, item.contentId), 'PUT')
}

export async function removeFromPlaylist(
  getAccessToken: GetAccessToken,
  item: PlaylistItemKey,
): Promise<void> {
  await write(getAccessToken, itemUrl(item.contentType, item.contentId), 'DELETE')
}

/** Where the listener got to, so the item resumes there on any device. */
export async function savePlaylistProgress(
  getAccessToken: GetAccessToken,
  item: PlaylistItemKey,
  positionSeconds: number,
): Promise<void> {
  await write(getAccessToken, `${itemUrl(item.contentType, item.contentId)}/progress`, 'PUT',
    { positionSeconds: Math.max(0, Math.round(positionSeconds * 10) / 10) })
}

export async function markPlaylistListened(
  getAccessToken: GetAccessToken,
  item: PlaylistItemKey,
): Promise<void> {
  await write(getAccessToken, `${itemUrl(item.contentType, item.contentId)}/listened`, 'POST')
}

/** Puts a queued item ahead of the date order, until it is heard or removed. */
export async function playNextInPlaylist(
  getAccessToken: GetAccessToken,
  item: PlaylistItemKey,
): Promise<void> {
  await write(getAccessToken, `${itemUrl(item.contentType, item.contentId)}/play-next`, 'POST')
}

export async function clearListened(getAccessToken: GetAccessToken): Promise<void> {
  await write(getAccessToken, `${PLAYLIST_URL}/listened`, 'DELETE')
}

/** Asks for audio for up to `MAX_AUDIO_ITEMS` items in one request. */
export async function requestPlaylistAudio(
  getAccessToken: GetAccessToken,
  items: PlaylistItemKey[],
): Promise<PlaylistAudioResult[]> {
  const response = await write(getAccessToken, `${PLAYLIST_URL}/narrations`, 'POST',
    { items: items.map(key) })
  const payload = (await response.json()) as { results?: PlaylistAudioResult[] }
  return Array.isArray(payload.results) ? payload.results : []
}

export async function updatePlaylistSettings(
  getAccessToken: GetAccessToken,
  autoVoice: boolean,
): Promise<boolean> {
  const response = await write(getAccessToken, `${PLAYLIST_URL}/settings`, 'PUT', { autoVoice })
  const payload = (await response.json()) as { autoVoice?: boolean }
  return Boolean(payload.autoVoice)
}

/** Strips an entry down to its key, so a whole entry can be passed without sending it all. */
function key(item: PlaylistItemKey): PlaylistItemKey {
  return { contentType: item.contentType, contentId: item.contentId }
}

export { READ_FALLBACK_MESSAGE, WRITE_FALLBACK_MESSAGE }
