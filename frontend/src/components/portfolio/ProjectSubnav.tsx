import { NavLink } from 'react-router-dom'

import type { PortfolioProject } from '../../types/portfolio'

interface ProjectSubnavProps {
  project: PortfolioProject
}

/** Tabs between a project's overview and its sub-pages. Absent when it has no sub-pages. */
export function ProjectSubnav({ project }: ProjectSubnavProps) {
  const pages = project.pages ?? []
  if (pages.length === 0) return null
  const base = `/portfolio/${project.slug}`
  const tabClass = ({ isActive }: { isActive: boolean }) =>
    `project-subnav__link${isActive ? ' project-subnav__link--active' : ''}`

  return (
    <nav aria-label={`${project.name} pages`} className="project-subnav">
      <div className="project-subnav__inner">
        <NavLink className={tabClass} end to={base}>Overview</NavLink>
        {pages.map(page => (
          <NavLink className={tabClass} key={page.slug} to={`${base}/${page.slug}`}>
            {page.title}
            {page.navHint ? <span className="project-subnav__hint">{page.navHint}</span> : null}
          </NavLink>
        ))}
      </div>
    </nav>
  )
}
