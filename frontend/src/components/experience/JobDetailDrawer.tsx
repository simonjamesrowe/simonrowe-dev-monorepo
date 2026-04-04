import { useEffect, useState } from 'react'
import { X } from 'lucide-react'
import ReactMarkdown from 'react-markdown'

import { fetchJob } from '../../services/jobsApi'
import type { IJobDetail } from '../../types/job'
import { API_BASE_URL } from '../../config/api'
import { SkillRatingBar } from '../skills/SkillRatingBar'

interface JobDetailDrawerProps {
  jobId: string
  onClose: () => void
  onSkillGroupClick?: (groupId: string) => void
}

function formatDate(dateStr: string): string {
  const date = new Date(dateStr)
  return date.toLocaleDateString('en-GB', { month: 'short', year: 'numeric' })
}

export function JobDetailDrawer({ jobId, onClose, onSkillGroupClick }: JobDetailDrawerProps) {
  const [job, setJob] = useState<IJobDetail | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    setLoading(true)

    fetchJob(jobId)
      .then(data => {
        if (!cancelled) setJob(data)
      })
      .catch(() => {
        // silently fail, drawer shows loading
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => { cancelled = true }
  }, [jobId])

  useEffect(() => {
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = '' }
  }, [])

  useEffect(() => {
    const handleEsc = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleEsc)
    return () => document.removeEventListener('keydown', handleEsc)
  }, [onClose])

  const logoUrl = job?.companyImage?.url
    ? `${API_BASE_URL}${job.companyImage.url}`
    : null

  const dateRange = job
    ? `${formatDate(job.startDate)} — ${job.endDate ? formatDate(job.endDate) : 'Present'}`
    : ''

  return (
    <div className="drawer-overlay" onClick={onClose}>
      <div className="drawer" onClick={e => e.stopPropagation()}>
        <div className="drawer__header">
          <span className="drawer__title">{loading ? 'Loading...' : job?.title ?? 'Job Detail'}</span>
          <button className="drawer__close" onClick={onClose} aria-label="Close" type="button">
            <X size={18} />
          </button>
        </div>
        <div className="drawer__body">
          {loading && (
            <div className="job-drawer__loading">
              <div className="skeleton-text skeleton-pulse" style={{ width: '60%', height: '1.5rem', marginBottom: '1rem' }} />
              <div className="skeleton-text skeleton-pulse" style={{ width: '40%', height: '1rem', marginBottom: '0.5rem' }} />
              <div className="skeleton-text skeleton-pulse" style={{ width: '30%', height: '1rem', marginBottom: '2rem' }} />
              <div className="skeleton-text skeleton-pulse" style={{ width: '100%', height: '0.875rem', marginBottom: '0.5rem' }} />
              <div className="skeleton-text skeleton-pulse" style={{ width: '100%', height: '0.875rem', marginBottom: '0.5rem' }} />
              <div className="skeleton-text skeleton-pulse" style={{ width: '80%', height: '0.875rem' }} />
            </div>
          )}
          {!loading && job && (
            <>
              <div className="job-drawer__header-info">
                {logoUrl && (
                  <img src={logoUrl} alt={job.company} className="job-drawer__logo" />
                )}
                <div>
                  <h3 className="job-drawer__title">{job.title}</h3>
                  <p className="job-drawer__company">
                    {job.companyUrl ? (
                      <a href={job.companyUrl} target="_blank" rel="noopener noreferrer">{job.company}</a>
                    ) : job.company}
                  </p>
                  <p className="job-drawer__meta">{dateRange} &middot; {job.location}</p>
                </div>
              </div>

              <div className="job-drawer__description">
                <ReactMarkdown>{job.longDescription}</ReactMarkdown>
              </div>

              {job.skills.length > 0 && (
                <div className="job-drawer__skills">
                  <h4 className="job-drawer__skills-title">Skills Used</h4>
                  <div className="job-drawer__skills-list">
                    {job.skills.map((skill, idx) => {
                      const skillImgUrl = skill.image?.url
                        ? `${API_BASE_URL}${skill.image.url}`
                        : null

                      return (
                        <button
                          key={`${skill.id}-${idx}`}
                          className="job-drawer__skill-item"
                          type="button"
                          onClick={() => onSkillGroupClick?.(skill.skillGroupId)}
                        >
                          {skillImgUrl ? (
                            <img src={skillImgUrl} alt={skill.name} className="job-drawer__skill-img" />
                          ) : (
                            <span className="job-drawer__skill-placeholder">{skill.name.charAt(0)}</span>
                          )}
                          <div className="job-drawer__skill-info">
                            <span className="job-drawer__skill-name">{skill.name}</span>
                            <SkillRatingBar rating={skill.rating} skillName={skill.name} />
                          </div>
                        </button>
                      )
                    })}
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}
