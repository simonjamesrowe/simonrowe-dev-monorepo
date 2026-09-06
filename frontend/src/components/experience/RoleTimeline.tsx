import { useEffect, useState } from 'react'

import { ErrorMessage } from '../common/ErrorMessage'
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

function getYear(dateStr: string): string {
  return new Date(dateStr).getFullYear().toString()
}

interface RoleTimelineProps {
  onJobClick?: (jobId: string) => void
}

export function RoleTimeline({ onJobClick }: RoleTimelineProps) {
  const [jobs, setJobs] = useState<IJob[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

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
  }, [attempt])

  if (loading) {
    return (
      <div className="role-timeline" aria-label="Loading experience" role="status">
        {Array.from({ length: 4 }).map((_, i) => (
          <div className={`role-timeline__item role-timeline__item--${i % 2 === 0 ? 'left' : 'right'}`} key={i}>
            <div className="role-timeline__card-wrap">
              <div className="role-timeline__card role-timeline__card--skeleton">
                <div className="skeleton-card skeleton-pulse" style={{ height: '10rem' }} />
              </div>
            </div>
            <div className="role-timeline__dot" />
          </div>
        ))}
      </div>
    )
  }

  if (error) {
    return (
      <ErrorMessage
        message={error}
        onRetry={() => setAttempt(prev => prev + 1)}
        title="Unable to load roles"
      />
    )
  }

  return (
    <div className="role-timeline" aria-label="Employment timeline">
      {jobs.map((job, index) => {
        const thumbnailUrl = job.companyImage?.formats?.thumbnail?.url ?? job.companyImage?.url
        const logoSrc = thumbnailUrl ? `${API_BASE_URL}${thumbnailUrl}` : null
        const isCurrent = !job.endDate
        const isLeft = index % 2 === 0

        const sideClass = isLeft ? 'role-timeline__item--left' : 'role-timeline__item--right'
        const stateClass = isCurrent
          ? 'role-timeline__item--current'
          : job.isEducation
            ? 'role-timeline__item--education'
            : ''

        const cardContent = (
          <div className="role-timeline__card-wrap">
            <div
              className={`role-timeline__card${index === 0 ? ' tour-experience-highlight' : ''}`}
              role="button"
              tabIndex={0}
              onClick={() => onJobClick?.(job.id)}
              onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') onJobClick?.(job.id) }}
            >
              <div className="role-timeline__card-header">
                <div className="role-timeline__logo">
                  {logoSrc ? (
                    <img alt={job.company} className="role-timeline__logo-image" src={logoSrc} />
                  ) : (
                    <div className="role-timeline__logo-placeholder" aria-label={job.company}>
                      {job.company.charAt(0)}
                    </div>
                  )}
                </div>
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
                </div>
              </div>
              <div className="role-timeline__meta">
                <span className={`role-timeline__dates-badge${isCurrent ? ' role-timeline__dates-badge--current' : ''}`}>
                  {formatDateRange(job.startDate, job.endDate)}
                </span>
                {job.location && <span className="role-timeline__location">{job.location}</span>}
              </div>
              <p className="role-timeline__description">{job.shortDescription}</p>
            </div>
          </div>
        )

        const yearLabel = <span className="role-timeline__year">{getYear(job.startDate)}</span>

        return (
          <div key={job.id} className={`role-timeline__item ${sideClass} ${stateClass}`}>
            {isLeft ? (
              <>
                {cardContent}
                <div className="role-timeline__dot" />
                {yearLabel}
              </>
            ) : (
              <>
                {yearLabel}
                <div className="role-timeline__dot" />
                {cardContent}
              </>
            )}
          </div>
        )
      })}
    </div>
  )
}
