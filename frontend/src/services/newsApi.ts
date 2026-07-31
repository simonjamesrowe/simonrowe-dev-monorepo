import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'
import type { ArticlePage, ArticleResponse } from '../types/news'

const NEWS_ENDPOINT = `${API_BASE_URL}/api/news`

const FALLBACK_MESSAGE = 'Unable to load news data.'

export async function fetchNews(page = 0, size = 20, source?: string): Promise<ArticlePage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (source) params.set('source', source)
  return fetchWithRetry<ArticlePage>(`${NEWS_ENDPOINT}?${params}`, {
    fallbackMessage: FALLBACK_MESSAGE,
  })
}

export async function fetchNewsById(id: string): Promise<ArticleResponse> {
  return fetchWithRetry<ArticleResponse>(`${NEWS_ENDPOINT}/${id}`, {
    fallbackMessage: FALLBACK_MESSAGE,
  })
}

/**
 * Every distinct source name the site holds, sorted, so filter chips can list a
 * source even when it has no article in the first page of results.
 */
export async function fetchNewsSources(): Promise<string[]> {
  return fetchWithRetry<string[]>(`${NEWS_ENDPOINT}/sources`, {
    fallbackMessage: FALLBACK_MESSAGE,
  })
}
