import { API_BASE_URL } from '../config/api'
import { fetchWithRetry } from './fetchWithRetry'
import type {
  BlogContentType,
  BlogDetail,
  BlogNarrationResponse,
  BlogSearchResult,
  BlogSummary,
} from '../types/blog'

const BLOGS_ENDPOINT = `${API_BASE_URL}/api/blogs`
const SEARCH_ENDPOINT = `${API_BASE_URL}/api/search/blogs`

const FALLBACK_MESSAGE = 'Unable to load blog data.'
const NARRATION_FALLBACK_MESSAGE = 'Unable to load narration.'

/** Same shape as the summary API's; a callback so the caller supplies the Auth0 hook. */
export type GetAccessToken = () => Promise<string>

interface NarrationStatusOptions {
  afterVersion?: number
  waitSeconds?: number
  signal?: AbortSignal
}

export async function fetchBlogs(): Promise<BlogSummary[]> {
  return fetchWithRetry<BlogSummary[]>(BLOGS_ENDPOINT, { fallbackMessage: FALLBACK_MESSAGE })
}

export async function fetchBlogById(id: string): Promise<BlogDetail> {
  return fetchWithRetry<BlogDetail>(`${BLOGS_ENDPOINT}/${id}`, {
    fallbackMessage: FALLBACK_MESSAGE,
  })
}

export async function fetchBlogNarrationStatus(
  blogId: string,
  options: NarrationStatusOptions = {},
): Promise<BlogNarrationResponse> {
  const params = new URLSearchParams()
  if (options.afterVersion !== undefined) {
    params.set('afterVersion', String(options.afterVersion))
  }
  if (options.waitSeconds !== undefined) {
    params.set('waitSeconds', String(options.waitSeconds))
  }

  const query = params.toString()
  const url = `${BLOGS_ENDPOINT}/${encodeURIComponent(blogId)}/narration${query ? `?${query}` : ''}`
  let response: Response
  try {
    response = await fetch(url, {
      headers: { Accept: 'application/json' },
      signal: options.signal,
    })
  } catch (error) {
    if (options.signal?.aborted) {
      throw error
    }
    throw new Error(NARRATION_FALLBACK_MESSAGE)
  }

  let payload: Partial<BlogNarrationResponse> | null = null
  try {
    payload = (await response.json()) as Partial<BlogNarrationResponse>
  } catch {
    // The public fallback below is deliberately independent of response details.
  }

  if (response.ok && payload?.state) {
    return payload as BlogNarrationResponse
  }

  throw new Error(
    typeof payload?.message === 'string' && payload.message.trim() !== ''
      ? payload.message
      : NARRATION_FALLBACK_MESSAGE,
  )
}

/**
 * Requests generation without automatic retries. The backend operation is
 * idempotent, but avoiding a client retry also keeps ambiguous network outcomes
 * visible. A 503 is part of the narration contract and carries an UNAVAILABLE
 * response, so it must not be reduced to a generic thrown error.
 *
 * Authenticated. This POST was public until the listing pages gained a Listen control on
 * every card: a text-to-speech render draws on the same monthly character budget as summary
 * narration, so it needs a session from every surface. Mirrors `requestSummaryNarration`.
 * The read next door stays public.
 */
export async function requestBlogNarration(
  getAccessToken: GetAccessToken,
  blogId: string,
  signal?: AbortSignal,
): Promise<BlogNarrationResponse> {
  const token = await getAccessToken()

  let response: Response
  try {
    response = await fetch(`${BLOGS_ENDPOINT}/${encodeURIComponent(blogId)}/narration`, {
      method: 'POST',
      headers: { Accept: 'application/json', Authorization: `Bearer ${token}` },
      signal,
    })
  } catch (error) {
    if (signal?.aborted) {
      throw error
    }
    throw new Error(NARRATION_FALLBACK_MESSAGE)
  }

  let payload: Partial<BlogNarrationResponse> | null = null
  try {
    payload = (await response.json()) as Partial<BlogNarrationResponse>
  } catch {
    // The public fallback below is deliberately independent of response details.
  }

  if ((response.ok || response.status === 503) && payload?.state) {
    return payload as BlogNarrationResponse
  }

  throw new Error(
    typeof payload?.message === 'string' && payload.message.trim() !== ''
      ? payload.message
      : NARRATION_FALLBACK_MESSAGE,
  )
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
