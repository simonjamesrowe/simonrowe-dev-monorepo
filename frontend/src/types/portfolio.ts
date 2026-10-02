import { API_BASE_URL } from '../config/api'
import type { ImageAsset } from './Profile'

export type ProjectStatus = 'LIVE' | 'BETA' | 'IN_DEVELOPMENT' | 'COMING_SOON'

/** A titled paragraph with an optional picture: a statement point or a numbered highlight. */
export interface ProjectHighlight {
  title: string
  text?: string | null
  imageUrl?: string | null
  imageAlt?: string | null
}

export interface ProjectStatement {
  label?: string | null
  text?: string | null
  points: ProjectHighlight[]
}

export interface ProjectChapter {
  startSeconds: number
  label: string
}

export interface ProjectDemo {
  title?: string | null
  summary?: string | null
  videoUrl: string
  captionsUrl?: string | null
  posterUrl?: string | null
  chapters: ProjectChapter[]
}

/** A sub-page at `/portfolio/{project}/{slug}`, written in markdown. */
export interface ProjectPage {
  slug: string
  title: string
  navHint?: string | null
  summary?: string | null
  body?: string | null
}

/**
 * A published portfolio project. A `COMING_SOON` one has no description, image, live link or
 * page fields — the API omits them — and no detail page. Every page field is optional: a project
 * without them gets the plain page of name, tagline and description.
 */
export interface PortfolioProject {
  slug: string
  name: string
  tagline: string
  status: ProjectStatus
  accentHue: number
  displayOrder: number
  description?: string
  image?: ImageAsset
  liveUrl?: string
  headline?: string
  summary?: string
  statement?: ProjectStatement
  exampleQuestions?: string[]
  highlights?: ProjectHighlight[]
  demo?: ProjectDemo
  pages?: ProjectPage[]
}

export const STATUS_LABELS: Record<ProjectStatus, string> = {
  LIVE: 'Live',
  BETA: 'Beta',
  IN_DEVELOPMENT: 'In development',
  COMING_SOON: 'Coming soon',
}

export function hasDetailPage(project: PortfolioProject): boolean {
  return project.status !== 'COMING_SOON'
}

/**
 * Where a CMS media URL actually loads from. A media-library item (`/uploads/...`) is served by
 * the API, which is a different origin in production; an https URL is used as it is.
 */
export function resolveMediaUrl(url: string): string {
  return url.startsWith('/uploads/') ? `${API_BASE_URL}${url}` : url
}
