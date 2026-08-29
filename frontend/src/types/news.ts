export interface ArticleResponse {
  id: string
  title: string
  sourceName: string
  originalUrl: string
  summary: string
  author: string | null
  publishedDate: string | null
  fetchedAt: string
  visible: boolean
  imageUrl: string | null
  /**
   * The full absolute share URL, ready to use. Absent when the article has no link minted
   * yet — the Share control is hidden rather than broken.
   */
  shortUrl?: string | null
}

/** A news source and how many visible articles it holds, busiest first from the API. */
export interface SourceSummary {
  name: string
  count: number
}

export interface ArticlePage {
  content: ArticleResponse[]
  totalElements: number
  totalPages: number
  number: number
  size: number
  /** True on the final page — what hides the "Load more" action (FR-038). */
  last: boolean
}
