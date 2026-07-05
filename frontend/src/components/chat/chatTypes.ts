export type WidgetKind = 'skills' | 'employment' | 'code' | 'blogs' | 'news' | 'events'

export interface SkillWidgetPayload {
  groups: Array<{
    name: string
    skills: Array<{ name: string; rating?: number | null }>
  }>
}

export interface EmploymentWidgetPayload {
  jobs: Array<{
    company: string
    title: string
    start?: string | null
    end?: string | null
    summary?: string | null
    skills?: string[]
  }>
}

export interface CodeWidgetPayload {
  examples: Array<{
    id?: string | null
    title: string
    description?: string | null
    language?: string | null
    code?: string | null
    skills?: string[]
  }>
}

export interface BlogWidgetPayload {
  posts: Array<{
    id?: string | null
    title: string
    summary?: string | null
    tags?: string[]
    publishedDate?: string | null
    url?: string | null
    imageUrl?: string | null
  }>
}

export interface NewsWidgetPayload {
  articles: Array<{
    id?: string | null
    title: string
    summary?: string | null
    sourceName?: string | null
    originalUrl?: string | null
    publishedDate?: string | null
    imageUrl?: string | null
  }>
}

export interface EventWidgetPayload {
  events: Array<{
    id?: string | null
    title: string
    summary?: string | null
    sourceName?: string | null
    originalUrl?: string | null
    eventDate?: string | null
    eventEndDate?: string | null
    venue?: string | null
    location?: string | null
    imageUrl?: string | null
  }>
}

export type ChatBlock =
  | { kind: 'text'; content: string }
  | { kind: 'tool'; label: string; status: 'running' | 'done' }
  | { kind: 'widget'; widgetKind: string; payload: unknown }

export interface ChatMessageModel {
  role: 'user' | 'assistant'
  content?: string
  blocks?: ChatBlock[]
  timestamp: string
  finalized?: boolean
}
