import { useMemo } from 'react'

import { NarrationPanel } from '../narration/NarrationPanel'
import { useNarration } from '../narration/useNarration'
import { useEnsureAuthenticated } from '../../hooks/useEnsureAuthenticated'
import { useAuth } from '../../auth/useAuth'
import { API_BASE_URL } from '../../config/api'
import { requestSummaryNarration } from '../../services/articleSummaryApi'
import type { BlogNarrationResponse } from '../../types/blog'

interface SummaryNarrationProps {
  articleId: string
  articleTitle: string
}

const FALLBACK_MESSAGE = 'Audio is temporarily unavailable.'

function narrationUrl(articleId: string): string {
  return `${API_BASE_URL}/api/news/${encodeURIComponent(articleId)}/summary/narration`
}

/**
 * Audio of an article's summary, inside the summary drawer.
 *
 * Uses the same `useNarration` + `NarrationPanel` pair as blog narration; the only
 * difference is the transport. Reads are public because the audio is globally shared, but
 * the request runs through `useEnsureAuthenticated` first: a text-to-speech render draws on
 * a monthly character budget, so it needs a session.
 */
export function SummaryNarration({ articleId, articleTitle }: SummaryNarrationProps) {
  const ensureAuthenticated = useEnsureAuthenticated()
  const { getAccessToken } = useAuth()

  const transport = useMemo(() => ({
    async fetchStatus(options: {
      afterVersion?: number
      waitSeconds?: number
      signal?: AbortSignal
    }): Promise<BlogNarrationResponse> {
      const params = new URLSearchParams()
      if (options.afterVersion !== undefined) {
        params.set('afterVersion', String(options.afterVersion))
      }
      if (options.waitSeconds !== undefined) {
        params.set('waitSeconds', String(options.waitSeconds))
      }
      const query = params.toString()
      const response = await fetch(
        `${narrationUrl(articleId)}${query ? `?${query}` : ''}`,
        { headers: { Accept: 'application/json' }, signal: options.signal },
      )
      return readNarration(response)
    },

    async request(signal?: AbortSignal): Promise<BlogNarrationResponse> {
      // A dismissed sign-in popup must cost nothing, so nothing is sent until a session is
      // genuinely confirmed. Reporting the current state as UNAVAILABLE rather than
      // throwing keeps the panel showing the summary rather than an error.
      if (!(await ensureAuthenticated())) {
        return {
          state: 'UNAVAILABLE',
          version: 0,
          retryable: false,
          message: 'Sign in to generate audio for this summary.',
        }
      }
      const response = await requestSummaryNarration(getAccessToken, articleId, signal)
      return readNarration(response)
    },
  }), [articleId, ensureAuthenticated, getAccessToken])

  const state = useNarration(transport)

  return (
    <div className="news-summary__audio">
      <NarrationPanel
        actionLabel="Listen to this summary"
        domId={`summary-narration-${articleId}`}
        heading="Generated narration"
        pendingText="Preparing audio. You can keep reading."
        promptText="Prefer to listen? Generate an audio version of this summary."
        state={state}
        subject={`the summary of ${articleTitle}`}
      />
    </div>
  )
}

/**
 * A 503 is part of the narration contract — it carries an UNAVAILABLE response — so it must
 * not be collapsed into a generic thrown error.
 */
async function readNarration(response: Response): Promise<BlogNarrationResponse> {
  let payload: Partial<BlogNarrationResponse> | null = null
  try {
    payload = (await response.json()) as Partial<BlogNarrationResponse>
  } catch {
    // Fall through to the message below.
  }
  if ((response.ok || response.status === 503) && payload?.state) {
    return payload as BlogNarrationResponse
  }
  throw new Error(
    typeof payload?.message === 'string' && payload.message.trim() !== ''
      ? payload.message
      : FALLBACK_MESSAGE,
  )
}
