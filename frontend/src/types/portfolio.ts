import type { ImageAsset } from './Profile'

export type ProjectStatus = 'LIVE' | 'BETA' | 'IN_DEVELOPMENT' | 'COMING_SOON'

/**
 * A published portfolio project. A `COMING_SOON` one has no description, image or live link —
 * the API omits them — and no detail page.
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
