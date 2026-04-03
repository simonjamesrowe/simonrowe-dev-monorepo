import { useEffect } from 'react'

import { ExpertiseGrid } from '../components/experience/ExpertiseGrid'
import { RoleTimeline } from '../components/experience/RoleTimeline'
import { trackPageView } from '../services/analytics'

export function ExperiencePage() {
  useEffect(() => {
    trackPageView('/experience')
    document.title = 'Experience & Skills | The Digital Architect'
  }, [])

  return (
    <div className="experience-page">
      <section className="experience-page__hero">
        <h1 className="display-lg experience-page__title">
          Architecting{' '}
          <span className="experience-page__title-accent">Digital Fortresses.</span>
        </h1>
        <p className="experience-page__subtitle body-lg">
          Simon Rowe, Senior Cloud Architect &amp; Cyber Sentinel. Precision-driven engineering for the AI-native frontier.
        </p>
      </section>

      <section className="experience-page__section">
        <h2 className="headline-lg" style={{ color: 'white', marginBottom: '2rem' }}>Experience</h2>
        <RoleTimeline />
      </section>

      <ExpertiseGrid />
    </div>
  )
}
