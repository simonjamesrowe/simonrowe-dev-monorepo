import { useEffect, useState } from 'react'

import { fetchSkillGroups } from '../../services/skillsApi'
import type { ISkillGroup } from '../../types/skill'

const CORE_GROUP_COUNT = 3

export function ExpertiseGrid() {
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
    return () => {
      cancelled = true
    }
  }, [])

  if (loading) {
    return (
      <section className="expertise-grid">
        <div className="expertise-grid__intro">
          <h2 className="headline-lg">The Arsenal of an Architect</h2>
        </div>
        <div className="expertise-grid__categories expertise-grid__categories--loading">
          {Array.from({ length: 6 }).map((_, i) => (
            <div className="expertise-grid__category" key={i}>
              <div className="skeleton-text skeleton-pulse" />
              <div className="expertise-grid__chips">
                {Array.from({ length: 4 }).map((__, j) => (
                  <span className="chip skeleton-pulse" key={j} />
                ))}
              </div>
            </div>
          ))}
        </div>
      </section>
    )
  }

  if (error) {
    return (
      <section className="expertise-grid">
        <div className="expertise-grid__intro">
          <h2 className="headline-lg">The Arsenal of an Architect</h2>
        </div>
        <p className="expertise-grid__error">{error}</p>
      </section>
    )
  }

  const sorted = [...groups].sort((a, b) => a.displayOrder - b.displayOrder)

  return (
    <section className="expertise-grid">
      <div className="expertise-grid__intro">
        <h2 className="headline-lg">The Arsenal of an Architect</h2>
        <p className="expertise-grid__subtitle">
          A curated set of technologies, platforms, and practices refined across years of
          architecting scalable systems and leading engineering teams.
        </p>
      </div>
      <div className="expertise-grid__categories">
        {sorted.map((group, index) => {
          const isCore = index < CORE_GROUP_COUNT
          return (
            <div className="expertise-grid__category" key={group.id}>
              <h3 className="expertise-grid__category-title">{group.name}</h3>
              <div className="expertise-grid__chips">
                {group.skills
                  .slice()
                  .sort((a, b) => a.displayOrder - b.displayOrder)
                  .map((skill) => (
                    <span
                      className={`chip${isCore ? ' chip--secondary' : ''}`}
                      key={skill.id}
                    >
                      {skill.name}
                    </span>
                  ))}
              </div>
            </div>
          )
        })}
      </div>
    </section>
  )
}
