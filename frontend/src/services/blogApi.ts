import type { BlogDetail, BlogSearchResult, BlogSummary } from '../types/blog'

const BLOGS_ENDPOINT = '/api/blogs'
const SEARCH_ENDPOINT = '/api/search/blogs'

async function handleResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let message = 'Unable to load blog data.'
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

export async function fetchBlogs(): Promise<BlogSummary[]> {
  const response = await fetch(BLOGS_ENDPOINT)
  return handleResponse<BlogSummary[]>(response)
}

export async function fetchBlogById(id: string): Promise<BlogDetail> {
  const response = await fetch(`${BLOGS_ENDPOINT}/${id}`)
  return handleResponse<BlogDetail>(response)
}

export async function fetchLatestBlogs(limit: number = 3): Promise<BlogSummary[]> {
  const response = await fetch(`${BLOGS_ENDPOINT}/latest?limit=${limit}`)
  return handleResponse<BlogSummary[]>(response)
}

export async function searchBlogs(query: string): Promise<BlogSearchResult[]> {
  const response = await fetch(`${SEARCH_ENDPOINT}?q=${encodeURIComponent(query)}`)
  return handleResponse<BlogSearchResult[]>(response)
}
