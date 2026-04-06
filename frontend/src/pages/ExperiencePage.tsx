import { useEffect } from 'react'

import { RoleTimeline } from '../components/experience/RoleTimeline'
import { SkillGroupGrid } from '../components/skills/SkillGroupGrid'
import { useDrawer } from '../hooks/useDrawer'
import { trackPageView } from '../services/analytics'

export function ExperiencePage() {
  const { openJob, openSkillGroup } = useDrawer()

  useEffect(() => {
    trackPageView('/experience')
    document.title = 'Experience & Skills'
  }, [])

  return (
    <div className="experience-page">
      <section className="experience-page__section tour-experience">
        <h2 className="headline-lg" style={{ color: 'white', marginBottom: '2rem' }}>Experience</h2>
        <RoleTimeline onJobClick={openJob} />
      </section>

      <section className="experience-page__section tour-skills">
        <h2 className="headline-lg" style={{ color: 'white', marginBottom: '0.5rem' }}>Skills</h2>
        <p className="body-lg" style={{ color: 'var(--on-surface-variant)', marginBottom: '2rem' }}>
          Click a skill group to explore individual skills and see where they&apos;ve been used.
        </p>
        <SkillGroupGrid onGroupClick={openSkillGroup} />
      </section>
    </div>
  )
}
