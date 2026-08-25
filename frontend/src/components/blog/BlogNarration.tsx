import { useMemo } from 'react'

import { NarrationPanel } from '../narration/NarrationPanel'
import { useNarration } from '../narration/useNarration'
import {
  fetchBlogNarrationStatus,
  requestBlogNarration,
} from '../../services/blogApi'

interface BlogNarrationProps {
  blogId: string
  blogTitle: string
}

/**
 * Audio narration of a blog post.
 *
 * A thin wrapper since narration was generalised: the long-poll orchestration lives in
 * `useNarration` and the render machine and player in `NarrationPanel`, both shared with
 * the article summary drawer. Every class name, aria attribute and string here is
 * unchanged from before that split — `BlogNarration.test.tsx` is the regression net and
 * passes untouched.
 */
export function BlogNarration({ blogId, blogTitle }: BlogNarrationProps) {
  const transport = useMemo(() => ({
    fetchStatus: (options: {
      afterVersion?: number
      waitSeconds?: number
      signal?: AbortSignal
    }) => fetchBlogNarrationStatus(blogId, options),
    // Public, unlike the summary equivalent: this endpoint's contract predates the
    // text-to-speech budget concern and is deliberately left as it was.
    request: (signal?: AbortSignal) => requestBlogNarration(blogId, signal),
  }), [blogId])

  const state = useNarration(transport)

  return (
    <NarrationPanel
      domId={`blog-narration-${blogId}`}
      state={state}
      subject={blogTitle}
    />
  )
}
