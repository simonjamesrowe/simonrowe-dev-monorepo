import { API_BASE_URL } from '../config/api'
import type { ArticlePage, ArticleResponse } from '../types/news'

const NEWS_ENDPOINT = `${API_BASE_URL}/api/news`

async function handleResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let message = 'Unable to load news data.'
    try {
      const errorPayload = await response.json()
      if (typeof errorPayload.message === 'string' && errorPayload.message.trim() !== '') {
        message = errorPayload.message
      }
    } catch {
      // Keep default fallback message
    }
    throw new Error(message)
  }
  return (await response.json()) as T
}

export async function fetchNews(page = 0, size = 20, source?: string): Promise<ArticlePage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (source) params.set('source', source)
  const response = await fetch(`${NEWS_ENDPOINT}?${params}`)
  return handleResponse<ArticlePage>(response)
}

export async function fetchNewsById(id: string): Promise<ArticleResponse> {
  const response = await fetch(`${NEWS_ENDPOINT}/${id}`)
  return handleResponse<ArticleResponse>(response)
}
