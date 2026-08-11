export interface TagRef {
  name: string
}

export interface SkillRef {
  id: string
  name: string
}

/**
 * How a post is classified: hand-written engineering writing, or a generated
 * weekly digest of other people's writing.
 *
 * Non-optional on the payload types below: the backend DTOs coerce a missing
 * stored value to `ENGINEERING`, so the field is never `null` over the wire, and
 * making it optional would push a redundant `?? 'ENGINEERING'` into every call site.
 */
export type BlogContentType = 'ENGINEERING' | 'DIGEST'

export interface BlogSummary {
  id: string
  title: string
  shortDescription: string
  featuredImageUrl?: string | null
  createdDate: string
  tags: TagRef[]
  skills?: SkillRef[]
  contentType: BlogContentType
}

export interface BlogDetail {
  id: string
  title: string
  shortDescription: string
  content: string
  featuredImageUrl?: string | null
  createdDate: string
  tags: TagRef[]
  skills?: SkillRef[]
  contentType: BlogContentType
}

export type BlogNarrationState =
  | 'NOT_REQUESTED'
  | 'QUEUED'
  | 'PROCESSING'
  | 'READY'
  | 'FAILED'
  | 'UNAVAILABLE'
  | 'INELIGIBLE'

interface BlogNarrationResponseBase {
  version: number
  retryable: boolean
  message: string
}

export type BlogNarrationResponse =
  | (BlogNarrationResponseBase & {
      state: 'READY'
      audioUrl: string
      durationSeconds: number
    })
  | (BlogNarrationResponseBase & {
      state: Exclude<BlogNarrationState, 'READY'>
      audioUrl?: never
      durationSeconds?: never
    })

export interface BlogSearchResult {
  id: string
  title: string
  thumbnailImage?: string | null
  createdDate: string
}

export interface ErrorResponse {
  status: number
  message: string
  timestamp: string
}
