import { useEffect, useState } from 'react'

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
    return <p className="skill-group-grid__error">{error}</p>
  }

  return (
    <div className="skill-group-grid">
      {groups.map((group) => (
        <SkillGroupCard group={group} key={group.id} onClick={onGroupClick} />
      ))}
    </div>
  )
}
