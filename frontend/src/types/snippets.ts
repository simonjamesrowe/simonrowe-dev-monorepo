/** A passage kept from an AI summary (spec 054). Only the site admin ever sees these. */
export interface Snippet {
  id: string
  sourceType: 'ARTICLE_SUMMARY'
  contentId: string
  /** The quote itself, as rendered text. Paragraph breaks are kept as a blank line. */
  exact: string
  /** Text just before and after the quote, used only to find it again in the summary. */
  prefix: string
  suffix: string
  /** Empty when there is no note. */
  note: string
  tags: string[]
  articleTitle: string
  sourceName: string
  author: string | null
  originalUrl: string
  articlePublishedAt: string | null
  createdAt: string
  updatedAt: string
  /** False once a regenerated summary no longer contains the quote. The quote is kept. */
  inCurrentSummary: boolean
  /** False when the article was pruned or hidden, so its summary cannot be opened. */
  articleAvailable: boolean
}

export interface NewSnippet {
  contentId: string
  exact: string
  prefix: string
  suffix: string
  note?: string
  tags?: string[]
}

export interface TagCount {
  tag: string
  count: number
}

export type SnippetSort = 'newest' | 'article'

export interface SnippetQuery {
  q?: string
  tags?: string[]
  sources?: string[]
  /** `yyyy-mm-dd`, inclusive, on the day the snippet was kept. */
  from?: string
  to?: string
  sort?: SnippetSort
  page?: number
  size?: number
}

export interface SnippetPage {
  content: Snippet[]
  totalElements: number
  totalPages: number
  number: number
  size: number
  last: boolean
}
