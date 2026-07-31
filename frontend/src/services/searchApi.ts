export interface SearchResult {
  name: string
  image: string | null
  url: string
}

export interface BlogSearchResult {
  title: string
  shortDescription: string | null
  image: string | null
  publishedDate: string
  url: string
}

export interface GroupedSearchResponse {
  blogs?: SearchResult[]
  jobs?: SearchResult[]
  skills?: SearchResult[]
  news?: SearchResult[]
  events?: SearchResult[]
}

import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'

const SITE_SEARCH_ENDPOINT = `${API_BASE_URL}/api/search`
const BLOG_SEARCH_ENDPOINT = `${API_BASE_URL}/api/search/blogs`

// Both searches are keystroke-driven and the caller aborts the in-flight request on
// every new keystroke; `fetchWithRetry` honours the signal and does not retry after an
// abort, so the retry behaviour never fights the debounce.

export async function siteSearch(
  query: string,
  signal?: AbortSignal
): Promise<GroupedSearchResponse> {
  return fetchWithRetry<GroupedSearchResponse>(
    `${SITE_SEARCH_ENDPOINT}?q=${encodeURIComponent(query)}`,
    { signal, fallbackMessage: 'Search request failed' }
  )
}

export async function blogSearch(
  query: string,
  signal?: AbortSignal
): Promise<BlogSearchResult[]> {
  return fetchWithRetry<BlogSearchResult[]>(
    `${BLOG_SEARCH_ENDPOINT}?q=${encodeURIComponent(query)}`,
    { signal, fallbackMessage: 'Blog search request failed' }
  )
}
