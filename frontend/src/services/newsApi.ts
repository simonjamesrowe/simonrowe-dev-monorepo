import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'
import type { ArticlePage, ArticleResponse, SourceSummary } from '../types/news'

const NEWS_ENDPOINT = `${API_BASE_URL}/api/news`

const FALLBACK_MESSAGE = 'Unable to load news data.'

/** What the feed's filter row narrows the listing by. Both are optional and combine. */
export interface NewsFilters {
  /** Source names to include. Empty or absent means every source. */
  sources?: string[]
  /** Free text matched against the fields a card shows. Blank or absent means no filter. */
  query?: string
}

/**
 * One page of the news feed.
 *
 * <p>`source` repeats rather than being comma-joined: a source name may legitimately
 * contain a comma, and a joined value would silently split it into two sources that match
 * nothing.
 */
export async function fetchNews(
  page = 0,
  size = 20,
  filters: NewsFilters = {},
): Promise<ArticlePage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  for (const source of filters.sources ?? []) {
    if (source) params.append('source', source)
  }
  const query = filters.query?.trim()
  if (query) params.set('q', query)
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
