/**
 * Shared fetch wrapper for the public API.
 *
 * Replaces the per-service `handleResponse` / `parseErrorMessage` helpers, which were
 * duplicated across blogApi, newsApi, eventsApi, jobsApi, skillsApi and profileApi and
 * differed only in their fallback message.
 *
 * Behaviour:
 * - Retries once, after a short backoff, on a network error or a 5xx response.
 * - Never retries a 4xx — a client error will not fix itself.
 * - Never surfaces the raw `TypeError: Failed to fetch` text as the error message;
 *   callers get the server's `ErrorResponse.message` when present, else their own
 *   fallback, else a generic line.
 */

const RETRY_DELAY_MS = 300

const GENERIC_MESSAGE = 'Something went wrong. Please try again.'

interface FetchWithRetryOptions extends RequestInit {
  /** Message shown when the server sends no usable `message` of its own. */
  fallbackMessage?: string
}

const delay = (ms: number) =>
  new Promise<void>((resolve) => {
    setTimeout(resolve, ms)
  })

/** Pulls `ErrorResponse.message` out of a failed response body, if there is one. */
async function readServerMessage(response: Response): Promise<string | null> {
  try {
    const payload = await response.json()
    if (typeof payload?.message === 'string' && payload.message.trim() !== '') {
      return payload.message
    }
  } catch {
    // Body was empty or not JSON — fall back to the caller's message.
  }
  return null
}

export async function fetchWithRetry<T>(
  url: string,
  options: FetchWithRetryOptions = {},
): Promise<T> {
  const { fallbackMessage, ...requestInit } = options

  let response: Response | null = null

  for (let attempt = 0; attempt < 2; attempt += 1) {
    const isLastAttempt = attempt === 1
    response = null

    try {
      response = await fetch(url, requestInit)
    } catch (error) {
      // Network-level failure. Retry once unless the caller aborted, since an
      // aborted request is a deliberate cancellation rather than a fault.
      if (requestInit.signal?.aborted) {
        throw error
      }
      if (isLastAttempt) {
        throw new Error(fallbackMessage ?? GENERIC_MESSAGE)
      }
      await delay(RETRY_DELAY_MS)
      continue
    }

    if (response.ok) {
      return (await response.json()) as T
    }

    // 5xx is worth one more go; 4xx is not.
    const isServerError = response.status >= 500
    if (isServerError && !isLastAttempt && !requestInit.signal?.aborted) {
      await delay(RETRY_DELAY_MS)
      continue
    }

    throw new Error((await readServerMessage(response)) ?? fallbackMessage ?? GENERIC_MESSAGE)
  }

  // Unreachable: the loop either returns or throws on its final iteration.
  throw new Error(fallbackMessage ?? GENERIC_MESSAGE)
}
