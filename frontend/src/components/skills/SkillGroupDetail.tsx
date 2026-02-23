import { useEffect, useRef, useState } from 'react'

import { fetchSkillGroup } from '../../services/skillsApi'
import type { ISkillGroupDetail } from '../../types/skill'
import { JobReferenceCard } from './JobReferenceCard'
import { SkillCard } from './SkillCard'

interface SkillGroupDetailProps {
  groupId: string
  onClose: () => void
  onJobClick: (jobId: string) => void
}

export function SkillGroupDetail({ groupId, onClose, onJobClick }: SkillGroupDetailProps) {
  const [group, setGroup] = useState<ISkillGroupDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const drawerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let cancelled = false

    const load = async () => {
      setLoading(true)
      setError(null)
      try {
        const data = await fetchSkillGroup(groupId)
        if (!cancelled) {
          setGroup(data)
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Unable to load skill group.')
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }

    void load()
    return () => { cancelled = true }
  }, [groupId])

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  useEffect(() => {
    if (!loading && group) {
      const hash = window.location.hash.slice(1)
      if (hash) {
        const el = document.getElementById(`skill-${hash}`)
        if (el) {
          el.scrollIntoView({ behavior: 'smooth', block: 'center' })
        }
      }
    }
  }, [loading, group])

  return (
    <div className="drawer-overlay" onClick={onClose} role="presentation">
      <div
        aria-label={group?.name ?? 'Skill group details'}
        className="drawer"
        onClick={(e) => e.stopPropagation()}
        ref={drawerRef}
        role="dialog"
      >
        <div className="drawer__header">
          <h3 className="drawer__title">{loading ? 'Loading...' : group?.name}</h3>
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

          {!loading && group && (
            <>
              {group.image?.url && (
                <img
                  alt={group.name}
                  className="drawer__hero-image"
                  src={group.image.url}
                />
              )}
              {group.description && (
                <p className="drawer__description">{group.description}</p>
              )}

              {group.skills.length === 0 ? (
                <p className="drawer__empty">No skills listed in this category.</p>
              ) : (
                <div className="drawer__skill-list">
                  {group.skills.map((skill) => (
                    <div className="drawer__skill-entry" key={skill.id}>
                      <SkillCard
                        description={skill.description}
                        id={skill.id}
                        image={skill.image}
                        name={skill.name}
                        rating={skill.rating}
                      />
                      {skill.jobs && skill.jobs.length > 0 && (
                        <div className="drawer__skill-jobs">
                          <span className="drawer__skill-jobs-label">Used in:</span>
                          {skill.jobs.map((job) => (
                            <JobReferenceCard
                              job={job}
                              key={job.id}
                              onClick={onJobClick}
                            />
                          ))}
                        </div>
                      )}
                      {skill.jobs && skill.jobs.length === 0 && (
                        <p className="drawer__no-correlations">Not linked to any positions</p>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}
