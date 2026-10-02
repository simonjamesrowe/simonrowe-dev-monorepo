import { ArrowLeft, ExternalLink } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'

import { MarkdownRenderer } from '../components/blog/MarkdownRenderer'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { ProjectOverview } from '../components/portfolio/ProjectOverview'
import { ProjectSubnav } from '../components/portfolio/ProjectSubnav'
import { usePageTitle } from '../hooks/usePageTitle'
import { fetchPortfolioProject } from '../services/portfolioApi'
import { STATUS_LABELS, type PortfolioProject } from '../types/portfolio'
import { NotFoundPage } from './NotFoundPage'

type State =
  | { kind: 'loading' }
  | { kind: 'found'; project: PortfolioProject }
  | { kind: 'missing' }
  | { kind: 'error'; message: string }

/**
 * A launched project's page: its overview at `/portfolio/:slug`, and each of its sub-pages at
 * `/portfolio/:slug/:pageSlug`. Unknown, unpublished and Coming soon slugs are a plain 404, and
 * so is a sub-page the project does not have. Switching between sub-pages reuses the project
 * already loaded rather than fetching it again.
 */
export function PortfolioProjectPage() {
  const { slug = '', pageSlug } = useParams()
  const [state, setState] = useState<State>({ kind: 'loading' })
  const project = state.kind === 'found' ? state.project : null
  const page = pageSlug ? project?.pages?.find(candidate => candidate.slug === pageSlug) : undefined
  usePageTitle(project ? (page ? `${page.title} · ${project.name}` : project.name) : 'Portfolio')

  useEffect(() => {
    let cancelled = false
    setState({ kind: 'loading' })
    fetchPortfolioProject(slug)
      .then(found => {
        if (!cancelled) setState(found ? { kind: 'found', project: found } : { kind: 'missing' })
      })
      .catch(error => {
        if (!cancelled) setState({ kind: 'error', message: error instanceof Error ? error.message : 'Unable to load this project.' })
      })
    return () => {
      cancelled = true
    }
  }, [slug])

  if (state.kind === 'loading') return <LoadingIndicator message="Loading project..." />
  if (state.kind === 'missing') return <NotFoundPage />
  if (state.kind === 'error') {
    return <ErrorMessage message={state.message} onRetry={() => window.location.reload()} title="Unable to load this project" />
  }
  if (pageSlug && !page) return <NotFoundPage />

  const found = state.project

  return (
    <article className="portfolio-project">
      <div className="project-section__inner">
        <Link className="portfolio-project__back" to="/portfolio">
          <ArrowLeft aria-hidden="true" size={16} /> All projects
        </Link>
      </div>
      {page ? (
        <>
          <header className="project-section__inner project-compact">
            <p className="project-eyebrow">
              Portfolio · {found.name}
              <span className={`project-card__status project-card__status--${found.status.toLowerCase()}`}>
                {STATUS_LABELS[found.status]}
              </span>
            </p>
            <h1 className="project-compact__title">{page.title}</h1>
            {found.liveUrl ? (
              <a className="button button--primary project-button" href={found.liveUrl} rel="noopener noreferrer" target="_blank">
                Open {found.name} <ExternalLink aria-hidden="true" size={16} />
              </a>
            ) : null}
          </header>
          <ProjectSubnav project={found} />
          <div className="project-section">
            <div className="project-section__inner project-section__inner--prose blog-detail__content">
              {page.body ? <MarkdownRenderer content={page.body} /> : null}
            </div>
          </div>
        </>
      ) : (
        <ProjectOverview project={found} />
      )}
    </article>
  )
}
