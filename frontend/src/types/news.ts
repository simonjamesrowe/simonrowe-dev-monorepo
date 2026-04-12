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
}

export interface ArticlePage {
  content: ArticleResponse[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}
