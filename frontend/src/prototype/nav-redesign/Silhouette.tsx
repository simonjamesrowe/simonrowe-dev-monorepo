/** PROTOTYPE — a "coming soon" silhouette standing in for a product screenshot. */
import type { CSSProperties } from 'react'

import type { PortfolioProject } from './data'

interface SilhouetteProps {
  project: PortfolioProject
  size?: 'sm' | 'md' | 'lg'
  number?: string
  hideBadge?: boolean
}

export function Silhouette({ project, size = 'md', number, hideBadge }: SilhouetteProps) {
  const Icon = project.icon
  return (
    <div
      aria-hidden="true"
      className={`proto-silhouette proto-silhouette--${size}`}
      style={{ '--hue': project.hue } as CSSProperties}
    >
      <div className="proto-silhouette__device">
        <span className="proto-silhouette__bar proto-silhouette__bar--wide" />
        <span className="proto-silhouette__bar" />
        <span className="proto-silhouette__bar proto-silhouette__bar--short" />
        <Icon className="proto-silhouette__glyph" strokeWidth={1.1} />
      </div>
      {number ? <span className="proto-silhouette__number">{number}</span> : null}
      {hideBadge ? null : <span className="proto-badge">Coming soon</span>}
    </div>
  )
}
