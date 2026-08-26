import { API_BASE_URL } from '../config/api'
import type { ArticleSummaryResponse } from '../types/articleSummary'
import type { BlogNarrationResponse } from '../types/blog'
import type { NarrationAudioContentType, ReadyNarration } from '../types/narrationAudio'

const READY_ENDPOINT = `${API_BASE_URL}/api/narrations/ready`

const READY_FALLBACK_MESSAGE = 'Audio availability could not be checked.'

const CHAIN_FALLBACK_MESSAGE = 'Audio could not be prepared. Please try again.'

/** Supplied by the caller so this module stays free of the Auth0 hook. */
export type GetAccessToken = () => Promise<string>

/**
 * Every item of a content type that has playable audio right now.
 *
 * Public — no token. The audio is globally shared, so which items have it is not per-reader
 * information; a signed-out reader must see the duration on a card and be able to press play.
 * Mirrors `fetchSummarisedArticleIds`.
 *
 * One call per listing page, not one per card. Per-card status polling is not merely wasteful, it
 * does not work: `/api/blogs/{id}/narration` is rate-limited at 10/min per IP on `GET` too, so a
 * dozen cards would 429 the reader's actual click. This path deliberately sits outside that
 * pattern.
 *
 * Callers must tolerate rejection by leaving their map empty and rendering every card cold — a
 * failure here must never stop a listing rendering.
 */
export async function fetchReadyNarrations(
  contentType: NarrationAudioContentType,
  signal?: AbortSignal,
): Promise<ReadyNarration[]> {
  const url = `${READY_ENDPOINT}?contentType=${encodeURIComponent(contentType)}`

  let response: Response
  try {
    response = await fetch(url, {
      headers: { Accept: 'application/json' },
      signal,
    })
  } catch (error) {
    if (signal?.aborted) {
      throw error
    }
    // Deliberately independent of the underlying failure: a network error must not leak
    // "TypeError: Failed to fetch" towards the UI.
    throw new Error(READY_FALLBACK_MESSAGE)
  }

  if (!response.ok) {
    throw new Error(READY_FALLBACK_MESSAGE)
  }

  const payload = (await response.json()) as unknown
  if (!Array.isArray(payload)) {
    throw new Error(READY_FALLBACK_MESSAGE)
  }
  return payload as ReadyNarration[]
}

export { READY_FALLBACK_MESSAGE }

/**
 * The outcome of a chain step.
 *
 * A discriminated union rather than an exception because the docked player has to distinguish
 * three outcomes with three different treatments, and a 429 in particular carries information —
 * the server's own `Retry-After` — that a thrown `Error` would drop. The existing
 * `readNarration`/`readSummary` helpers throw on any non-2xx/503, which loses the header, so the
 * chain uses these instead.
 */
export type ChainOutcome<T> =
  | { ok: true; value: T }
  | { ok: false; rateLimited: true; retryAfterSeconds: number }
  | { ok: false; rateLimited: false; message: string }

/** Seconds to wait when a 429 arrives without a usable `Retry-After`. */
const DEFAULT_RETRY_AFTER_SECONDS = 60

function retryAfterFrom(response: Response): number {
  const header = response.headers?.get?.('Retry-After')
  const parsed = header ? Number(header) : NaN
  return Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_RETRY_AFTER_SECONDS
}

/**
 * POSTs an authenticated chain step and classifies the result.
 *
 * A `503` is part of the narration contract — it carries an `UNAVAILABLE` response, typically a
 * spent monthly budget — so it counts as a successful read of state, not a failure. A `202` is
 * likewise part of both contracts (someone else is already generating).
 */
async function postChainStep<T extends { state: string }>(
  url: string,
  token: string,
  signal?: AbortSignal,
): Promise<ChainOutcome<T>> {
  let response: Response
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: { Accept: 'application/json', Authorization: `Bearer ${token}` },
      signal,
    })
  } catch (error) {
    if (signal?.aborted) throw error
    return { ok: false, rateLimited: false, message: CHAIN_FALLBACK_MESSAGE }
  }

  if (response.status === 429) {
    return { ok: false, rateLimited: true, retryAfterSeconds: retryAfterFrom(response) }
  }

  let payload: { state?: string; message?: unknown } | null = null
  try {
    payload = (await response.json()) as { state?: string; message?: unknown }
  } catch {
    // Fall through to the message below.
  }

  if ((response.ok || response.status === 503) && payload?.state) {
    return { ok: true, value: payload as T }
  }

  const message = typeof payload?.message === 'string' && payload.message.trim() !== ''
    ? payload.message
    : CHAIN_FALLBACK_MESSAGE
  return { ok: false, rateLimited: false, message }
}

/** Queues text-to-speech for a blog post. Authenticated since 035-listen-from-listing. */
export async function postBlogNarration(
  getAccessToken: GetAccessToken,
  blogId: string,
  signal?: AbortSignal,
): Promise<ChainOutcome<BlogNarrationResponse>> {
  const token = await getAccessToken()
  return postChainStep<BlogNarrationResponse>(
    `${API_BASE_URL}/api/blogs/${encodeURIComponent(blogId)}/narration`, token, signal)
}

/** Queues text-to-speech for an article's summary. */
export async function postSummaryNarration(
  getAccessToken: GetAccessToken,
  articleId: string,
  signal?: AbortSignal,
): Promise<ChainOutcome<BlogNarrationResponse>> {
  const token = await getAccessToken()
  return postChainStep<BlogNarrationResponse>(
    `${API_BASE_URL}/api/news/${encodeURIComponent(articleId)}/summary/narration`, token, signal)
}

/** Generates an article's summary. Blocks 15–30s: this is the slow step of the news chain. */
export async function postArticleSummary(
  getAccessToken: GetAccessToken,
  articleId: string,
  signal?: AbortSignal,
): Promise<ChainOutcome<ArticleSummaryResponse>> {
  const token = await getAccessToken()
  return postChainStep<ArticleSummaryResponse>(
    `${API_BASE_URL}/api/news/${encodeURIComponent(articleId)}/summary`, token, signal)
}

/**
 * Current narration state for either content type, optionally long-polling.
 *
 * Public — no token. One function for both content types because the two endpoints share the
 * `NarrationResponse` contract exactly; only the path differs.
 */
export async function fetchNarrationStatus(
  contentType: NarrationAudioContentType,
  contentId: string,
  options: { afterVersion?: number; waitSeconds?: number; signal?: AbortSignal } = {},
): Promise<BlogNarrationResponse> {
  const params = new URLSearchParams()
  if (options.afterVersion !== undefined) {
    params.set('afterVersion', String(options.afterVersion))
  }
  if (options.waitSeconds !== undefined) {
    params.set('waitSeconds', String(options.waitSeconds))
  }
  const query = params.toString()
  const base = contentType === 'BLOG'
    ? `${API_BASE_URL}/api/blogs/${encodeURIComponent(contentId)}/narration`
    : `${API_BASE_URL}/api/news/${encodeURIComponent(contentId)}/summary/narration`

  let response: Response
  try {
    response = await fetch(`${base}${query ? `?${query}` : ''}`, {
      headers: { Accept: 'application/json' },
      signal: options.signal,
    })
  } catch (error) {
    if (options.signal?.aborted) throw error
    throw new Error(CHAIN_FALLBACK_MESSAGE)
  }

  let payload: Partial<BlogNarrationResponse> | null = null
  try {
    payload = (await response.json()) as Partial<BlogNarrationResponse>
  } catch {
    // Fall through.
  }
  if ((response.ok || response.status === 503) && payload?.state) {
    return payload as BlogNarrationResponse
  }
  throw new Error(CHAIN_FALLBACK_MESSAGE)
}

export { CHAIN_FALLBACK_MESSAGE }
