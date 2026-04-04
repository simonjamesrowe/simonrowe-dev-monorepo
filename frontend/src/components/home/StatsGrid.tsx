import { Terminal, Cloud } from 'lucide-react'
import { API_BASE_URL } from '../../config/api'
import type { ISkillGroup } from '../../types/skill'

interface StatsGridProps {
  profile: {
    name: string
    title: string
    description: string
    profileImage: { url: string }
  }
  skillGroups: ISkillGroup[]
  yearsExperience: number
  jobCount: number
}

export function StatsGrid({ profile, skillGroups, yearsExperience, jobCount }: StatsGridProps) {
  // Pick first two skill groups as capability highlights
  const sorted = [...skillGroups].sort((a, b) => a.displayOrder - b.displayOrder)
  const cap1 = sorted[0]
  const cap2 = sorted[1]

  return (
    <section className="stats-grid">
      <div className="stats-grid__grid">
        {/* Summary card — spans 2 cols */}
        <div className="card stats-grid__summary">
          <h3 className="headline-md" style={{ color: 'white' }}>The Vision</h3>
          <p className="body-lg" style={{ color: 'var(--on-surface-variant)', marginTop: '1.5rem' }}>
            {profile.description}
          </p>
          <div className="stats-grid__author">
            <img
              src={`${API_BASE_URL}${profile.profileImage.url}`}
              alt={profile.name}
              className="stats-grid__avatar"
            />
            <div>
              <p className="stats-grid__author-name">{profile.name}</p>
              <p className="stats-grid__author-title">{profile.title}</p>
            </div>
          </div>
        </div>

        {/* Years experience stat — 1 col */}
        <div className="card stats-grid__stat">
          <span className="stats-grid__stat-value stats-grid__stat-value--primary">{yearsExperience}+</span>
          <p className="stats-grid__stat-label">Years Experience</p>
        </div>

        {/* Roles held stat — 1 col */}
        <div className="card stats-grid__stat">
          <span className="stats-grid__stat-value stats-grid__stat-value--secondary">{jobCount}</span>
          <p className="stats-grid__stat-label">Roles Held</p>
        </div>

        {/* Capability sub-grid — spans 2 cols, 2x2 */}
        <div className="stats-grid__capabilities">
          {cap1 && (
            <div className="card stats-grid__capability">
              <Terminal size={32} className="stats-grid__capability-icon stats-grid__capability-icon--primary" />
              <div className="stats-grid__capability-text">
                <p className="stats-grid__capability-title">{cap1.name}</p>
                <p className="stats-grid__capability-desc">
                  {cap1.skills.slice(0, 4).map(s => s.name).join(', ')}
                </p>
              </div>
            </div>
          )}
          {cap2 && (
            <div className="card stats-grid__capability">
              <Cloud size={32} className="stats-grid__capability-icon stats-grid__capability-icon--secondary" />
              <div className="stats-grid__capability-text">
                <p className="stats-grid__capability-title">{cap2.name}</p>
                <p className="stats-grid__capability-desc">
                  {cap2.skills.slice(0, 4).map(s => s.name).join(', ')}
                </p>
              </div>
            </div>
          )}
        </div>

        {/* Skill groups count card — spans 2 cols */}
        <div className="card stats-grid__showcase">
          <div className="stats-grid__showcase-content">
            <h4 className="headline-md" style={{ color: 'white' }}>{skillGroups.length} Skill Domains</h4>
            <p style={{ color: 'var(--on-surface-variant)' }}>
              Spanning {skillGroups.reduce((acc, g) => acc + g.skills.length, 0)} individual technologies and practices.
            </p>
          </div>
        </div>
      </div>
    </section>
  )
}
