import { useEffect, useState } from 'react'

import { fetchJobs } from '../../services/jobsApi'
import type { IJob } from '../../types/job'
import { TimelineEntry } from './TimelineEntry'

interface TimelineProps {
  onJobClick: (jobId: string) => void
}

function getEntrySide(index: number): 'left' | 'right' {
  const group = Math.floor(index / 2)
  return group % 2 === 0 ? 'left' : 'right'
}

export function Timeline({ onJobClick }: TimelineProps) {
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
          setError(err instanceof Error ? err.message : 'Unable to load employment data.')
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }

    void load()
    return () => { cancelled = true }
  }, [])

  if (loading) {
    return (
      <div className="timeline timeline--loading" role="list">
        {Array.from({ length: 4 }).map((_, i) => (
          <div className="timeline-entry timeline-entry--skeleton" key={i}>
            <div className="skeleton-text skeleton-pulse" />
            <div className="skeleton-card skeleton-pulse" />
          </div>
        ))}
      </div>
    )
  }

  if (error) {
    return <p className="timeline__error">{error}</p>
  }

  return (
    <div aria-label="Employment timeline" className="timeline" role="list">
      {jobs.map((job, index) => (
        <TimelineEntry
          job={job}
          key={job.id}
          onClick={onJobClick}
          side={getEntrySide(index)}
        />
      ))}
    </div>
  )
}
