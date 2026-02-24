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
}

import { API_BASE_URL } from '../config/api'

const SITE_SEARCH_ENDPOINT = `${API_BASE_URL}/api/search`
const BLOG_SEARCH_ENDPOINT = `${API_BASE_URL}/api/search/blogs`

export async function siteSearch(
  query: string,
  signal?: AbortSignal
): Promise<GroupedSearchResponse> {
  const response = await fetch(
    `${SITE_SEARCH_ENDPOINT}?q=${encodeURIComponent(query)}`,
    { signal }
  )
  if (!response.ok) {
    throw new Error('Search request failed')
  }
  return (await response.json()) as GroupedSearchResponse
}

export async function blogSearch(
  query: string,
  signal?: AbortSignal
): Promise<BlogSearchResult[]> {
  const response = await fetch(
    `${BLOG_SEARCH_ENDPOINT}?q=${encodeURIComponent(query)}`,
    { signal }
  )
  if (!response.ok) {
    throw new Error('Blog search request failed')
  }
  return (await response.json()) as BlogSearchResult[]
}
