import { API_BASE_URL } from '../config/api'
import type { ArticleSummaryResponse } from '../types/articleSummary'

const NEWS_ENDPOINT = `${API_BASE_URL}/api/news`

const SUMMARY_FALLBACK_MESSAGE = 'Summary status is temporarily unavailable.'

export type GetAccessToken = () => Promise<string>

interface StatusOptions {
  afterVersion?: number
  waitSeconds?: number
  signal?: AbortSignal
}

function summaryUrl(articleId: string): string {
  return `${NEWS_ENDPOINT}/${encodeURIComponent(articleId)}/summary`
}

/**
 * Turns a response into an `ArticleSummaryResponse`, or throws with the server's own
 * message when there is one.
 *
 * Deliberately independent of response details for its fallback: a summary endpoint that
 * returns something unparseable should not leak `TypeError: Failed to fetch` into the UI.
 */
async function readSummary(
  response: Response,
  acceptedStatuses: number[] = [],
): Promise<ArticleSummaryResponse> {
  let payload: Partial<ArticleSummaryResponse> | null = null
  try {
    payload = (await response.json()) as Partial<ArticleSummaryResponse>
  } catch {
    // Fall through to the message below.
  }

  if ((response.ok || acceptedStatuses.includes(response.status)) && payload?.state) {
    return payload as ArticleSummaryResponse
  }

  throw new Error(
    typeof payload?.message === 'string' && payload.message.trim() !== ''
      ? payload.message
      : SUMMARY_FALLBACK_MESSAGE,
  )
}

/**
 * Current summary state. Public — summaries are globally shared, so no token.
 *
 * Pass `afterVersion` plus `waitSeconds` to long-poll: the request is held open until the
 * version moves or a terminal state is reached, capped server-side at 25 seconds.
 */
export async function fetchArticleSummary(
  articleId: string,
  { afterVersion, waitSeconds, signal }: StatusOptions = {},
): Promise<ArticleSummaryResponse> {
  const params = new URLSearchParams()
  if (afterVersion !== undefined) params.set('afterVersion', String(afterVersion))
  if (waitSeconds !== undefined) params.set('waitSeconds', String(waitSeconds))
  const query = params.toString()

  let response: Response
  try {
    response = await fetch(`${summaryUrl(articleId)}${query ? `?${query}` : ''}`, {
      headers: { Accept: 'application/json' },
      signal,
    })
  } catch (error) {
    if (signal?.aborted) {
      throw error
    }
    throw new Error(SUMMARY_FALLBACK_MESSAGE)
  }

  return readSummary(response)
}

/**
 * Requests generation. Authenticated — this is the call that spends on the model.
 *
 * No client retry. The backend operation is idempotent thanks to the insert-first dedup
 * guard, but avoiding a retry also keeps ambiguous network outcomes visible instead of
 * quietly issuing a second request the user never asked for.
 *
 * A 202 is part of the contract (another caller is already generating), so it must not be
 * reduced to a thrown error.
 */
export async function requestArticleSummary(
  getAccessToken: GetAccessToken,
  articleId: string,
  signal?: AbortSignal,
): Promise<ArticleSummaryResponse> {
  const token = await getAccessToken()

  let response: Response
  try {
    response = await fetch(summaryUrl(articleId), {
      method: 'POST',
      headers: { Accept: 'application/json', Authorization: `Bearer ${token}` },
      signal,
    })
  } catch (error) {
    if (signal?.aborted) {
      throw error
    }
    throw new Error(SUMMARY_FALLBACK_MESSAGE)
  }

  return readSummary(response, [202])
}

/**
 * Article ids that already have a summary. Public, so a logged-out visitor's card can read
 * "Read summary" and open instantly rather than being pushed at the login popup for
 * content that is already free to read.
 */
export async function fetchSummarisedArticleIds(): Promise<string[]> {
  const response = await fetch(`${NEWS_ENDPOINT}/summaries/ids`, {
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) {
    throw new Error(SUMMARY_FALLBACK_MESSAGE)
  }
  return (await response.json()) as string[]
}

/**
 * Queues text-to-speech for a summary. Authenticated: a render is the more expensive of
 * the two operations and draws on a monthly character budget shared with blog narration.
 */
export async function requestSummaryNarration(
  getAccessToken: GetAccessToken,
  articleId: string,
  signal?: AbortSignal,
): Promise<Response> {
  const token = await getAccessToken()
  return fetch(`${summaryUrl(articleId)}/narration`, {
    method: 'POST',
    headers: { Accept: 'application/json', Authorization: `Bearer ${token}` },
    signal,
  })
}

export { SUMMARY_FALLBACK_MESSAGE }
