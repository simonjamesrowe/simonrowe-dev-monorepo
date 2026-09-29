import { useEffect } from 'react'

import { ErrorMessage } from '../components/common/ErrorMessage'
import { LoadingIndicator } from '../components/common/LoadingIndicator'
import { ProjectCard } from '../components/portfolio/ProjectCard'
import { usePageTitle } from '../hooks/usePageTitle'
import { usePortfolio } from '../hooks/usePortfolio'
import { trackPageView } from '../services/analytics'

export function PortfolioPage() {
  const { projects, loading, error } = usePortfolio()
  usePageTitle('Portfolio')

  useEffect(() => {
    trackPageView('/portfolio')
  }, [])

  return (
    <div className="portfolio-page">
      <header className="portfolio-page__header">
        <p className="portfolio-carousel__eyebrow">Portfolio</p>
        <h1 className="portfolio-page__title">What I&rsquo;m building</h1>
        <p className="portfolio-page__intro">
          Products I design, build and run, each on the same platform as this site.
        </p>
      </header>
      {loading ? <LoadingIndicator message="Loading projects..." /> : null}
      {error ? (
        <ErrorMessage message={error} onRetry={() => window.location.reload()} title="Unable to load the portfolio" />
      ) : null}
      {!loading && !error && projects.length === 0 ? (
        <p className="portfolio-page__empty">Nothing to show yet.</p>
      ) : null}
      {projects.length > 0 ? (
        <ul className="portfolio-page__grid">
          {projects.map(project => (
            <li key={project.slug}><ProjectCard project={project} /></li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
