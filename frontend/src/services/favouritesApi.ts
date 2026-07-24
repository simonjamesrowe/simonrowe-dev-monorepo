import { API_BASE_URL } from '../config/api'
import type { FavouriteContentType } from '../types/favourites'
import type { ArticlePage } from '../types/news'
import type { EventPage } from '../types/events'

const FAVOURITES_URL = `${API_BASE_URL}/api/favourites`

export type GetAccessToken = () => Promise<string>

async function authFetch(url: string, token: string, options?: RequestInit): Promise<Response> {
  return fetch(url, {
    ...options,
    headers: {
      ...options?.headers,
      Authorization: `Bearer ${token}`,
    },
  })
}

async function assertOk(response: Response): Promise<void> {
  if (!response.ok) {
    let message = 'Request failed.'
    try {
      const errorPayload = await response.json()
      if (typeof errorPayload.message === 'string' && errorPayload.message.trim() !== '') {
        message = errorPayload.message
      }
    } catch {
      // Keep default fallback message when the response has no JSON payload.
    }
    throw new Error(message)
  }
}

async function handleResponse<T>(response: Response): Promise<T> {
  await assertOk(response)
  return (await response.json()) as T
}

// Favourites are globally shared, so reads are public — no auth token required.
export async function getFavouriteIds(type: FavouriteContentType): Promise<string[]> {
  const response = await fetch(`${FAVOURITES_URL}/${type}/ids`)
  return handleResponse<string[]>(response)
}

export async function getFavourites(
  type: 'news',
  page?: number,
  size?: number,
): Promise<ArticlePage>
export async function getFavourites(
  type: 'events',
  page?: number,
  size?: number,
): Promise<EventPage>
export async function getFavourites(
  type: FavouriteContentType,
  page = 0,
  size = 20,
): Promise<ArticlePage | EventPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  const response = await fetch(`${FAVOURITES_URL}/${type}?${params}`)
  return handleResponse<ArticlePage | EventPage>(response)
}

export async function addFavourite(
  getAccessToken: GetAccessToken,
  type: FavouriteContentType,
  id: string,
): Promise<void> {
  const token = await getAccessToken()
  const response = await authFetch(`${FAVOURITES_URL}/${type}/${id}`, token, { method: 'PUT' })
  await assertOk(response)
}

export async function removeFavourite(
  getAccessToken: GetAccessToken,
  type: FavouriteContentType,
  id: string,
): Promise<void> {
  const token = await getAccessToken()
  const response = await authFetch(`${FAVOURITES_URL}/${type}/${id}`, token, { method: 'DELETE' })
  await assertOk(response)
}
