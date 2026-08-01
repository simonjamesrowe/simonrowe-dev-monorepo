import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'
import type { BlogContentType, BlogDetail, BlogSearchResult, BlogSummary } from '../types/blog'

const BLOGS_ENDPOINT = `${API_BASE_URL}/api/blogs`
const SEARCH_ENDPOINT = `${API_BASE_URL}/api/search/blogs`

const FALLBACK_MESSAGE = 'Unable to load blog data.'

export async function fetchBlogs(): Promise<BlogSummary[]> {
  return fetchWithRetry<BlogSummary[]>(BLOGS_ENDPOINT, { fallbackMessage: FALLBACK_MESSAGE })
}

export async function fetchBlogById(id: string): Promise<BlogDetail> {
  return fetchWithRetry<BlogDetail>(`${BLOGS_ENDPOINT}/${id}`, {
    fallbackMessage: FALLBACK_MESSAGE,
  })
}

/**
 * Newest published posts, optionally restricted to one content type.
 *
 * The backend applies the `contentType` filter *before* the limit, so asking for
 * three engineering posts always yields three even when digests occupy the top of
 * the list.
 */
export async function fetchLatestBlogs(
  limit: number = 3,
  contentType?: BlogContentType,
): Promise<BlogSummary[]> {
  const query = contentType ? `?limit=${limit}&contentType=${contentType}` : `?limit=${limit}`
  return fetchWithRetry<BlogSummary[]>(`${BLOGS_ENDPOINT}/latest${query}`, {
    fallbackMessage: FALLBACK_MESSAGE,
  })
}

export async function searchBlogs(query: string): Promise<BlogSearchResult[]> {
  return fetchWithRetry<BlogSearchResult[]>(`${SEARCH_ENDPOINT}?q=${encodeURIComponent(query)}`, {
    fallbackMessage: FALLBACK_MESSAGE,
  })
}
