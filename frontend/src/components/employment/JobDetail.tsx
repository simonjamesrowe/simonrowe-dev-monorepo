import { useEffect, useState } from 'react'

import { fetchJob } from '../../services/jobsApi'
import type { IJobDetail } from '../../types/job'
import { JobAboutTab } from './JobAboutTab'
import { JobSkillsTab } from './JobSkillsTab'

interface JobDetailProps {
  jobId: string
  onClose: () => void
  onSkillClick: (skillGroupId: string, skillId: string) => void
}

type Tab = 'about' | 'skills'

function formatDate(dateStr: string): string {
  const date = new Date(dateStr)
  return date.toLocaleDateString('en-GB', { month: 'short', year: 'numeric' })
}

export function JobDetail({ jobId, onClose, onSkillClick }: JobDetailProps) {
  const [job, setJob] = useState<IJobDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [activeTab, setActiveTab] = useState<Tab>('about')

  useEffect(() => {
    let cancelled = false

    const load = async () => {
      setLoading(true)
      setError(null)
      try {
        const data = await fetchJob(jobId)
        if (!cancelled) {
          setJob(data)
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Unable to load job details.')
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }

    void load()
    return () => { cancelled = true }
  }, [jobId])

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  return (
    <div className="drawer-overlay" onClick={onClose} role="presentation">
      <div
        aria-label={job?.title ?? 'Job details'}
        className="drawer"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
      >
        <div className="drawer__header">
          <div>
            <h3 className="drawer__title">{loading ? 'Loading...' : job?.title}</h3>
            {job && (
              <span className="drawer__subtitle">
                {job.company} &middot; {formatDate(job.startDate)} — {job.endDate ? formatDate(job.endDate) : 'Present'}
              </span>
            )}
          </div>
          <button
            aria-label="Close"
            className="drawer__close"
            onClick={onClose}
            type="button"
          >
            ✕
          </button>
        </div>

        <div className="drawer__body">
          {loading && (
            <div className="drawer__loading">
              <div className="loading-state__spinner" />
            </div>
          )}

          {error && <p className="drawer__error">{error}</p>}

          {!loading && job && (
            <>
              <div className="drawer__tabs">
                <button
                  className={`drawer__tab ${activeTab === 'about' ? 'drawer__tab--active' : ''}`}
                  onClick={() => setActiveTab('about')}
                  type="button"
                >
                  About
                </button>
                <button
                  className={`drawer__tab ${activeTab === 'skills' ? 'drawer__tab--active' : ''}`}
                  onClick={() => setActiveTab('skills')}
                  type="button"
                >
                  Skills
                </button>
              </div>

              {activeTab === 'about' && <JobAboutTab job={job} />}
              {activeTab === 'skills' && (
                <JobSkillsTab
                  onSkillClick={onSkillClick}
                  skills={job.skills}
                />
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}
