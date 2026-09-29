import { ArrowLeft, ExternalLink } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'

import { MarkdownRenderer } from '../components/blog/MarkdownRenderer'
import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { API_BASE_URL } from '../config/api'
import { usePageTitle } from '../hooks/usePageTitle'
import { fetchPortfolioProject } from '../services/portfolioApi'
import { STATUS_LABELS, type PortfolioProject } from '../types/portfolio'
import { NotFoundPage } from './NotFoundPage'

type State =
  | { kind: 'loading' }
  | { kind: 'found'; project: PortfolioProject }
  | { kind: 'missing' }
  | { kind: 'error'; message: string }

/** A launched project's page. Unknown, unpublished and Coming soon slugs are a plain 404. */
export function PortfolioProjectPage() {
  const { slug = '' } = useParams()
  const [state, setState] = useState<State>({ kind: 'loading' })
  usePageTitle(state.kind === 'found' ? state.project.name : 'Portfolio')

  useEffect(() => {
    let cancelled = false
    setState({ kind: 'loading' })
    fetchPortfolioProject(slug)
      .then(project => {
        if (!cancelled) setState(project ? { kind: 'found', project } : { kind: 'missing' })
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

  const { project } = state
  const imageUrl = project.image?.formats?.large?.url ?? project.image?.url

  return (
    <article className="portfolio-project">
      <Link className="portfolio-project__back" to="/portfolio">
        <ArrowLeft aria-hidden="true" size={16} /> All projects
      </Link>
      <header className="portfolio-project__header">
        <span className={`project-card__status project-card__status--${project.status.toLowerCase()}`}>
          {STATUS_LABELS[project.status]}
        </span>
        <h1 className="portfolio-project__title">{project.name}</h1>
        <p className="portfolio-project__tagline">{project.tagline}</p>
        {project.liveUrl ? (
          <a className="button button--primary" href={project.liveUrl} rel="noopener noreferrer" target="_blank">
            Open {project.name} <ExternalLink aria-hidden="true" size={16} />
          </a>
        ) : null}
      </header>
      {imageUrl ? <img alt="" className="portfolio-project__image" src={`${API_BASE_URL}${imageUrl}`} /> : null}
      {project.description ? (
        <div className="portfolio-project__body"><MarkdownRenderer content={project.description} /></div>
      ) : null}
    </article>
  )
}
