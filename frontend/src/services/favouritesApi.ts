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

export async function getFavouriteIds(
  getAccessToken: GetAccessToken,
  type: FavouriteContentType,
): Promise<string[]> {
  const token = await getAccessToken()
  const response = await authFetch(`${FAVOURITES_URL}/${type}/ids`, token)
  return handleResponse<string[]>(response)
}

export async function getFavourites(
  getAccessToken: GetAccessToken,
  type: 'news',
  page?: number,
  size?: number,
): Promise<ArticlePage>
export async function getFavourites(
  getAccessToken: GetAccessToken,
  type: 'events',
  page?: number,
  size?: number,
): Promise<EventPage>
export async function getFavourites(
  getAccessToken: GetAccessToken,
  type: FavouriteContentType,
  page = 0,
  size = 20,
): Promise<ArticlePage | EventPage> {
  const token = await getAccessToken()
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  const response = await authFetch(`${FAVOURITES_URL}/${type}?${params}`, token)
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
