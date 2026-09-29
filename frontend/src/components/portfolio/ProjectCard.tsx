import type { CSSProperties } from 'react'
import { Link } from 'react-router-dom'

import { API_BASE_URL } from '../../config/api'
import { hasDetailPage, STATUS_LABELS, type PortfolioProject } from '../../types/portfolio'
import { ProjectSilhouette } from './ProjectSilhouette'

interface ProjectCardProps {
  project: PortfolioProject
  /** The carousel's 01, 02… numbering; omitted on the Portfolio page. */
  number?: string
}

/**
 * One project. A launched project with an image shows it and links to its detail page; a
 * Coming soon one shows a silhouette and links nowhere, because there is nothing behind it yet.
 */
export function ProjectCard({ project, number }: ProjectCardProps) {
  const linked = hasDetailPage(project)
  const imageUrl = project.image?.formats?.medium?.url ?? project.image?.url
  const media = linked && imageUrl ? (
    <div className="project-card__image">
      <img alt="" loading="lazy" src={`${API_BASE_URL}${imageUrl}`} />
      {number ? <span className="project-silhouette__number">{number}</span> : null}
    </div>
  ) : (
    <ProjectSilhouette hue={project.accentHue} number={number} />
  )

  const body = (
    <>
      <div className="project-card__media">
        {media}
        <span className={`project-card__status project-card__status--${project.status.toLowerCase()}`}>
          {STATUS_LABELS[project.status]}
        </span>
      </div>
      <h3 className="project-card__name">{project.name}</h3>
      <p className="project-card__tagline">{project.tagline}</p>
    </>
  )

  return (
    <article className="project-card" style={{ '--project-hue': project.accentHue } as CSSProperties}>
      {linked ? (
        <Link className="project-card__link" to={`/portfolio/${project.slug}`}>{body}</Link>
      ) : body}
    </article>
  )
}
