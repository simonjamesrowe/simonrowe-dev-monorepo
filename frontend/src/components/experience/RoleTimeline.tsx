import { useEffect, useState } from 'react'

import { fetchJobs } from '../../services/jobsApi'
import type { IJob } from '../../types/job'

function formatDate(dateStr: string): string {
  const date = new Date(dateStr)
  return date.toLocaleDateString('en-GB', { month: 'short', year: 'numeric' })
}

function formatDateRange(startDate: string, endDate?: string | null): string {
  return `${formatDate(startDate)} — ${endDate ? formatDate(endDate) : 'Present'}`
}

export function RoleTimeline() {
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
            <div className="card role-timeline__card role-timeline__card--skeleton" key={i}>
              <div className="skeleton-card skeleton-pulse" />
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
        {jobs.map((job) => {
          const thumbnailUrl = job.companyImage?.formats?.thumbnail?.url ?? job.companyImage?.url

          return (
            <div className="card role-timeline__card" key={job.id}>
              <div className="role-timeline__logo">
                {thumbnailUrl ? (
                  <img
                    alt={job.company}
                    className="role-timeline__logo-image"
                    src={thumbnailUrl}
                  />
                ) : (
                  <div
                    aria-label={job.company}
                    className="role-timeline__logo-placeholder"
                  >
                    {job.company.charAt(0)}
                  </div>
                )}
              </div>
              <h3 className="headline-md role-timeline__title">{job.title}</h3>
              <p className="role-timeline__company">{job.company}</p>
              <p className="role-timeline__dates">{formatDateRange(job.startDate, job.endDate)}</p>
              <p className="role-timeline__description">{job.shortDescription}</p>
            </div>
          )
        })}
      </div>
    </div>
  )
}
