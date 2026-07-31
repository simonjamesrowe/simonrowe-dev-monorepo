import { useCallback, useEffect, useState } from 'react'

import { ErrorMessage } from '../common/ErrorMessage'
import { fetchSkillGroups } from '../../services/skillsApi'
import type { ISkillGroup } from '../../types/skill'
import { SkillGroupCard } from './SkillGroupCard'

interface SkillGroupGridProps {
  onGroupClick: (groupId: string) => void
}

export function SkillGroupGrid({ onGroupClick }: SkillGroupGridProps) {
  const [groups, setGroups] = useState<ISkillGroup[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // Bumping the attempt counter re-runs the effect, which is what Retry does.
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false

    const load = async () => {
      setLoading(true)
      setError(null)
      try {
        const data = await fetchSkillGroups()
        if (!cancelled) {
          setGroups(data)
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Unable to load skills.')
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }

    void load()
    return () => { cancelled = true }
  }, [attempt])

  const retry = useCallback(() => {
    setAttempt((value) => value + 1)
  }, [])

  if (loading) {
    return (
      <div className="skill-group-grid skill-group-grid--loading">
        {Array.from({ length: 9 }).map((_, i) => (
          <div className="skill-group-card skill-group-card--skeleton" key={i}>
            <div className="skill-group-card__image-placeholder skeleton-pulse" />
            <div className="skill-group-card__content">
              <div className="skeleton-text skeleton-pulse" />
              <div className="skeleton-bar skeleton-pulse" />
            </div>
          </div>
        ))}
      </div>
    )
  }

  if (error) {
    return <ErrorMessage message={error} onRetry={retry} title="Unable to load skills" />
  }

  return (
    <div className="skill-group-grid">
      {groups.map((group) => (
        <SkillGroupCard group={group} key={group.id} onClick={onGroupClick} />
      ))}
    </div>
  )
}
