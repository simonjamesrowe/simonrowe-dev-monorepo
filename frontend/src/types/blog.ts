export interface TagRef {
  name: string
}

export interface SkillRef {
  id: string
  name: string
}

export interface BlogSummary {
  id: string
  title: string
  shortDescription: string
  featuredImageUrl?: string | null
  createdDate: string
  tags: TagRef[]
  skills?: SkillRef[]
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
}

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
