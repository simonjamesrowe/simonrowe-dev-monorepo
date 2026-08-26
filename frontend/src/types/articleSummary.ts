/**
 * Wire types for on-demand article summaries.
 *
 * Shaped as a discriminated union like `BlogNarrationResponse`, so a `READY` summary is
 * statically known to carry a `body` and callers cannot forget to narrow.
 */

export type ArticleSummaryState =
  | 'NOT_REQUESTED'
  | 'GENERATING'
  | 'READY'
  | 'FAILED'

/**
 * Whether a retry is worth anything is carried on the response rather than inferred from
 * the code, but the codes are named here so the UI can explain the non-retryable ones.
 */
export type SummaryFailureCode =
  | 'INSUFFICIENT_SOURCE_TEXT'
  | 'MODEL_ERROR'
  | 'ARTICLE_NOT_FOUND'

interface ArticleSummaryResponseBase {
  version: number
  retryable: boolean
  message: string
}

export type ArticleSummaryResponse =
  | (ArticleSummaryResponseBase & {
      state: 'READY'
      body: string
      model?: string
      completedAt?: string
      failureCode?: never
    })
  | (ArticleSummaryResponseBase & {
      state: 'FAILED'
      failureCode: SummaryFailureCode
      body?: never
      model?: never
      completedAt?: never
    })
  | (ArticleSummaryResponseBase & {
      state: 'NOT_REQUESTED' | 'GENERATING'
      body?: never
      model?: never
      completedAt?: never
      failureCode?: never
    })
