import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'
import type { ArticlePage, ArticleResponse, SourceSummary } from '../types/news'

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
 * Every source the site holds with its article count, busiest first, so the filter
 * pills can list a source even when it has no article in the first page of results
 * and can collapse the low-volume tail.
 */
export async function fetchNewsSources(): Promise<SourceSummary[]> {
  return fetchWithRetry<SourceSummary[]>(`${NEWS_ENDPOINT}/sources`, {
    fallbackMessage: FALLBACK_MESSAGE,
  })
}
