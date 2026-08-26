import { useMemo } from 'react'

import { NarrationPanel } from '../narration/NarrationPanel'
import { useNarration } from '../narration/useNarration'
import { useAuth } from '../../auth/useAuth'
import { useEnsureAuthenticated } from '../../hooks/useEnsureAuthenticated'
import {
  fetchBlogNarrationStatus,
  requestBlogNarration,
} from '../../services/blogApi'
import type { BlogNarrationResponse } from '../../types/blog'

interface BlogNarrationProps {
  blogId: string
  blogTitle: string
}

/**
 * Audio narration of a blog post.
 *
 * A thin wrapper since narration was generalised: the long-poll orchestration lives in
 * `useNarration` and the render machine and player in `NarrationPanel`, both shared with
 * the article summary drawer.
 *
 * Reads are public because the audio is globally shared. The request runs through
 * `useEnsureAuthenticated` first — the same gate `SummaryNarration` has always had. This
 * endpoint's POST was public until the listing pages gained a Listen control on every card;
 * a text-to-speech render draws on a monthly character budget, so gating only the listing
 * would have left the same post anonymously narratable from right here.
 */
export function BlogNarration({ blogId, blogTitle }: BlogNarrationProps) {
  const ensureAuthenticated = useEnsureAuthenticated()
  const { getAccessToken } = useAuth()

  const transport = useMemo(() => ({
    fetchStatus: (options: {
      afterVersion?: number
      waitSeconds?: number
      signal?: AbortSignal
    }) => fetchBlogNarrationStatus(blogId, options),

    async request(signal?: AbortSignal): Promise<BlogNarrationResponse> {
      // A dismissed sign-in popup must cost nothing, so nothing is sent until a session is
      // genuinely confirmed. Reporting the current state as UNAVAILABLE rather than throwing
      // keeps the panel showing an invitation rather than an error.
      if (!(await ensureAuthenticated())) {
        return {
          state: 'UNAVAILABLE',
          version: 0,
          retryable: false,
          message: 'Sign in to generate audio for this post.',
        }
      }
      return requestBlogNarration(getAccessToken, blogId, signal)
    },
  }), [blogId, ensureAuthenticated, getAccessToken])

  const state = useNarration(transport)

  return (
    <NarrationPanel
      domId={`blog-narration-${blogId}`}
      state={state}
      subject={blogTitle}
    />
  )
}
