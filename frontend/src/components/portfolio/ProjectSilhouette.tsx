import type { CSSProperties } from 'react'

interface ProjectSilhouetteProps {
  hue: number
  size?: 'sm' | 'lg'
  number?: string
}

/**
 * A "coming soon" stand-in for a product screenshot: a blurred device outline tinted by the
 * project's accent hue. Decorative; the card around it carries the name.
 */
export function ProjectSilhouette({ hue, size = 'lg', number }: ProjectSilhouetteProps) {
  return (
    <div
      aria-hidden="true"
      className={`project-silhouette project-silhouette--${size}`}
      style={{ '--project-hue': hue } as CSSProperties}
    >
      <div className="project-silhouette__device">
        <span className="project-silhouette__bar project-silhouette__bar--wide" />
        <span className="project-silhouette__bar" />
        <span className="project-silhouette__bar project-silhouette__bar--short" />
      </div>
      {number ? <span className="project-silhouette__number">{number}</span> : null}
    </div>
  )
}
