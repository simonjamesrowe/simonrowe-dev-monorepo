import { useEffect, useState } from 'react'

import { fetchJobs } from '../../services/jobsApi'
import type { IJob } from '../../types/job'
import { API_BASE_URL } from '../../config/api'

function formatDate(dateStr: string): string {
  const date = new Date(dateStr)
  return date.toLocaleDateString('en-GB', { month: 'short', year: 'numeric' })
}

function formatDateRange(startDate: string, endDate?: string | null): string {
  return `${formatDate(startDate)} — ${endDate ? formatDate(endDate) : 'Present'}`
}

interface RoleTimelineProps {
  onJobClick?: (jobId: string) => void
}

export function RoleTimeline({ onJobClick }: RoleTimelineProps) {
  const [jobs, setJobs] = useState<IJob[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    const load = async () => {
      setLoading(true)
      setError(null)
      try {
        const data = await fetchJobs()
        if (!cancelled) {
          setJobs(data)
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Unable to load jobs data.')
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }

    void load()
    return () => {
      cancelled = true
    }
  }, [])

  if (loading) {
    return (
      <div className="role-timeline" aria-label="Loading experience" role="status">
        <div className="role-timeline__grid">
          {Array.from({ length: 4 }).map((_, i) => (
            <div className="role-timeline__card role-timeline__card--skeleton" key={i}>
              <div className="skeleton-card skeleton-pulse" style={{ height: '10rem' }} />
            </div>
          ))}
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="role-timeline" role="alert">
        <p className="role-timeline__error">{error}</p>
      </div>
    )
  }

  return (
    <div className="role-timeline" aria-label="Employment timeline">
      <div className="role-timeline__grid">
        {jobs.map((job, index) => {
          const thumbnailUrl = job.companyImage?.formats?.thumbnail?.url ?? job.companyImage?.url
          const logoSrc = thumbnailUrl ? `${API_BASE_URL}${thumbnailUrl}` : null
          const row = Math.floor(index / 2)
          const isEven = row % 2 === 1

          const logoEl = (
            <div className="role-timeline__logo">
              {logoSrc ? (
                <img alt={job.company} className="role-timeline__logo-image" src={logoSrc} />
              ) : (
                <div className="role-timeline__logo-placeholder" aria-label={job.company}>
                  {job.company.charAt(0)}
                </div>
              )}
            </div>
          )

          const infoEl = (
            <div className="role-timeline__card-info">
              <h3 className="role-timeline__title">{job.title}</h3>
              <p className="role-timeline__company">
                {job.companyUrl ? (
                  <a
                    href={job.companyUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    onClick={e => e.stopPropagation()}
                    className="role-timeline__company-link"
                  >
                    {job.company}
                  </a>
                ) : job.company}
              </p>
              <p className="role-timeline__dates">{formatDateRange(job.startDate, job.endDate)}</p>
            </div>
          )

          return (
            <div
              key={job.id}
              className={`role-timeline__card${isEven ? ' role-timeline__card--reversed' : ''}`}
              role="button"
              tabIndex={0}
              onClick={() => onJobClick?.(job.id)}
              onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') onJobClick?.(job.id) }}
            >
              <div className="role-timeline__card-header">
                {isEven ? (
                  <>{logoEl}{infoEl}</>
                ) : (
                  <>{infoEl}{logoEl}</>
                )}
              </div>
              <p className="role-timeline__description">{job.shortDescription}</p>
            </div>
          )
        })}
      </div>
    </div>
  )
}
